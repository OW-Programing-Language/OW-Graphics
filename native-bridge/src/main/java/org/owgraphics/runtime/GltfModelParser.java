package org.owgraphics.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Explicitly bounded glTF 2.0 embedded-buffer static mesh subset.
 * No external URIs, GLB, extensions, external files or scripts.
 *
 * Data returned to OW:
 * [positions, uvs, normals, indices, baseColorRGBA, metallic, roughness].
 * The first mesh's first TRIANGLES primitive is loaded; subsequent primitives
 * and scene/node/skin/animation definitions are intentionally not interpreted.
 */
final class GltfModelParser {
    private static final int MAX_JSON_BYTES = 1_048_576;
    private static final int MAX_BINARY_BYTES = 4 * 1024 * 1024;
    private static final int MAX_VERTICES = 100_000;
    private GltfModelParser() {}

    static List<Object> loadRelative(String filename, Path root) {
        if (filename == null || filename.isBlank() || filename.length() > 4096
                || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".gltf"))
            throw new IllegalArgumentException("glTF file must use a relative .gltf name");
        Path requested;
        try { requested = Path.of(filename); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid glTF path", invalid); }
        if (requested.isAbsolute())
            throw new IllegalArgumentException("Absolute glTF paths are not supported");
        for (Path part : requested)
            if (part.toString().equals(".."))
                throw new IllegalArgumentException("Parent traversal in glTF paths is forbidden");
        try {
            Path realRoot = root.toRealPath();
            Path file = realRoot.resolve(requested).toRealPath();
            if (!file.startsWith(realRoot) || !Files.isRegularFile(file))
                throw new IllegalArgumentException("glTF must be a regular project file");
            if (Files.size(file) > MAX_JSON_BYTES)
                throw new IllegalArgumentException("glTF JSON exceeds 1 MiB");
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read glTF file: " + filename, failure);
        }
    }

    private static JsonObject object(JsonElement value, String label) {
        if (value == null || !value.isJsonObject())
            throw new IllegalArgumentException(label + " must be a JSON object");
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject owner, String member) {
        JsonElement value = owner.get(member);
        if (value == null || !value.isJsonArray())
            throw new IllegalArgumentException(member + " must be a JSON array");
        return value.getAsJsonArray();
    }

    private static int number(JsonObject owner, String member, int fallback) {
        if (!owner.has(member)) return fallback;
        try {
            double raw = owner.get(member).getAsDouble();
            if (!Double.isFinite(raw) || raw != Math.rint(raw)
                    || raw < Integer.MIN_VALUE || raw > Integer.MAX_VALUE)
                throw new IllegalArgumentException(member + " must be an integer");
            return (int) raw;
        } catch (UnsupportedOperationException | NumberFormatException e) {
            throw new IllegalArgumentException(member + " must be an integer", e);
        }
    }

    private static double finite(JsonElement element, String label) {
        if (element == null || !element.isJsonPrimitive())
            throw new IllegalArgumentException(label + " must be numeric");
        double number;
        try { number = element.getAsDouble(); }
        catch (RuntimeException e) { throw new IllegalArgumentException(label + " must be numeric", e); }
        if (!Double.isFinite(number) || Math.abs(number) > Float.MAX_VALUE)
            throw new IllegalArgumentException(label + " must be a finite float");
        return number;
    }

    private record Accessor(ByteBuffer bytes, int offset, int stride, int count,
                            int components, int componentType) {
        double value(int row, int col) {
            int position = offset + row * stride + col * componentBytes(componentType);
            return switch (componentType) {
                case 5126 -> bytes.getFloat(position);
                case 5125 -> Integer.toUnsignedLong(bytes.getInt(position));
                case 5123 -> Short.toUnsignedInt(bytes.getShort(position));
                case 5121 -> Byte.toUnsignedInt(bytes.get(position));
                default -> throw new IllegalArgumentException("Unsupported accessor component type");
            };
        }
    }

    private static int componentBytes(int type) {
        return switch (type) {
            case 5126, 5125 -> 4;
            case 5123 -> 2;
            case 5121 -> 1;
            default -> throw new IllegalArgumentException("Unsupported glTF accessor component type " + type);
        };
    }

    private static int checkedIndex(int value, int size, String label) {
        if (value < 0 || value >= size)
            throw new IllegalArgumentException("glTF " + label + " is out of range");
        return value;
    }

    private static Accessor accessor(JsonObject root, ByteBuffer data, int index) {
        JsonArray accessors = array(root,"accessors");
        JsonObject spec = object(accessors.get(checkedIndex(index, accessors.size(),"accessor")), "accessor");
        if (spec.has("sparse") || spec.has("normalized") && spec.get("normalized").getAsBoolean())
            throw new IllegalArgumentException("Sparse or normalized accessors are not supported");
        int type = number(spec,"componentType",-1);
        int components = switch (spec.get("type").getAsString()) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            default -> throw new IllegalArgumentException("Unsupported glTF attribute type");
        };
        int bytesPerComponent = componentBytes(type);
        int count = number(spec,"count",-1);
        if (count < 1 || count > MAX_VERTICES * 3)
            throw new IllegalArgumentException("glTF accessor count out of range");
        JsonArray views = array(root,"bufferViews");
        int viewIndex = number(spec,"bufferView",-1);
        JsonObject view = object(views.get(checkedIndex(viewIndex,views.size(),"buffer view")), "bufferView");
        if (number(view,"buffer",0) != 0)
            throw new IllegalArgumentException("Only embedded buffer zero is supported");
        int start = number(view,"byteOffset",0);
        int length = number(view,"byteLength",-1);
        int relative = number(spec,"byteOffset",0);
        int elementSize = bytesPerComponent * components;
        int stride = number(view,"byteStride",elementSize);
        if (stride < elementSize || stride > 252 || stride % bytesPerComponent != 0
                || start < 0 || relative < 0 || length < 0
                || start % bytesPerComponent != 0 || relative % bytesPerComponent != 0)
            throw new IllegalArgumentException("Invalid glTF buffer offset/stride");
        long endWithinView = (long) relative + (long)(count-1)*stride + elementSize;
        long endView = (long) start + length;
        if (endWithinView > length || endView > data.capacity())
            throw new IllegalArgumentException("glTF accessor exceeds buffer view");
        return new Accessor(data,start+relative,stride,count,components,type);
    }

    private static Accessor attribute(JsonObject root, ByteBuffer buffer,
                                      JsonObject properties, String key, int components) {
        if (!properties.has(key)) return null;
        int index;
        try { index = properties.get(key).getAsInt(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid " + key + " accessor",e); }
        Accessor a = accessor(root, buffer, index);
        if (a.components != components || a.componentType != 5126)
            throw new IllegalArgumentException(key + " must be FLOAT VEC" + components);
        return a;
    }

    static List<Object> parse(String source) {
        if (source == null || source.isBlank()
                || source.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES)
            throw new IllegalArgumentException("glTF JSON must contain 1..1048576 bytes");
        JsonObject root;
        try { root = object(JsonParser.parseString(source),"glTF document"); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Malformed glTF JSON",e); }
        JsonObject asset = object(root.get("asset"),"glTF asset");
        if (!"2.0".equals(asset.get("version").getAsString()))
            throw new IllegalArgumentException("Only glTF 2.0 is supported");
        if (root.has("extensionsRequired") && root.getAsJsonArray("extensionsRequired").size() != 0)
            throw new IllegalArgumentException("Required glTF extensions are not supported");
        JsonArray buffers = array(root,"buffers");
        if (buffers.size() != 1)
            throw new IllegalArgumentException("Exactly one embedded glTF buffer is supported");
        JsonObject buffer = object(buffers.get(0),"glTF buffer");
        String uri = buffer.get("uri").getAsString();
        String prefix = "data:application/octet-stream;base64,";
        if (!uri.startsWith(prefix))
            throw new IllegalArgumentException("External/remote glTF buffer URIs are not supported");
        String content = uri.substring(prefix.length());
        if (content.length() > (MAX_BINARY_BYTES + 2L)/3*4 + 8)
            throw new IllegalArgumentException("glTF data URI exceeds 4 MiB");
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(content); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Bad glTF base64 buffer",e); }
        if (decoded.length > MAX_BINARY_BYTES || number(buffer,"byteLength",-1) > decoded.length)
            throw new IllegalArgumentException("glTF binary buffer is invalid or too large");
        ByteBuffer data = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN);
        JsonArray meshes = array(root,"meshes");
        if (meshes.isEmpty())
            throw new IllegalArgumentException("glTF needs at least one mesh");
        JsonArray primitives = array(object(meshes.get(0),"mesh"),"primitives");
        if (primitives.size() != 1)
            throw new IllegalArgumentException("This importer supports exactly one mesh primitive");
        JsonObject primitive = object(primitives.get(0),"primitive");
        if (number(primitive,"mode",4) != 4)
            throw new IllegalArgumentException("Only glTF TRIANGLES mode 4 is supported");
        JsonObject attrs = object(primitive.get("attributes"),"attributes");
        Accessor xyz = attribute(root,data,attrs,"POSITION",3);
        if (xyz == null || xyz.count > MAX_VERTICES)
            throw new IllegalArgumentException("POSITION accessor required, max 100000 vertices");
        Accessor uv = attribute(root,data,attrs,"TEXCOORD_0",2);
        Accessor normals = attribute(root,data,attrs,"NORMAL",3);
        if (uv != null && uv.count != xyz.count || normals != null && normals.count != xyz.count)
            throw new IllegalArgumentException("glTF attribute vertex counts disagree");
        List<Double> outXYZ = new ArrayList<>(xyz.count*3);
        List<Double> outUv = new ArrayList<>(xyz.count*2);
        List<Double> outNormals = new ArrayList<>(xyz.count*3);
        for (int i=0;i<xyz.count;i++) {
            for (int c=0;c<3;c++)
                outXYZ.add(xyz.value(i,c));
            for (int c=0;c<2;c++)
                outUv.add(uv==null?0.0:uv.value(i,c));
            for (int c=0;c<3;c++)
                outNormals.add(normals==null?0.0:normals.value(i,c));
        }
        List<Integer> outIndices = new ArrayList<>();
        if (primitive.has("indices")) {
            int idx = number(primitive,"indices",-1);
            Accessor indices = accessor(root,data,idx);
            if (indices.components != 1 || indices.componentType == 5126
                    || indices.count > 300_000 || indices.count % 3 != 0)
                throw new IllegalArgumentException("glTF indices must be unsigned scalar triangles");
            for (int i=0;i<indices.count;i++) {
                double value = indices.value(i,0);
                if (value < 0 || value >= xyz.count)
                    throw new IllegalArgumentException("glTF index out of vertex bounds");
                outIndices.add((int)value);
            }
        } else {
            if (xyz.count % 3 != 0)
                throw new IllegalArgumentException("Nonindexed glTF vertices must be triangles");
            for (int i=0;i<xyz.count;i++) outIndices.add(i);
        }

        if (normals == null) {
            double[] sums = new double[xyz.count*3];
            for (int k=0;k<outIndices.size();k+=3) {
                int ia=outIndices.get(k), ib=outIndices.get(k+1), ic=outIndices.get(k+2);
                int a=ia*3,b=ib*3,c=ic*3;
                double ux=outXYZ.get(b)-outXYZ.get(a);
                double uy=outXYZ.get(b+1)-outXYZ.get(a+1);
                double uz=outXYZ.get(b+2)-outXYZ.get(a+2);
                double vx=outXYZ.get(c)-outXYZ.get(a);
                double vy=outXYZ.get(c+1)-outXYZ.get(a+1);
                double vz=outXYZ.get(c+2)-outXYZ.get(a+2);
                double nx=uy*vz-uz*vy, ny=uz*vx-ux*vz, nz=ux*vy-uy*vx;
                if (!Double.isFinite(nx+ny+nz) || nx*nx+ny*ny+nz*nz < 1e-18)
                    throw new IllegalArgumentException("Degenerate glTF triangle");
                for (int at : new int[]{a,b,c}) {
                    sums[at]+=nx;sums[at+1]+=ny;sums[at+2]+=nz;
                }
            }
            outNormals.clear();
            for (int i=0;i<sums.length;i+=3) {
                double length = Math.sqrt(sums[i]*sums[i]+sums[i+1]*sums[i+1]+sums[i+2]*sums[i+2]);
                if (!Double.isFinite(length) || length < 1e-9)
                    throw new IllegalArgumentException("Unreferenced or invalid glTF vertex normal");
                outNormals.add(sums[i]/length);
                outNormals.add(sums[i+1]/length);
                outNormals.add(sums[i+2]/length);
            }
        }

        List<Double> color = List.of(1.0,1.0,1.0,1.0);
        double metallic=1.0, roughness=1.0;
        if (primitive.has("material")) {
            JsonArray materials = array(root,"materials");
            JsonObject mat = object(materials.get(checkedIndex(
                    number(primitive,"material",-1),materials.size(),"material")),"material");
            if (mat.has("extensions"))
                throw new IllegalArgumentException("Material extensions are not supported");
            if (mat.has("pbrMetallicRoughness")) {
                JsonObject pbr = object(mat.get("pbrMetallicRoughness"),"pbrMetallicRoughness");
                if (pbr.has("baseColorTexture") || pbr.has("metallicRoughnessTexture"))
                    throw new IllegalArgumentException("Embedded glTF textures are not implemented");
                if (pbr.has("baseColorFactor")) {
                    JsonArray rgba = array(pbr,"baseColorFactor");
                    if (rgba.size()!=4)
                        throw new IllegalArgumentException("baseColorFactor must have 4 channels");
                    List<Double> components = new ArrayList<>(4);
                    for (JsonElement v : rgba) components.add(finite(v,"baseColorFactor"));
                    color=components;
                }
                if (pbr.has("metallicFactor")) metallic=finite(pbr.get("metallicFactor"),"metallic");
                if (pbr.has("roughnessFactor")) roughness=finite(pbr.get("roughnessFactor"),"roughness");
            }
        }
        if (metallic<0 || metallic>1 || roughness<0 || roughness>1)
            throw new IllegalArgumentException("Metallic/roughness factors must be in [0,1]");
        for (Double value:color) if (value<0 || value>1)
            throw new IllegalArgumentException("baseColorFactor channels must be in [0,1]");
        return List.of(outXYZ,outUv,outNormals,outIndices,color,metallic,roughness);
    }
}

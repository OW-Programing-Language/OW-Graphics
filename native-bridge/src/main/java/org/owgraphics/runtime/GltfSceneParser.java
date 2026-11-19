package org.owgraphics.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Bounded glTF 2.0 / GLB static-scene loader, designed to reuse the existing
 * typed mesh + parent-first pure OW scene graph instead of creating a competing
 * native renderer. No external buffers, URI fetching, skins or animations.
 *
 * Result [primitives, parents, localTransforms, nodePrimitiveIds]:
 * - primitives[i] is the existing 7-value glTF mesh array
 * - parents[] is parent-first (-1 for a root)
 * - localTransforms[] contains OpenGL column-major mat4 arrays
 * - nodePrimitiveIds[n] contains zero or more primitive indexes for that node
 *
 * No GPU context is required for any of these operations.
 */
final class GltfSceneParser {
    private static final int MAX_JSON_BYTES = 1_048_576;
    private static final int MAX_BIN_BYTES = 4 * 1024 * 1024;
    private static final int MAX_GLB_BYTES = MAX_JSON_BYTES + MAX_BIN_BYTES + 32;
    private static final int MAX_MESHES = 64;
    private static final int MAX_PRIMITIVES = 64;
    private static final int MAX_NODES = 1024;

    private GltfSceneParser() {}

    static List<Object> loadRelative(String name, Path root) {
        if (name == null || name.isBlank() || name.length() > 4096)
            throw new IllegalArgumentException("glTF scene filename is required");
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".gltf") && !lower.endsWith(".glb"))
            throw new IllegalArgumentException("Static scene must be .gltf or .glb");
        Path requested;
        try { requested = Path.of(name); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid scene path",invalid); }
        if (requested.isAbsolute())
            throw new IllegalArgumentException("Absolute scene paths are forbidden");
        for (Path item : requested)
            if (item.toString().equals(".."))
                throw new IllegalArgumentException("Parent traversal in scene paths is forbidden");
        try {
            Path realRoot = root.toRealPath();
            Path file = realRoot.resolve(requested).toRealPath();
            if (!file.startsWith(realRoot) || !Files.isRegularFile(file))
                throw new IllegalArgumentException("Scene file must remain within the working directory");
            long size = Files.size(file);
            if (size < 1 || size > (lower.endsWith(".glb") ? MAX_GLB_BYTES : MAX_JSON_BYTES))
                throw new IllegalArgumentException("Scene file exceeds its size limit");
            return lower.endsWith(".glb") ? parseGlb(Files.readAllBytes(file))
                    : parseJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read local glTF scene: " + name,e);
        }
    }

    static List<Object> parseJson(String source) {
        return fromDocument(GltfModelParser.document(source));
    }

    /**
     * glTF 2.0 binary container with one JSON chunk followed by one BIN chunk.
     * No unknown chunk types, silently ignored trailing bytes or network URIs.
     */
    static List<Object> parseGlb(byte[] bytes) {
        if (bytes == null || bytes.length < 28 || bytes.length > MAX_GLB_BYTES
                || (bytes.length & 3) != 0)
            throw new IllegalArgumentException("GLB container length must be bounded and 4-aligned");
        ByteBuffer input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt()!=0x46546c67 || input.getInt()!=2 || input.getInt()!=bytes.length)
            throw new IllegalArgumentException("Invalid GLB magic, version or declared length");
        int jsonBytes = input.getInt();
        int jsonType = input.getInt();
        if (jsonType!=0x4e4f534a || jsonBytes<4 || jsonBytes>MAX_JSON_BYTES
                || (jsonBytes&3)!=0 || input.remaining()<jsonBytes+8)
            throw new IllegalArgumentException("Invalid GLB JSON chunk");
        byte[] json = new byte[jsonBytes];
        input.get(json);
        int binBytes=input.getInt();
        int binType=input.getInt();
        if (binType!=0x004e4942 || binBytes<1 || binBytes>MAX_BIN_BYTES+3
                || (binBytes&3)!=0 || input.remaining()!=binBytes)
            throw new IllegalArgumentException("Invalid GLB BIN chunk");
        byte[] binary=new byte[binBytes];
        input.get(binary);
        // Official JSON chunks are space-padded. Gson permits whitespace after
        // the closing brace, but NUL padding is invalid and intentionally refused.
        return fromDocument(GltfModelParser.document(new String(json,StandardCharsets.UTF_8),binary));
    }

    private static JsonObject object(JsonElement value,String label) {
        if (value==null || !value.isJsonObject())
            throw new IllegalArgumentException(label+" must be an object");
        return value.getAsJsonObject();
    }
    private static JsonArray array(JsonObject obj,String key) {
        if (obj==null || !obj.has(key) || !obj.get(key).isJsonArray())
            throw new IllegalArgumentException("glTF "+key+" must be an array");
        return obj.getAsJsonArray(key);
    }
    private static int integer(JsonElement element,String label) {
        if (element==null || !element.isJsonPrimitive())
            throw new IllegalArgumentException(label+" must be an integer");
        try {
            double n=element.getAsDouble();
            if (!Double.isFinite(n) || n!=Math.rint(n) || n<Integer.MIN_VALUE || n>Integer.MAX_VALUE)
                throw new IllegalArgumentException(label+" must be an integer");
            return (int)n;
        } catch (NumberFormatException | UnsupportedOperationException e) {
            throw new IllegalArgumentException(label+" must be an integer",e);
        }
    }
    private static int optionalInteger(JsonObject obj,String key,int fallback) {
        return obj.has(key) ? integer(obj.get(key),key) : fallback;
    }
    private static double finite(JsonElement element,String label) {
        if (element==null || !element.isJsonPrimitive())
            throw new IllegalArgumentException(label+" must be a finite number");
        double v;
        try { v=element.getAsDouble(); }
        catch (RuntimeException e) { throw new IllegalArgumentException(label+" must be a number",e); }
        if (!Double.isFinite(v) || Math.abs(v)>Float.MAX_VALUE)
            throw new IllegalArgumentException(label+" must be a finite float");
        return v;
    }
    private static List<Double> vector(JsonObject node,String member,int size,double[] fallback) {
        if (!node.has(member)) {
            List<Double> out=new ArrayList<>(size);
            for (double v:fallback)out.add(v);
            return out;
        }
        JsonArray values=array(node,member);
        if (values.size()!=size)
            throw new IllegalArgumentException(member+" needs "+size+" values");
        List<Double> result=new ArrayList<>(size);
        for (JsonElement item:values)result.add(finite(item,member));
        return result;
    }
    private static List<Double> localTransform(JsonObject node) {
        if (node.has("matrix")) {
            if (node.has("translation") || node.has("rotation") || node.has("scale"))
                throw new IllegalArgumentException("glTF matrix and TRS are mutually exclusive");
            return vector(node,"matrix",16,new double[16]);
        }
        List<Double> pos=vector(node,"translation",3,new double[]{0,0,0});
        List<Double> scale=vector(node,"scale",3,new double[]{1,1,1});
        List<Double> rot=vector(node,"rotation",4,new double[]{0,0,0,1});
        double x=rot.get(0),y=rot.get(1),z=rot.get(2),w=rot.get(3);
        double qlen=Math.sqrt(x*x+y*y+z*z+w*w);
        if (!Double.isFinite(qlen) || qlen<1e-12)
            throw new IllegalArgumentException("glTF quaternion must be finite and nonzero");
        x/=qlen;y/=qlen;z/=qlen;w/=qlen;
        double sx=scale.get(0),sy=scale.get(1),sz=scale.get(2);
        // Column-major OpenGL 4x4 matrix T*R*S; no transpose.
        double[] raw={
            (1-2*(y*y+z*z))*sx, 2*(x*y+z*w)*sx, 2*(x*z-y*w)*sx, 0,
            2*(x*y-z*w)*sy, (1-2*(x*x+z*z))*sy, 2*(y*z+x*w)*sy, 0,
            2*(x*z+y*w)*sz, 2*(y*z-x*w)*sz, (1-2*(x*x+y*y))*sz, 0,
            pos.get(0),pos.get(1),pos.get(2),1
        };
        List<Double> result=new ArrayList<>(16);
        for(double value:raw) {
            if (!Double.isFinite(value)||Math.abs(value)>Float.MAX_VALUE)
                throw new IllegalArgumentException("glTF node transform overflow");
            result.add(value);
        }
        return result;
    }

    private static List<Object> fromDocument(GltfModelParser.EmbeddedDocument doc) {
        JsonObject root=doc.root();
        if (root.has("skins") && !root.getAsJsonArray("skins").isEmpty())
            throw new IllegalArgumentException("glTF skins are not yet supported");
        if (root.has("animations") && !root.getAsJsonArray("animations").isEmpty())
            throw new IllegalArgumentException("glTF animation tracks are not yet supported");
        JsonArray meshes=array(root,"meshes");
        if (meshes.isEmpty() || meshes.size()>MAX_MESHES)
            throw new IllegalArgumentException("glTF scene meshes must be 1..64");
        List<Object> geometries=new ArrayList<>();
        List<List<Integer>> primitiveRefs=new ArrayList<>();
        for (JsonElement element:meshes) {
            JsonObject mesh=object(element,"mesh");
            JsonArray primitives=array(mesh,"primitives");
            if (primitives.isEmpty() || primitives.size()>MAX_PRIMITIVES
                    || geometries.size()+primitives.size()>MAX_PRIMITIVES)
                throw new IllegalArgumentException("glTF scene exceeds 64 primitives");
            List<Integer> ids=new ArrayList<>();
            for (JsonElement primitive:primitives) {
                List<Object> parsed=GltfModelParser.parsePrimitive(
                        root,doc.data(),object(primitive,"primitive"));
                ids.add(geometries.size());
                geometries.add(parsed);
            }
            primitiveRefs.add(ids);
        }

        JsonArray nodes=array(root,"nodes");
        if (nodes.isEmpty() || nodes.size()>MAX_NODES)
            throw new IllegalArgumentException("glTF scene nodes must be 1..1024");
        List<JsonObject> nodeObjects=new ArrayList<>(nodes.size());
        for(JsonElement element:nodes)nodeObjects.add(object(element,"node"));
        List<List<Integer>> edges=new ArrayList<>();
        int[] parentByOriginal=new int[nodes.size()];
        Arrays.fill(parentByOriginal,-1);
        for (int i=0;i<nodes.size();i++) {
            JsonObject node=nodeObjects.get(i);
            if (node.has("extensions") || node.has("skin") || node.has("weights"))
                throw new IllegalArgumentException("glTF node extensions, skins and morph weights are unsupported");
            List<Integer> children=new ArrayList<>();
            if (node.has("children")) {
                JsonArray kids=array(node,"children");
                for(JsonElement element:kids) {
                    int id=integer(element,"child");
                    if(id<0 || id>=nodes.size() || id==i || parentByOriginal[id]>=0)
                        throw new IllegalArgumentException("Invalid or shared glTF node child");
                    parentByOriginal[id]=i;
                    children.add(id);
                }
            }
            edges.add(children);
        }
        // Check all nodes (including nodes outside the active scene) for cycles.
        byte[] state=new byte[nodes.size()];
        for(int i=0;i<nodes.size();i++)checkAcyclic(i,edges,state);

        List<Integer> roots=new ArrayList<>();
        if(root.has("scenes")) {
            JsonArray scenes=array(root,"scenes");
            if(scenes.isEmpty() || scenes.size()>32)
                throw new IllegalArgumentException("glTF scenes must have 1..32 entries");
            int sceneIndex=optionalInteger(root,"scene",0);
            if(sceneIndex<0 || sceneIndex>=scenes.size())
                throw new IllegalArgumentException("Invalid glTF default scene");
            JsonArray top=array(object(scenes.get(sceneIndex),"scene"),"nodes");
            for(JsonElement element:top) {
                int nodeId=integer(element,"scene root");
                if(nodeId<0||nodeId>=nodes.size()||parentByOriginal[nodeId]>=0)
                    throw new IllegalArgumentException("Selected glTF scene root is invalid");
                roots.add(nodeId);
            }
        } else {
            for(int i=0;i<nodes.size();i++)
                if(parentByOriginal[i]<0)roots.add(i);
        }
        if(roots.isEmpty())
            throw new IllegalArgumentException("glTF scene must contain a root node");
        List<Integer> parents=new ArrayList<>();
        List<Object> localMatrices=new ArrayList<>();
        List<Object> perNodePrimitives=new ArrayList<>();
        boolean[] visited=new boolean[nodes.size()];
        for (int rootId:roots) {
            appendNode(rootId,-1,nodeObjects,edges,primitiveRefs,visited,
                    parents,localMatrices,perNodePrimitives);
        }
        return List.of(geometries,parents,localMatrices,perNodePrimitives);
    }

    private static void checkAcyclic(int id,List<List<Integer>> edges,byte[] state) {
        if(state[id]==2)return;
        if(state[id]==1)
            throw new IllegalArgumentException("Cycle in glTF node graph");
        state[id]=1;
        for(int child:edges.get(id))checkAcyclic(child,edges,state);
        state[id]=2;
    }
    private static void appendNode(int id,int parent,List<JsonObject> original,
                                   List<List<Integer>> edges,List<List<Integer>> primitiveRefs,
                                   boolean[] visited,List<Integer> parents,List<Object> transforms,
                                   List<Object> nodes) {
        if(visited[id])
            throw new IllegalArgumentException("Selected glTF scene references a node twice");
        visited[id]=true;
        int outputIndex=parents.size();
        JsonObject node=original.get(id);
        parents.add(parent);
        transforms.add(localTransform(node));
        int mesh=optionalInteger(node,"mesh",-1);
        if(mesh>=primitiveRefs.size())
            throw new IllegalArgumentException("glTF node mesh is out of range");
        if(mesh< -1)
            throw new IllegalArgumentException("glTF node mesh index must be nonnegative");
        nodes.add(mesh<0?List.of():primitiveRefs.get(mesh));
        for(int child:edges.get(id))
            appendNode(child,outputIndex,original,edges,primitiveRefs,
                    visited,parents,transforms,nodes);
    }
}

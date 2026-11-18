package org.owgraphics.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GltfModelParserTest {
    private static String fixture() {
        ByteBuffer data = ByteBuffer.allocate(102).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : new float[]{-0.5f,-0.5f,0, 0.5f,-0.5f,0, 0,0.5f,0})
            data.putFloat(f);
        for (float f : new float[]{0,0,1, 0,0,1, 0,0,1})
            data.putFloat(f);
        for (float f : new float[]{0,0, 1,0, 0.5f,1})
            data.putFloat(f);
        for (int i : new int[]{0,1,2})
            data.putShort((short)i);
        if (data.position() != 102) throw new AssertionError("bad fixture size");
        String base64=Base64.getEncoder().encodeToString(data.array());
        return """
                {"asset":{"version":"2.0"},
                 "buffers":[{"byteLength":102,"uri":"data:application/octet-stream;base64,%s"}],
                 "bufferViews":[
                   {"buffer":0,"byteOffset":0,"byteLength":36},
                   {"buffer":0,"byteOffset":36,"byteLength":36},
                   {"buffer":0,"byteOffset":72,"byteLength":24},
                   {"buffer":0,"byteOffset":96,"byteLength":6}
                 ],
                 "accessors":[
                   {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                   {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},
                   {"bufferView":2,"componentType":5126,"count":3,"type":"VEC2"},
                   {"bufferView":3,"componentType":5123,"count":3,"type":"SCALAR"}
                 ],
                 "materials":[{"pbrMetallicRoughness":{
                   "baseColorFactor":[0.8,0.6,0.4,1.0],"metallicFactor":0.65,"roughnessFactor":0.35}}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"indices":3,"material":0}]}]
                }
                """.formatted(base64);
    }

    @Test public void decodesEmbeddedGltfAndPbrFactors() {
        var model=GltfModelParser.parse(fixture());
        assertEquals(7,model.size());
        assertEquals(9,((List<?>)model.get(0)).size());
        assertEquals(6,((List<?>)model.get(1)).size());
        assertEquals(9,((List<?>)model.get(2)).size());
        assertEquals(List.of(0,1,2),model.get(3));
        assertEquals(0.8, (Double)((List<?>)model.get(4)).get(0),0.00001);
        assertEquals(0.65,(Double)model.get(5),0.00001);
        assertEquals(0.35,(Double)model.get(6),0.00001);
        var mesh=GraphicsArguments.litGeometry(model.get(0),model.get(1),model.get(2),model.get(3));
        assertEquals(9,mesh.normals().length);
    }

    @Test public void generatesMissingNormals() {
        String noNormals=fixture().replace("\"NORMAL\":1,","");
        var model=GltfModelParser.parse(noNormals);
        assertEquals(1.0,(Double)((List<?>)model.get(2)).get(2),0.0001);
    }

    @Test public void refusesUnsupportedModesInvalidBuffersAndMaterials() {
        String src=fixture();
        assertThrows(IllegalArgumentException.class,()->GltfModelParser.parse(
                src.replace("\"version\":\"2.0\"","\"version\":\"1.0\"")));
        assertThrows(IllegalArgumentException.class,()->GltfModelParser.parse(
                src.replace("data:application/octet-stream;base64,","https://example.com/model.bin")));
        assertThrows(IllegalArgumentException.class,()->GltfModelParser.parse(
                src.replace("\"material\":0","\"mode\":5,\"material\":0")));
        assertThrows(IllegalArgumentException.class,()->GltfModelParser.parse(
                src.replace("\"byteOffset\":96","\"byteOffset\":999999")));
        assertThrows(IllegalArgumentException.class,()->GltfModelParser.parse(
                src.replace("\"metallicFactor\":0.65","\"metallicFactor\":2.0")));
        assertThrows(IllegalArgumentException.class,()->GltfModelParser.parse(
                src.replace("\"indices\":3","\"indices\":100")));
        assertThrows(IllegalArgumentException.class,()->GltfModelParser.parse(
                src.replace("\"meshes\"","\"extensionsRequired\":[\"KHR_fake\"],\"meshes\"")));
    }

    @Test public void enforcesProjectSandbox() throws Exception {
        Path root=Files.createTempDirectory("ow-gltf");
        Path file=root.resolve("triangle.gltf");
        Path out=Files.createTempFile("gltf-outside",".gltf");
        try {
            Files.writeString(file,fixture(),StandardCharsets.UTF_8);
            Files.writeString(out,fixture(),StandardCharsets.UTF_8);
            assertEquals(3,((List<?>)GltfModelParser.loadRelative("triangle.gltf",root).get(3)).size());
            assertThrows(IllegalArgumentException.class,()->GltfModelParser.loadRelative("../triangle.gltf",root));
            assertThrows(IllegalArgumentException.class,()->GltfModelParser.loadRelative(out.toString(),root));
            assertThrows(IllegalArgumentException.class,()->GltfModelParser.loadRelative("triangle.glb",root));
            Path link=root.resolve("link.gltf");
            try {
                Files.createSymbolicLink(link,out);
                assertThrows(IllegalArgumentException.class,()->GltfModelParser.loadRelative("link.gltf",root));
            } catch (UnsupportedOperationException|java.nio.file.FileSystemException ignored) {
                // Some Windows test configurations do not allow symlinks.
            }
        } finally {
            Files.deleteIfExists(root.resolve("link.gltf"));
            Files.deleteIfExists(file);
            Files.deleteIfExists(out);
            Files.deleteIfExists(root);
        }
    }

    @Test public void validatesInstanceMatrices() {
        var identity=List.of(1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,
                0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0);
        assertEquals(32,GraphicsArguments.instanceMatrices(List.of(identity,identity)).length);
        assertThrows(IllegalArgumentException.class,()->GraphicsArguments.instanceMatrices(List.of()));
        assertThrows(IllegalArgumentException.class,()->GraphicsArguments.instanceMatrices(List.of(List.of(1.0))));
        var invalid=new java.util.ArrayList<>(identity);
        invalid.set(3,Double.NaN);
        assertThrows(IllegalArgumentException.class,()->GraphicsArguments.instanceMatrices(List.of(invalid)));
    }
}

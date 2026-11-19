package org.owgraphics.runtime;

import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Tests GLB binary framing, nested nodes, primitive grouping, path safety. */
public final class GltfSceneParserTest {
    private static Path projectRoot() {
        Path current=Path.of("").toAbsolutePath();
        for (Path candidate:List.of(current,current.resolve("ow-graphics"),
                current.getParent(),current.resolve("..").resolve("ow-graphics"))) {
            if(candidate != null && Files.exists(candidate.resolve(
                    "examples/gltf_scene/assets/multi_scene.glb")))
                return candidate.normalize();
        }
        throw new AssertionError("Cannot find checked-in glTF fixture");
    }

    private static Path glb() {
        return projectRoot().resolve("examples/gltf_scene/assets/multi_scene.glb");
    }
    private static String json() throws Exception {
        return Files.readString(projectRoot().resolve(
                "examples/gltf_scene/assets/multi_scene.gltf"));
    }
    private static List<?> at(Object data,int i) {
        return (List<?>)((List<?>)data).get(i);
    }

    @Test public void parsesGlbWithMultiPrimitiveMeshesAndParentFirstOrder() throws Exception {
        List<Object> result=GltfSceneParser.parseGlb(Files.readAllBytes(glb()));
        assertEquals(4,result.size());
        assertEquals(3,at(result,0).size());
        assertEquals(List.of(-1,0,0,-1),result.get(1));
        List<?> perNode=at(result,3);
        assertEquals(List.of(),perNode.get(0));
        assertEquals(List.of(2),perNode.get(1));
        assertEquals(List.of(0,1),perNode.get(2));
        assertEquals(List.of(2),perNode.get(3));
        List<?> transforms=at(result,2);
        assertEquals(-0.65,(Double)((List<?>)transforms.get(0)).get(12),0.00001);
        assertEquals(1.3,(Double)((List<?>)transforms.get(1)).get(12),0.00001);
        assertEquals(0.0,(Double)((List<?>)transforms.get(2)).get(12),0.00001);
        assertEquals(-0.9,(Double)((List<?>)transforms.get(3)).get(13),0.00001);
        assertEquals(0.85,(Double)at(at(result,0).get(0),4).get(0),0.00001);
        assertEquals(0.0,(Double)at(result,0).stream().map(x -> x).toList()
                .stream().skip(1).findFirst().map(x -> ((List<?>)x).get(5)).get(),0.00001);
        assertEquals(3,at(result,0).size());
        for(Object primitive:at(result,0)) {
            var a=(List<?>)primitive;
            var mesh=GraphicsArguments.litGeometry(a.get(0),a.get(1),a.get(2),a.get(3));
            assertEquals(6,mesh.base().indices().length);
        }
    }

    @Test public void jsonAndGlbHaveIdenticalSceneContracts() throws Exception {
        var fromJson=GltfSceneParser.parseJson(json());
        var fromGlb=GltfSceneParser.loadRelative(
                "examples/gltf_scene/assets/multi_scene.glb",projectRoot());
        assertEquals(fromJson,fromGlb);
    }

    @Test public void rejectsInvalidContainerHeadersAndChunks() throws Exception {
        byte[] bytes=Files.readAllBytes(glb());
        byte[] wrongMagic=bytes.clone();wrongMagic[0]='x';
        assertThrows(IllegalArgumentException.class,()->GltfSceneParser.parseGlb(wrongMagic));
        byte[] badLength=bytes.clone();
        ByteBuffer.wrap(badLength).order(ByteOrder.LITTLE_ENDIAN).putInt(8,bytes.length+4);
        assertThrows(IllegalArgumentException.class,()->GltfSceneParser.parseGlb(badLength));
        byte[] wrongJsonType=bytes.clone();wrongJsonType[16]=0;
        assertThrows(IllegalArgumentException.class,()->GltfSceneParser.parseGlb(wrongJsonType));
        byte[] invalidBinLength=bytes.clone();
        int binHeader=20+ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
        ByteBuffer.wrap(invalidBinLength).order(ByteOrder.LITTLE_ENDIAN).putInt(binHeader,8);
        assertThrows(IllegalArgumentException.class,()->GltfSceneParser.parseGlb(invalidBinLength));
        assertThrows(IllegalArgumentException.class,()->GltfSceneParser.parseGlb(
                Arrays.copyOf(bytes,bytes.length-1)));
    }

    @Test public void rejectsCyclesAndBadNodeReferences() throws Exception {
        var base=JsonParser.parseString(json()).getAsJsonObject();
        base.getAsJsonArray("nodes").get(1).getAsJsonObject()
                .add("children",JsonParser.parseString("[0]").getAsJsonArray());
        assertThrows(IllegalArgumentException.class,()->GltfSceneParser.parseJson(base.toString()));
        var out=JsonParser.parseString(json()).getAsJsonObject();
        out.getAsJsonArray("nodes").get(2).getAsJsonObject().addProperty("mesh",99);
        assertThrows(IllegalArgumentException.class,()->GltfSceneParser.parseJson(out.toString()));
        var badRoots=JsonParser.parseString(json()).getAsJsonObject();
        badRoots.getAsJsonArray("scenes").get(0).getAsJsonObject()
                .add("nodes",JsonParser.parseString("[1]").getAsJsonArray());
        assertThrows(IllegalArgumentException.class,()->GltfSceneParser.parseJson(badRoots.toString()));
    }

    @Test public void parsesQuaternionAndRejectsInvalidTRS() throws Exception {
        var asset=JsonParser.parseString(json()).getAsJsonObject();
        var node=asset.getAsJsonArray("nodes").get(1).getAsJsonObject();
        node.add("rotation",JsonParser.parseString("[0,0,0.70710678118,0.70710678118]").getAsJsonArray());
        List<Object> parsed=GltfSceneParser.parseJson(asset.toString());
        List<?> locals=at(parsed,2);
        List<?> rotate=(List<?>)locals.get(2); // DFS reorders node 1 to index 2
        assertEquals(0.0,(Double)rotate.get(0),0.000001);
        assertEquals(1.0,(Double)rotate.get(1),0.000001);
        node.add("matrix",JsonParser.parseString(
                "[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]").getAsJsonArray());
        assertThrows(IllegalArgumentException.class,()->GltfSceneParser.parseJson(asset.toString()));
        node.remove("matrix");
        node.add("rotation",JsonParser.parseString("[0,0,0,0]").getAsJsonArray());
        assertThrows(IllegalArgumentException.class,()->GltfSceneParser.parseJson(asset.toString()));
    }

    @Test public void rejectsEscapingAndUnsupportedFormats() throws Exception {
        Path root=projectRoot();
        assertThrows(IllegalArgumentException.class,
                ()->GltfSceneParser.loadRelative("../elsewhere.glb",root));
        assertThrows(IllegalArgumentException.class,
                ()->GltfSceneParser.loadRelative(glb().toString(),root));
        assertThrows(IllegalArgumentException.class,
                ()->GltfSceneParser.loadRelative("examples/gltf_scene/assets/else.txt",root));
        var unsupported=JsonParser.parseString(json()).getAsJsonObject();
        unsupported.add("skins",JsonParser.parseString("[{}]").getAsJsonArray());
        assertThrows(IllegalArgumentException.class,
                ()->GltfSceneParser.parseJson(unsupported.toString()));
        unsupported.remove("skins");
        unsupported.add("animations",JsonParser.parseString("[{}]").getAsJsonArray());
        assertThrows(IllegalArgumentException.class,
                ()->GltfSceneParser.parseJson(unsupported.toString()));
    }
}

package org.owgraphics.runtime;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Animation data, CPU pose sampling and strict rig validation without GLFW. */
public final class GltfSkinParserTest {
    private static Path projectRoot() {
        Path cwd=Path.of("").toAbsolutePath();
        for(Path folder:List.of(cwd,cwd.resolve("ow-graphics"),
                cwd.getParent(),cwd.resolve("..").resolve("ow-graphics"))) {
            if(folder!=null && Files.exists(folder.resolve("examples/gltf_skinning/assets/wave.glb")))
                return folder.normalize();
        }
        throw new AssertionError("Original animated glTF fixture unavailable");
    }
    private static List<Object> load(String suffix) {
        return GltfSkinParser.loadRelative(
                "examples/gltf_skinning/assets/wave."+suffix,projectRoot());
    }
    private static List<?> matrix(List<Object> rig,double seconds,int joint) {
        return (List<?>)GltfSkinParser.sample(rig,seconds).get(joint);
    }
    private static double at(List<?> matrix,int i) {
        return ((Number)matrix.get(i)).doubleValue();
    }

    @Test public void glbAndJsonContainIdenticalSkinsAndAnimations() {
        var glb=load("glb");
        var json=load("gltf");
        assertEquals(json,glb);
        assertEquals(14,glb.size());
        assertEquals(12,((List<?>)glb.get(0)).size());
        assertEquals(16,((List<?>)glb.get(4)).size());
        assertEquals(16,((List<?>)glb.get(5)).size());
        assertEquals(List.of(0,1),glb.get(10));
        assertEquals(3,((List<?>)glb.get(12)).size());
        assertEquals(2,GltfSkinParser.sample(glb,0.5).size());
    }

    @Test public void sampleTranslationRotationSlerpAndStepScale() {
        var rig=load("glb");
        var zero=matrix(rig,0.0,1);
        assertEquals(1,at(zero,0),1e-5);
        assertEquals(0,at(zero,13),1e-5);
        var half=matrix(rig,0.5,1);
        assertEquals(0.275,at(half,13),1e-5);
        assertEquals(Math.cos(Math.PI/12),at(half,0),1e-5);
        assertEquals(Math.sin(Math.PI/12),at(half,1),1e-5);
        var finalPose=matrix(rig,1.0,1);
        assertEquals(0.55,at(finalPose,13),1e-5);
        assertEquals(Math.cos(Math.PI/6)*1.1,at(finalPose,0),1e-5);
        assertEquals(Math.sin(Math.PI/6)*1.1,at(finalPose,1),1e-5);
        assertEquals(matrix(rig,-10,1),zero);
        assertEquals(matrix(rig,10,1),finalPose);
        assertEquals(1.0,at(matrix(rig,0.5,0),0),1e-5);
    }

    @Test public void rejectsUnsupportedChannelsAndNodeCycles() throws Exception {
        var original=JsonParser.parseString(Files.readString(
                projectRoot().resolve("examples/gltf_skinning/assets/wave.gltf"))).getAsJsonObject();
        var unsupported=original.deepCopy();
        var sample=unsupported.getAsJsonArray("animations").get(0).getAsJsonObject()
                .getAsJsonArray("samplers").get(0).getAsJsonObject();
        sample.addProperty("interpolation","CUBICSPLINE");
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.parseJson(unsupported.toString()));
        var missingJoint=original.deepCopy();
        missingJoint.getAsJsonArray("skins").get(0).getAsJsonObject()
                .add("joints",JsonParser.parseString("[0,99]").getAsJsonArray());
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.parseJson(missingJoint.toString()));
        var cycle=original.deepCopy();
        cycle.getAsJsonArray("nodes").get(1).getAsJsonObject()
                .add("children",JsonParser.parseString("[0]").getAsJsonArray());
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.parseJson(cycle.toString()));
        var anotherSkin=original.deepCopy();
        anotherSkin.getAsJsonArray("skins").add(anotherSkin.getAsJsonArray("skins").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.parseJson(anotherSkin.toString()));
    }

    @Test public void rejectsUnsafePathsAndTamperedRig() {
        Path root=projectRoot();
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.loadRelative("../secret.glb",root));
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.loadRelative(root.resolve("examples/gltf_skinning/assets/wave.glb").toString(),root));
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.loadRelative("examples/gltf_skinning/assets/wave.png",root));
        var rig=load("gltf");
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.sample(rig,Double.NaN));
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.sample(List.of(),0.5));
        var tampered=new ArrayList<Object>(rig);
        tampered.set(13,1000);
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.sample(tampered,0.5));
    }

    @Test public void validatesSkinnedGeometryAndUniformPalette() {
        var rig=load("gltf");
        var mesh=GraphicsArguments.skinnedGeometry(
                rig.get(0),rig.get(1),rig.get(2),rig.get(3),rig.get(4),rig.get(5));
        assertEquals(16,mesh.joints().length);
        assertEquals(16,mesh.weights().length);
        var palette=GltfSkinParser.sample(rig,0.5);
        assertEquals(32,GraphicsArguments.jointMatrices(palette).length);
        var invalid=new ArrayList<Object>(rig);
        invalid.set(4,List.of(99,0,0,0,99,0,0,0,99,0,0,0,99,0,0,0));
        assertThrows(IllegalArgumentException.class,
                ()->GraphicsArguments.skinnedGeometry(
                    invalid.get(0),invalid.get(1),invalid.get(2),
                    invalid.get(3),invalid.get(4),invalid.get(5)));
        assertThrows(IllegalArgumentException.class,
                ()->GraphicsArguments.jointMatrices(List.of()));
    }
}

package org.owgraphics.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** CPU-only tests for additive skeletal layers, markers and extracted root motion. */
public final class GltfAnimationControllerTest {
    private static final String NAME="examples/animation_controller/assets/three_clips.";
    private static Path root() {
        Path cwd=Path.of("").toAbsolutePath();
        for(Path folder:List.of(cwd,cwd.resolve("ow-graphics"),
                cwd.getParent(),cwd.resolve("..").resolve("ow-graphics")))
            if(folder!=null && Files.exists(folder.resolve(NAME+"glb")))return folder;
        throw new AssertionError("Original three-clip animation controller fixture missing");
    }
    private static List<Object> library(String extension) {
        return GltfSkinParser.loadLibraryRelative(NAME+extension,root());
    }
    private static double at(List<Object> pose,int joint,int component) {
        return ((Number)((List<?>)pose.get(joint)).get(component)).doubleValue();
    }

    @Test public void loadsThreeDistinctClipsFromJsonAndBinary() {
        var glb=library("glb");
        assertEquals(glb,library("gltf"));
        assertEquals(List.of("Wave","Bounce","Walk"),glb.get(2));
        assertEquals(3,((List<?>)glb.get(1)).size());
        assertEquals(1,GltfSkinParser.clipDuration(glb,2),0);
        assertEquals(0.5,at(GltfSkinParser.sampleClip(glb,2,0.5),0,12),1e-6);
        assertEquals(0.5,at(GltfSkinParser.sampleClip(glb,2,0.5),1,12),1e-6);
    }

    @Test public void additiveIsMaskedAndAppliedBeforeBonePaletteComputation() {
        var lib=library("glb");
        var base=GltfSkinParser.sampleClip(lib,0,0.5);
        var layer=GltfSkinParser.sampleClip(lib,1,0.5);
        assertEquals(0.275,at(base,1,13),1e-5);
        assertEquals(-0.35,at(layer,1,13),1e-5);
        assertEquals(base,GltfSkinParser.additiveClips(lib,0,0.5,1,0.5,0,0,List.of(1)));
        assertEquals(base,GltfSkinParser.additiveClips(lib,0,0.5,1,0.5,0,1,List.of()));
        assertEquals(base,GltfSkinParser.additiveClips(lib,0,0.5,1,0.5,0,1,List.of(0)));
        var mixed=GltfSkinParser.additiveClips(lib,0,0.5,1,0.5,0,0.5,List.of(1));
        assertEquals(0.100,at(mixed,1,13),0.0001);
        assertEquals(1,at(mixed,0,0),0.0001);
        double x=at(mixed,1,0),y=at(mixed,1,1);
        assertEquals(1,Math.sqrt(x*x+y*y),1e-4);
        assertTrue("weighted quaternion must rotate",y<at(base,1,1));
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.additiveClips(lib,0,0.5,1,0.5,0,1.1,List.of(1)));
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.additiveClips(lib,0,0.5,1,0.5,0,0.5,List.of(1,1)));
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.additiveClips(lib,0,0.5,1,0.5,0,0.5,List.of(20)));
    }

    @Test public void extractsRootLocalTranslationAcrossLoopsWithoutMutatingPose() {
        var lib=library("gltf");
        assertEquals(List.of(0.5,0.0,0.0),GltfSkinParser.rootDelta(lib,2,0,0.25,0.75));
        assertEquals(0.5,GltfSkinParser.rootDelta(lib,2,0,0.75,1.25).get(0),1e-7);
        assertEquals(2.0,GltfSkinParser.rootDelta(lib,2,0,0.5,2.5).get(0),1e-7);
        assertEquals(0.0,GltfSkinParser.rootDelta(lib,0,0,0.5,2.5).get(0),1e-7);
        assertEquals(0.0,GltfSkinParser.rootDelta(lib,2,0,1.25,1.25).get(0),1e-7);
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.rootDelta(lib,2,0,2.0,1.0));
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.rootDelta(lib,2,99,0,1));
    }

    @Test public void collectsAuthoredEventsAcrossLoopBoundariesExactlyOnce() {
        var lib=library("glb");
        List<Object> markers=List.of(List.of(2,0.25,"step-left"),
                List.of(2,0.75,"step-right"),List.of(0,0.5,"wave-apex"));
        var single=GltfSkinParser.eventsBetween(lib,2,markers,0.2,0.8);
        assertEquals(2,single.size());
        assertEquals(List.of("step-left",0.25,0.25),single.get(0));
        assertEquals(List.of("step-right",0.75,0.75),single.get(1));
        var across=GltfSkinParser.eventsBetween(lib,2,markers,0.8,1.3);
        assertEquals(List.of(List.of("step-left",1.25,0.25)),across);
        assertEquals(List.of(),GltfSkinParser.eventsBetween(lib,2,markers,0.5,0.5));
        assertEquals(1,GltfSkinParser.eventsBetween(lib,0,markers,0.3,0.6).size());
        assertThrows(IllegalArgumentException.class,()->GltfSkinParser.eventsBetween(
                lib,2,List.of(List.of(2,1.5,"too-late")),0,1));
        assertThrows(IllegalArgumentException.class,()->GltfSkinParser.eventsBetween(
                lib,2,markers,1.2,0.4));
        assertThrows(IllegalArgumentException.class,()->GltfSkinParser.eventsBetween(
                lib,2,markers,0,200.0));
    }
}

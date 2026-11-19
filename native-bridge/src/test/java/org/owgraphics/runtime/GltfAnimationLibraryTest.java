package org.owgraphics.runtime;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GltfAnimationLibraryTest {
    private static final String FILE="examples/animation_library/assets/wave_bounce.";
    private static Path root() {
        Path cwd=Path.of("").toAbsolutePath();
        for(Path folder:List.of(cwd,cwd.resolve("ow-graphics"),cwd.getParent(),
                cwd.resolve("..").resolve("ow-graphics"))) {
            if(folder!=null&&Files.exists(folder.resolve(FILE+"glb")))return folder;
        }
        throw new AssertionError("Two-clip original fixture is missing");
    }
    private static List<Object> library(String extension) {
        return GltfSkinParser.loadLibraryRelative(FILE+extension,root());
    }
    private static double at(List<Object> palette,int joint,int component) {
        return ((Number)((List<?>)palette.get(joint)).get(component)).doubleValue();
    }

    @Test public void parsesSameGlbAndJsonLibraryAndKeepsLegacy14FieldRig() {
        var glb=library("glb");
        var json=library("gltf");
        assertEquals(json,glb);
        assertEquals(4,glb.size());
        assertEquals(14,((List<?>)glb.get(0)).size());
        assertEquals(2,((List<?>)glb.get(1)).size());
        assertEquals(List.of("Wave","Bounce"),glb.get(2));
        assertEquals(List.of(1.0,1.0),glb.get(3));
        var original=GltfSkinParser.loadRelative(FILE+"glb",root());
        assertEquals(glb.get(0),original);
        assertEquals(3,((List<?>)original.get(12)).size());
    }

    @Test public void samplesCubicHermiteAndNormalizedQuaternion() {
        var lib=library("gltf");
        double wave=at(GltfSkinParser.sampleClip(lib,0,0.5),1,13);
        double cubic=at(GltfSkinParser.sampleClip(lib,1,0.5),1,13);
        assertEquals(0.275,wave,0.0001);
        // Hermite with out-tangent -1.2, in-tangent zero: -0.2 -0.15.
        assertEquals(-0.35,cubic,0.0001);
        assertNotEquals(-0.2,cubic,0.0001); // not simple linear interpolation
        var first=GltfSkinParser.sampleClip(lib,1,0);
        var last=GltfSkinParser.sampleClip(lib,1,1);
        assertEquals(0,at(first,1,13),0.0001);
        assertEquals(-0.4,at(last,1,13),0.0001);
        assertEquals(Math.cos(Math.PI/4),at(last,1,0),0.0001);
        assertEquals(-Math.sin(Math.PI/4),at(last,1,1),0.0001);
        var midway=GltfSkinParser.sampleClip(lib,1,0.5);
        double x=at(midway,1,0),y=at(midway,1,1);
        assertEquals(1.0,Math.sqrt(x*x+y*y),0.0001);
        assertTrue(y<0 && x>0);
    }

    @Test public void crossfadeMixesLocalTrsAndNormalizesRotation() {
        var lib=library("glb");
        var from=GltfSkinParser.sampleClip(lib,0,0.5);
        var to=GltfSkinParser.sampleClip(lib,1,0.5);
        assertEquals(from,GltfSkinParser.blendClips(lib,0,0.5,1,0.5,0));
        assertEquals(to,GltfSkinParser.blendClips(lib,0,0.5,1,0.5,1));
        var blended=GltfSkinParser.blendClips(lib,0,0.5,1,0.5,0.5);
        assertEquals(-0.0375,at(blended,1,13),0.0001);
        double a=at(blended,1,0),b=at(blended,1,1);
        assertEquals(1.0,Math.sqrt(a*a+b*b),0.0001);
        assertTrue(a>0.9);
        assertEquals(1.0,at(blended,0,0),0.0001); // root remains fixed
    }

    @Test public void clockWrapAndValidatesBounds() {
        var lib=library("glb");
        assertEquals(1.0,GltfSkinParser.clipDuration(lib,1),0);
        assertEquals(0.25,GltfSkinParser.loopTime(lib,0,2.25),1e-8);
        assertEquals(0.75,GltfSkinParser.loopTime(lib,1,-0.25),1e-8);
        assertEquals(0,GltfSkinParser.loopTime(lib,1,1.0),1e-8);
        assertThrows(IllegalArgumentException.class,()->GltfSkinParser.sampleClip(lib,2,0));
        assertThrows(IllegalArgumentException.class,()->GltfSkinParser.clipDuration(lib,-1));
        assertThrows(IllegalArgumentException.class,()->GltfSkinParser.loopTime(lib,0,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->GltfSkinParser.blendClips(lib,0,0,1,0,-0.1));
        assertThrows(IllegalArgumentException.class,()->GltfSkinParser.blendClips(lib,0,0,1,0,1.1));
        assertThrows(IllegalArgumentException.class,()->GltfSkinParser.blendClips(lib,0,0,99,0,1));
    }

    @Test public void rejectsMalformedCubicAccessorsDuplicatePathsAndExcessClips() throws Exception {
        var original=JsonParser.parseString(Files.readString(root().resolve(FILE+"gltf"))).getAsJsonObject();
        var count=original.deepCopy();
        count.getAsJsonArray("accessors").get(11).getAsJsonObject().addProperty("count",5);
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.parseLibraryJson(count.toString()));
        var duplicate=original.deepCopy();
        var extra=duplicate.getAsJsonArray("animations").get(1).getAsJsonObject();
        extra.getAsJsonArray("channels").add(extra.getAsJsonArray("channels").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.parseLibraryJson(duplicate.toString()));
        var invalid=original.deepCopy();
        invalid.getAsJsonArray("animations").get(1).getAsJsonObject()
                .getAsJsonArray("samplers").get(0).getAsJsonObject()
                .addProperty("interpolation","CUBIC_HERMITE_WRONG");
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.parseLibraryJson(invalid.toString()));
        var excessive=original.deepCopy();
        for(int i=2;i<=16;i++)
            excessive.getAsJsonArray("animations").add(
                    original.getAsJsonArray("animations").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class,
                ()->GltfSkinParser.parseLibraryJson(excessive.toString()));
    }

    @Test public void legacySingleClipDoesNotChange() {
        var rig=GltfSkinParser.loadRelative(
                "examples/gltf_skinning/assets/wave.gltf",root());
        assertEquals(14,rig.size());
        assertEquals(0.275,at(GltfSkinParser.sample(rig,0.5),1,13),0.0001);
    }
}

package org.owgraphics.runtime;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33.*;

/**
 * Actual Mesa/OpenGL proof: draw three independently colored/animated actors
 * from ONE shared skinned GPU mesh with three model matrices and joint palettes.
 * Moving one actor does not change the other two framebuffer positions.
 */
public final class AnimationCrowdGpuSmoke {
    private AnimationCrowdGpuSmoke() {}
    private static int op(GraphicsRuntimeExtension gpu,String command,Object... args) {
        return (int)gpu.invoke(command,List.of(args));
    }
    private static void ensure(boolean pass,String description) {
        if(!pass)throw new AssertionError(description);
    }
    private static Path root() {
        Path cwd=Path.of("").toAbsolutePath();
        for(Path dir:List.of(cwd,cwd.resolve("ow-graphics"),cwd.getParent(),
                cwd.resolve("..").resolve("ow-graphics")))
            if(dir!=null&&Files.exists(dir.resolve("examples/animation_controller/assets/three_clips.glb")))
                return dir;
        throw new AssertionError("Phase 11 animated GLB asset missing");
    }
    private static int[] pixel(int x,int y) {
        ByteBuffer rgba=BufferUtils.createByteBuffer(4);
        glReadBuffer(GL_BACK);
        glReadPixels(x,y,1,1,GL_RGBA,GL_UNSIGNED_BYTE,rgba);
        return new int[]{rgba.get(0)&255,rgba.get(1)&255,rgba.get(2)&255};
    }
    private static List<Double> translation(double x) {
        return List.of(1.0,0.0,0.0,0.0,
                       0.0,1.0,0.0,0.0,
                       0.0,0.0,1.0,0.0,
                       x,0.0,0.0,1.0);
    }
    private static void draw(GraphicsRuntimeExtension gpu,int shader,int mesh,
                             List<Object> skin,double x,double r,double g,double b) {
        ensure(op(gpu,"graphics.shader.uniform_mat4",shader,"uModel",translation(x))==1,
                "per-actor model matrix");
        ensure(op(gpu,"graphics.shader.uniform_mat4_array",shader,"uBones",skin)==1,
                "per-actor joint palette");
        ensure(op(gpu,"graphics.shader.uniform4",shader,"uTint",r,g,b,1.0)==1,
                "per-actor tint");
        ensure(op(gpu,"graphics.mesh.draw",mesh)==1,"shared GPU mesh draw");
    }

    public static void main(String[] args) {
        var library=GltfSkinParser.loadLibraryRelative(
                "examples/animation_controller/assets/three_clips.glb",root());
        List<?> rig=(List<?>)library.get(0);
        var wave=GltfSkinParser.sampleClip(library,0,0.25);
        var bounce=GltfSkinParser.sampleClip(library,1,0.5);
        var walk=GltfSkinParser.sampleClip(library,2,0);
        try(var gpu=new GraphicsRuntimeExtension()) {
            op(gpu,"graphics.window.open",320,240,"OW 3 Actor Render","opengl");
            int shader=op(gpu,"graphics.shader.create",
                    "#version 330 core\nlayout(location=0) in vec3 aPos;\n"
                    +"layout(location=7) in vec4 joints;\n"
                    +"layout(location=8) in vec4 weights;\n"
                    +"uniform mat4 uBones[32];\nuniform mat4 uModel;\n"
                    +"void main(){vec4 p=vec4(aPos*0.35,1.0);"
                    +"mat4 skin=weights.x*uBones[int(joints.x)]"
                    +"+weights.y*uBones[int(joints.y)]"
                    +"+weights.z*uBones[int(joints.z)]"
                    +"+weights.w*uBones[int(joints.w)];"
                    +"gl_Position=uModel*skin*p;}",
                    "#version 330 core\nout vec4 pixel;\nuniform vec4 uTint;\n"
                    +"void main(){pixel=uTint;}");
            int mesh=op(gpu,"graphics.mesh.create_skinned_indexed",
                    rig.get(0),rig.get(1),rig.get(2),rig.get(3),rig.get(4),rig.get(5));
            ensure(shader>0&&mesh>0,"one shader and one skinned geometry resource");
            ensure(op(gpu,"graphics.shader.bind",shader)==1,"shader bound");
            op(gpu,"graphics.opengl.clear",5,8,12);
            draw(gpu,shader,mesh,wave,-0.7,0.98,0.12,0.11);
            draw(gpu,shader,mesh,bounce,0.0,0.12,0.98,0.12);
            draw(gpu,shader,mesh,walk,0.7,0.12,0.28,0.98);
            glFinish();
            int[] left=pixel(48,106),middle=pixel(160,106),right=pixel(272,106);
            ensure(left[0]>200&&left[1]<80&&left[2]<80,
                    "left actor must be red: "+java.util.Arrays.toString(left));
            ensure(middle[1]>200&&middle[0]<80&&middle[2]<80,
                    "middle actor must be green: "+java.util.Arrays.toString(middle));
            ensure(right[2]>200&&right[0]<80&&right[1]<100,
                    "right actor must be blue: "+java.util.Arrays.toString(right));
            // Move ONLY the middle actor. The first and third model matrices
            // remain unchanged and must still occupy their original pixels.
            op(gpu,"graphics.opengl.clear",5,8,12);
            draw(gpu,shader,mesh,wave,-0.7,0.98,0.12,0.11);
            draw(gpu,shader,mesh,bounce,0.35,0.12,0.98,0.12);
            draw(gpu,shader,mesh,walk,0.7,0.12,0.28,0.98);
            glFinish();
            int[] emptied=pixel(160,106),moved=pixel(216,106);
            int[] leftAfter=pixel(48,106),rightAfter=pixel(272,106);
            ensure(emptied[0]<30&&emptied[1]<30&&emptied[2]<30,
                    "middle actor old pixel must be empty");
            ensure(moved[1]>200&&moved[0]<80,
                    "middle actor appears in updated world location");
            ensure(leftAfter[0]>200&&rightAfter[2]>200,
                    "independent actor positions remain unchanged");
            ensure(glGetError()==GL_NO_ERROR,"no graphics errors");
            ensure(op(gpu,"graphics.mesh.destroy",mesh)==1,"shared mesh destroyed once");
            ensure(op(gpu,"graphics.shader.destroy",shader)==1,"shader destroyed");
        }
        System.out.println("PASS: three independent animated actors from one GPU mesh with per-actor model/skin");
    }
}

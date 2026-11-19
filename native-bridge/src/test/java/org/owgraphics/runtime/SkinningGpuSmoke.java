package org.owgraphics.runtime;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33.*;

/**
 * Tests actual GPU linear blend skinning: one vertex belongs to the animated
 * tip joint, one belongs to the root joint. A moving pixel changes from black
 * to red between two sampled poses while the root half stays stationary.
 */
public final class SkinningGpuSmoke {
    private static int op(GraphicsRuntimeExtension gpu,String name,Object... args) {
        return (int)gpu.invoke(name,List.of(args));
    }
    private static int[] pixel(int x,int y) {
        ByteBuffer rgba=BufferUtils.createByteBuffer(4);
        glReadBuffer(GL_BACK);
        glReadPixels(x,y,1,1,GL_RGBA,GL_UNSIGNED_BYTE,rgba);
        return new int[]{rgba.get(0)&255,rgba.get(1)&255,rgba.get(2)&255};
    }
    private static void check(boolean ok,String name) {
        if(!ok)throw new AssertionError(name);
    }
    private static Path root() {
        Path cwd=Path.of("").toAbsolutePath();
        for(Path path:List.of(cwd,cwd.resolve("ow-graphics"),cwd.getParent(),
                cwd.resolve("..").resolve("ow-graphics")))
            if(path!=null && Files.exists(path.resolve("examples/gltf_skinning/assets/wave.glb")))
                return path;
        throw new AssertionError("Animated GLB fixture missing");
    }
    public static void main(String[] args) {
        var rig=GltfSkinParser.loadRelative(
                "examples/gltf_skinning/assets/wave.glb",root());
        try(var gpu=new GraphicsRuntimeExtension()) {
            op(gpu,"graphics.window.open",320,240,"OW GPU skeleton","opengl");
            int shader=op(gpu,"graphics.shader.create",
                    "#version 330 core\n"
                    +"layout(location=0) in vec3 position;\n"
                    +"layout(location=7) in vec4 joints;\n"
                    +"layout(location=8) in vec4 weights;\n"
                    +"uniform mat4 uBones[32];\n"
                    +"void main(){vec4 p=vec4(position,1.0);"
                    +"gl_Position=weights.x*(uBones[int(joints.x)]*p)"
                    +"+weights.y*(uBones[int(joints.y)]*p)"
                    +"+weights.z*(uBones[int(joints.z)]*p)"
                    +"+weights.w*(uBones[int(joints.w)]*p);}",
                    "#version 330 core\nout vec4 color;\n"
                    +"void main(){color=vec4(0.95,0.1,0.1,1.0);}");
            int mesh=op(gpu,"graphics.mesh.create_skinned_indexed",
                    rig.get(0),rig.get(1),rig.get(2),rig.get(3),rig.get(4),rig.get(5));
            check(shader>0 && mesh>0,"skinning GPU resources created");
            op(gpu,"graphics.shader.bind",shader);
            op(gpu,"graphics.shader.uniform_mat4_array",shader,"uBones",
                    GltfSkinParser.sample(rig,0.0));
            op(gpu,"graphics.opengl.clear",0,0,0);
            op(gpu,"graphics.mesh.draw",mesh);
            glFinish();
            int[] previous=pixel(240,180);
            int[] stationary=pixel(95,105);
            check(previous[0]<15,"initial upper-right location empty");
            check(stationary[0]>200,"unanimated left half rendered");
            op(gpu,"graphics.shader.uniform_mat4_array",shader,"uBones",
                    GltfSkinParser.sample(rig,1.0));
            op(gpu,"graphics.opengl.clear",0,0,0);
            op(gpu,"graphics.mesh.draw",mesh);
            glFinish();
            int[] moved=pixel(240,180);
            int[] stationaryAfter=pixel(95,105);
            check(moved[0]>190 && moved[1]<80,
                    "skin palette must move the right edge into the upper-right pixel: "
                    +java.util.Arrays.toString(moved));
            check(stationaryAfter[0]>200,"root-bound vertices remain stationary");
            check(glGetError()==GL_NO_ERROR,"no OpenGL error from skinning");
            op(gpu,"graphics.mesh.destroy",mesh);
            op(gpu,"graphics.shader.destroy",shader);
        }
        System.out.println("PASS: animated GLB GPU skinning, joint palette and moving framebuffer pixel");
    }
}

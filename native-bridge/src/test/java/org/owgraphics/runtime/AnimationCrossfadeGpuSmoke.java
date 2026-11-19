package org.owgraphics.runtime;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33.*;

/** Real GPU proof: two animation clips and their blended pose move jointed geometry. */
public final class AnimationCrossfadeGpuSmoke {
    private static int op(GraphicsRuntimeExtension gpu,String op,Object... args) {
        return (int)gpu.invoke(op,List.of(args));
    }
    private static Path root() {
        Path cwd=Path.of("").toAbsolutePath();
        for(Path dir:List.of(cwd,cwd.resolve("ow-graphics"),cwd.getParent(),
                cwd.resolve("..").resolve("ow-graphics")))
            if(dir!=null&&Files.exists(dir.resolve("examples/animation_library/assets/wave_bounce.glb")))
                return dir;
        throw new AssertionError("Multi-clip GLB fixture missing");
    }
    private static double weightedCenter(GraphicsRuntimeExtension gpu,int shader,int mesh,
                                         List<Object> pose) {
        op(gpu,"graphics.shader.uniform_mat4_array",shader,"uBones",pose);
        op(gpu,"graphics.opengl.clear",0,0,0);
        op(gpu,"graphics.mesh.draw",mesh);
        glFinish();
        ByteBuffer pixels=BufferUtils.createByteBuffer(320*240*4);
        glReadBuffer(GL_BACK);
        glReadPixels(0,0,320,240,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        long count=0,ysum=0;
        for(int y=0;y<240;y++)for(int x=175;x<315;x++) {
            int at=(y*320+x)*4;
            int red=pixels.get(at)&255,green=pixels.get(at+1)&255;
            if(red>170 && green<110) {count++;ysum+=y;}
        }
        if(count<400)throw new AssertionError("Skinned mesh not drawn on GPU: "+count);
        return (double)ysum/count;
    }
    public static void main(String[] args) {
        List<Object> library=GltfSkinParser.loadLibraryRelative(
                "examples/animation_library/assets/wave_bounce.glb",root());
        List<?> rig=(List<?>)library.get(0);
        try(var gpu=new GraphicsRuntimeExtension()) {
            op(gpu,"graphics.window.open",320,240,"OW Animation Crossfade","opengl");
            int shader=op(gpu,"graphics.shader.create",
                    "#version 330 core\nlayout(location=0) in vec3 position;\n"
                    +"layout(location=7) in vec4 joints;\nlayout(location=8) in vec4 weights;\n"
                    +"uniform mat4 uBones[32];\nvoid main(){vec4 p=vec4(position,1.0);"
                    +"gl_Position=weights.x*(uBones[int(joints.x)]*p)"
                    +"+weights.y*(uBones[int(joints.y)]*p)"
                    +"+weights.z*(uBones[int(joints.z)]*p)"
                    +"+weights.w*(uBones[int(joints.w)]*p);}",
                    "#version 330 core\nout vec4 color;\n"
                    +"void main(){color=vec4(0.97,0.1,0.1,1.0);}");
            int mesh=op(gpu,"graphics.mesh.create_skinned_indexed",
                    rig.get(0),rig.get(1),rig.get(2),rig.get(3),rig.get(4),rig.get(5));
            op(gpu,"graphics.shader.bind",shader);
            double upper=weightedCenter(gpu,shader,mesh,GltfSkinParser.sampleClip(library,0,1));
            double lower=weightedCenter(gpu,shader,mesh,GltfSkinParser.sampleClip(library,1,1));
            double middle=weightedCenter(gpu,shader,mesh,
                    GltfSkinParser.blendClips(library,0,1,1,1,0.5));
            if(!(upper>middle+8 && middle>lower+8))
                throw new AssertionError("GPU joint animation centers should descend through crossfade: "
                        +upper+" > "+middle+" > "+lower);
            if(glGetError()!=GL_NO_ERROR)
                throw new AssertionError("OpenGL crossfade rendering error");
            op(gpu,"graphics.mesh.destroy",mesh);
            op(gpu,"graphics.shader.destroy",shader);
        }
        System.out.println("PASS: GLB Wave/Bounce GPU skinning and intermediate crossfade pose");
    }
}

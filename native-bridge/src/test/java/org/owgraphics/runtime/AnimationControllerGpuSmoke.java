package org.owgraphics.runtime;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33.*;

/** Mesa proof: GPU additive bone deformation and root translation use same skinned mesh. */
public final class AnimationControllerGpuSmoke {
    private static int op(GraphicsRuntimeExtension gpu,String name,Object... args) {
        return (int)gpu.invoke(name,List.of(args));
    }
    private static Path root() {
        Path cwd=Path.of("").toAbsolutePath();
        for(Path path:List.of(cwd,cwd.resolve("ow-graphics"),cwd.getParent(),
                cwd.resolve("..").resolve("ow-graphics")))
            if(path!=null&&Files.exists(path.resolve("examples/animation_controller/assets/three_clips.glb")))
                return path;
        throw new AssertionError("Animation fixture not found");
    }
    private static double[] center(GraphicsRuntimeExtension gpu,int shader,int mesh,
                                   List<Object> palette,boolean rightOnly) {
        op(gpu,"graphics.shader.uniform_mat4_array",shader,"uBones",palette);
        op(gpu,"graphics.opengl.clear",0,0,0);
        op(gpu,"graphics.mesh.draw",mesh);
        glFinish();
        ByteBuffer pixels=BufferUtils.createByteBuffer(320*240*4);
        glReadBuffer(GL_BACK);
        glReadPixels(0,0,320,240,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        long count=0,xSum=0,ySum=0;
        for(int y=0;y<240;y++)for(int x=rightOnly?175:0;x<320;x++) {
            int at=(y*320+x)*4;
            if((pixels.get(at)&255)>190 && (pixels.get(at+1)&255)<100) {
                count++;xSum+=x;ySum+=y;
            }
        }
        if(count<250)throw new AssertionError("GPU skinned mesh not visible: "+count);
        return new double[]{(double)xSum/count,(double)ySum/count};
    }
    public static void main(String[] args) {
        var lib=GltfSkinParser.loadLibraryRelative(
                "examples/animation_controller/assets/three_clips.glb",root());
        List<?> rig=(List<?>)lib.get(0);
        try(var gpu=new GraphicsRuntimeExtension()) {
            op(gpu,"graphics.window.open",320,240,"OW Animation State GPU","opengl");
            int shader=op(gpu,"graphics.shader.create",
                    "#version 330 core\nlayout(location=0) in vec3 pos;\n"
                    +"layout(location=7) in vec4 joints;\nlayout(location=8) in vec4 weights;\n"
                    +"uniform mat4 uBones[32];\nvoid main(){vec4 p=vec4(pos,1);"
                    +"gl_Position=weights.x*(uBones[int(joints.x)]*p)"
                    +"+weights.y*(uBones[int(joints.y)]*p)"
                    +"+weights.z*(uBones[int(joints.z)]*p)"
                    +"+weights.w*(uBones[int(joints.w)]*p);}",
                    "#version 330 core\nout vec4 pixel;\n"
                    +"void main(){pixel=vec4(0.96,0.1,0.1,1);}");
            int mesh=op(gpu,"graphics.mesh.create_skinned_indexed",
                    rig.get(0),rig.get(1),rig.get(2),rig.get(3),rig.get(4),rig.get(5));
            op(gpu,"graphics.shader.bind",shader);
            var wave=GltfSkinParser.sampleClip(lib,0,0.5);
            var overlay=GltfSkinParser.additiveClips(lib,0,0.5,1,0.5,0,0.5,List.of(1));
            var bounce=GltfSkinParser.sampleClip(lib,1,0.5);
            double ywave=center(gpu,shader,mesh,wave,true)[1];
            double yoverlay=center(gpu,shader,mesh,overlay,true)[1];
            double ybounce=center(gpu,shader,mesh,bounce,true)[1];
            if(!(ywave>yoverlay+5 && yoverlay>ybounce+5))
                throw new AssertionError("GPU additive overlay should sit between clips: "
                        +ywave+", "+yoverlay+", "+ybounce);
            double xStart=center(gpu,shader,mesh,GltfSkinParser.sampleClip(lib,2,0),false)[0];
            double xHalf=center(gpu,shader,mesh,GltfSkinParser.sampleClip(lib,2,0.5),false)[0];
            if(!(xHalf>xStart+30))
                throw new AssertionError("GPU root joint motion should move mesh horizontally: "
                        +xStart+" to "+xHalf);
            if(glGetError()!=GL_NO_ERROR)throw new AssertionError("OpenGL error");
            op(gpu,"graphics.mesh.destroy",mesh);
            op(gpu,"graphics.shader.destroy",shader);
        }
        System.out.println("PASS: real Mesa additive bone layer, root motion and GPU resource cleanup");
    }
}

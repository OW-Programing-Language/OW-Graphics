package org.owgraphics.runtime;

import java.nio.ByteBuffer;
import java.util.List;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33.*;

/** Tests actual mat4 vertex divisors and one indexed draw call with two instances. */
public final class InstancingGpuSmoke {
    private static int op(GraphicsRuntimeExtension gpu,String name,Object... args) {
        return (int)gpu.invoke(name,List.of(args));
    }
    private static void check(boolean ok,String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static int[] pixel(int x,int y) {
        ByteBuffer rgba=BufferUtils.createByteBuffer(4);
        glReadBuffer(GL_BACK);
        glReadPixels(x,y,1,1,GL_RGBA,GL_UNSIGNED_BYTE,rgba);
        return new int[]{rgba.get(0)&255,rgba.get(1)&255,rgba.get(2)&255};
    }
    private static List<Double> matrix(double x) {
        return List.of(1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,
                0.0,0.0,1.0,0.0,x,0.0,0.0,1.0);
    }
    public static void main(String[] args) {
        try(var gpu=new GraphicsRuntimeExtension()) {
            check(op(gpu,"graphics.window.open",320,240,"OW instancing smoke","opengl")==1,"window");
            int shader=op(gpu,"graphics.shader.create",
                    "#version 330 core\nlayout(location=0) in vec3 pos;\nlayout(location=3) in mat4 model;\nflat out int which;\nvoid main(){which=gl_InstanceID;gl_Position=model*vec4(pos,1.0);}",
                    "#version 330 core\nflat in int which;\nout vec4 pixel;\nvoid main(){pixel=(which==0)?vec4(1.0,0.15,0.1,1.0):vec4(0.1,0.2,1.0,1.0);}");
            int mesh=op(gpu,"graphics.mesh.create_indexed",
                    List.of(-0.3,-0.4,0.0,0.3,-0.4,0.0,0.3,0.4,0.0,-0.3,0.4,0.0),
                    List.of(0.0,0.0,1.0,0.0,1.0,1.0,0.0,1.0),
                    List.of(0,1,2,2,3,0));
            op(gpu,"graphics.opengl.clear",0,0,0);
            op(gpu,"graphics.shader.bind",shader);
            check(op(gpu,"graphics.mesh.draw_instanced",mesh,
                    List.of(matrix(-0.55),matrix(0.55)))==1,"2 instances draw");
            glFinish();
            var left=pixel(80,120);
            var right=pixel(240,120);
            var middle=pixel(160,120);
            check(left[0]>230 && left[2]<70,"left instance should be red");
            check(right[2]>230 && right[0]<70,"right instance should be blue");
            check(middle[0]<15 && middle[2]<15,"space between instances should remain clear");
            // Reuse existing GPU matrix buffer across draws with different transforms.
            op(gpu,"graphics.opengl.clear",0,0,0);
            op(gpu,"graphics.mesh.draw_instanced",mesh,List.of(matrix(0.0)));
            glFinish();
            check(pixel(160,120)[0]>230,"single-instance buffer update");
            op(gpu,"graphics.mesh.destroy",mesh);
            boolean rejected=false;
            try {op(gpu,"graphics.mesh.draw_instanced",mesh,List.of(matrix(0.0)));}
            catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"destroyed instanced mesh must be rejected");
            op(gpu,"graphics.shader.destroy",shader);
            check(glGetError()==GL_NO_ERROR,"no GL error");
        }
        System.out.println("PASS: real GL mat4 divisor, indexed instancing, buffer reuse and cleanup");
    }
}

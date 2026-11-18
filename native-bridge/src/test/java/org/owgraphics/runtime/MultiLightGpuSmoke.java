package org.owgraphics.runtime;

import java.nio.ByteBuffer;
import java.util.List;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33.*;

/** Framebuffer verifies two independent directional sources plus a point light. */
public final class MultiLightGpuSmoke {
    private static int invoke(GraphicsRuntimeExtension gpu, String name, Object... arguments) {
        return (int) gpu.invoke(name, List.of(arguments));
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static int[] pixel() {
        ByteBuffer data = BufferUtils.createByteBuffer(4);
        glReadBuffer(GL_BACK);
        glReadPixels(160, 120, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, data);
        return new int[]{data.get(0) & 255, data.get(1) & 255, data.get(2) & 255};
    }
    private static void render(GraphicsRuntimeExtension graphics, int program, int mesh,
                               double key, double fill, double point) {
        invoke(graphics, "graphics.opengl.clear", 0, 0, 0);
        invoke(graphics, "graphics.shader.uniform4", program, "uStrengths",
                key, fill, point, 0.0);
        invoke(graphics, "graphics.mesh.draw", mesh);
        glFinish();
    }
    public static void main(String[] args) {
        try (var graphics = new GraphicsRuntimeExtension()) {
            invoke(graphics, "graphics.window.open", 320, 240, "OW multiple lights", "opengl");
            int mesh = invoke(graphics, "graphics.mesh.create_lit_indexed",
                    List.of(-0.75,-0.75,0.0, 0.75,-0.75,0.0, 0.0,0.8,0.0),
                    List.of(0.0,0.0, 1.0,0.0, 0.5,1.0),
                    List.of(0.0,0.0,1.0, 0.0,0.0,1.0, 0.0,0.0,1.0),
                    List.of(0,1,2));
            int program = invoke(graphics,"graphics.shader.create",
                    "#version 330 core\nlayout(location=0) in vec3 pos;\nlayout(location=2) in vec3 norm;\nout vec3 n;\nout vec3 world;\nvoid main(){world=pos;n=norm;gl_Position=vec4(pos,1.0);}",
                    "#version 330 core\nin vec3 n;\nin vec3 world;\nout vec4 pixel;\nuniform vec4 uKeyDir;\nuniform vec4 uFillDir;\nuniform vec4 uPointPos;\nuniform vec4 uStrengths;\nvoid main(){vec3 normal=normalize(n);vec3 point=uPointPos.xyz-world;float atten=1.0/(1.0+0.12*dot(point,point));float a=max(dot(normal,normalize(uKeyDir.xyz)),0.0)*uStrengths.x;float b=max(dot(normal,normalize(uFillDir.xyz)),0.0)*uStrengths.y;float c=max(dot(normal,normalize(point)),0.0)*uStrengths.z*atten;pixel=vec4(0.08+a,0.08+b,0.08+c,1.0);}");
            invoke(graphics,"graphics.shader.bind",program);
            invoke(graphics,"graphics.shader.uniform4",program,"uKeyDir",0.0,0.0,1.0,0.0);
            invoke(graphics,"graphics.shader.uniform4",program,"uFillDir",0.0,0.0,1.0,0.0);
            invoke(graphics,"graphics.shader.uniform4",program,"uPointPos",0.0,0.0,2.0,1.0);
            render(graphics,program,mesh,0.55,0.45,0.70);
            int[] all = pixel();
            check(all[0] > 140 && all[1] > 110 && all[2] > 100,
                    "All three independent lights must contribute to the pixel");

            render(graphics,program,mesh,0.0,0.0,0.70);
            int[] pointOnly = pixel();
            check(pointOnly[0] < 35 && pointOnly[1] < 35 && pointOnly[2] > 100,
                    "Point light only must preserve blue while removing directional red/green");

            render(graphics,program,mesh,0.55,0.0,0.0);
            int[] keyOnly = pixel();
            check(keyOnly[0] > 140 && keyOnly[1] < 35 && keyOnly[2] < 35,
                    "Key directional light only must contribute red");

            invoke(graphics,"graphics.mesh.destroy",mesh);
            invoke(graphics,"graphics.shader.destroy",program);
            check(glGetError() == GL_NO_ERROR, "OpenGL errors");
        }
        System.out.println("PASS: two directional lights + point attenuation in GL framebuffer");
    }
}

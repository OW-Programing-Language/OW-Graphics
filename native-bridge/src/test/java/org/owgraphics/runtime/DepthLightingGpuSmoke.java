package org.owgraphics.runtime;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33.*;

/** Mesa/Xvfb framebuffer integration test: depth ordering + diffuse normals. */
public final class DepthLightingGpuSmoke {
    private static int op(GraphicsRuntimeExtension gpu, String operation, Object... arguments) {
        return (int) gpu.invoke(operation, List.of(arguments));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static int[] centerRgb() {
        ByteBuffer data = BufferUtils.createByteBuffer(4);
        glReadBuffer(GL_BACK);
        glReadPixels(160, 120, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, data);
        return new int[]{data.get(0) & 255, data.get(1) & 255, data.get(2) & 255};
    }

    private static int rgbRed() { return centerRgb()[0]; }

    public static void main(String[] unused) {
        try (var graphics = new GraphicsRuntimeExtension()) {
            check(op(graphics, "graphics.window.open", 320, 240, "OW 3D GPU smoke", "opengl") == 1,
                    "OpenGL 3.3 window");
            var uv = List.of(0.0,0.0, 1.0,0.0, 1.0,1.0, 0.0,1.0);
            var index = List.of(0,1,2, 2,3,0);
            var near = List.of(-0.6,-0.6,-1.2, 0.6,-0.6,-1.2,
                               0.6, 0.6,-1.2,-0.6, 0.6,-1.2);
            var far = List.of(-0.6,-0.6,-2.0, 0.6,-0.6,-2.0,
                              0.6, 0.6,-2.0,-0.6, 0.6,-2.0);
            int nearMesh = op(graphics,"graphics.mesh.create_indexed",near,uv,index);
            int farMesh = op(graphics,"graphics.mesh.create_indexed",far,uv,index);
            int program = op(graphics,"graphics.shader.create",
                    "#version 330 core\nlayout(location=0) in vec3 aPos;\nuniform mat4 uProjection;\nvoid main(){gl_Position=uProjection*vec4(aPos,1.0);}",
                    "#version 330 core\nuniform vec4 uColor;\nout vec4 pixel;\nvoid main(){pixel=uColor;}");
            var projection = List.of(
                    0.75,0.0,0.0,0.0, 0.0,1.0,0.0,0.0,
                    0.0,0.0,-1.020202,-1.0, 0.0,0.0,-0.2020202,0.0);
            check(op(graphics, "graphics.opengl.depth_test",1) == 1, "enable depth");
            check(glIsEnabled(GL_DEPTH_TEST), "GL_DEPTH_TEST must be active");
            // GL_DEPTH_BITS is not guaranteed to be queryable in a core context.
            // Depth correctness is verified below through actual occlusion and
            // glReadPixels(GL_DEPTH_COMPONENT), not implementation-dependent state.
            op(graphics,"graphics.shader.bind",program);
            op(graphics,"graphics.shader.uniform_mat4",program,"uProjection",projection);
            op(graphics,"graphics.opengl.clear",0,0,0);
            op(graphics,"graphics.shader.uniform4",program,"uColor",1.0,0.0,0.0,1.0);
            op(graphics,"graphics.mesh.draw",nearMesh);
            op(graphics,"graphics.shader.uniform4",program,"uColor",0.0,0.0,1.0,1.0);
            op(graphics,"graphics.mesh.draw",farMesh);
            glFinish();
            var center = centerRgb();
            check(center[0] > 235 && center[2] < 20,
                    "Depth buffer must retain near red over far blue");
            FloatBuffer depth = BufferUtils.createFloatBuffer(1);
            glReadPixels(160,120,1,1,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
            check(depth.get(0) < 1.0f && depth.get(0) > 0.0f,
                    "Depth buffer must contain a geometrical depth value");

            // With depth explicitly disabled, later far geometry replaces near.
            op(graphics,"graphics.opengl.depth_test",0);
            check(!glIsEnabled(GL_DEPTH_TEST), "disable depth");
            op(graphics,"graphics.opengl.clear",0,0,0);
            op(graphics,"graphics.shader.uniform4",program,"uColor",1.0,0.0,0.0,1.0);
            op(graphics,"graphics.mesh.draw",nearMesh);
            op(graphics,"graphics.shader.uniform4",program,"uColor",0.0,0.0,1.0,1.0);
            op(graphics,"graphics.mesh.draw",farMesh);
            glFinish();
            center = centerRgb();
            check(center[2] > 235 && center[0] < 20,
                    "Without depth test the later far blue mesh must win");

            int litMesh = op(graphics,"graphics.mesh.create_lit_indexed",near,uv,
                    List.of(0.0,0.0,1.0, 0.0,0.0,1.0, 0.0,0.0,1.0, 0.0,0.0,1.0),index);
            int litProgram = op(graphics,"graphics.shader.create",
                    "#version 330 core\nlayout(location=0) in vec3 aPos;\nlayout(location=2) in vec3 aNormal;\nout vec3 normal;\nuniform mat4 uProjection;\nvoid main(){normal=aNormal;gl_Position=uProjection*vec4(aPos,1.0);}",
                    "#version 330 core\nin vec3 normal;\nuniform vec4 uLight;\nout vec4 pixel;\nvoid main(){float n=max(dot(normalize(normal),normalize(uLight.xyz)),0.0);pixel=vec4(vec3(0.2+0.8*n),1.0);}");
            op(graphics,"graphics.shader.bind",litProgram);
            op(graphics,"graphics.shader.uniform_mat4",litProgram,"uProjection",projection);
            op(graphics,"graphics.opengl.clear",0,0,0);
            op(graphics,"graphics.shader.uniform4",litProgram,"uLight",0.0,0.0,1.0,0.0);
            op(graphics,"graphics.mesh.draw",litMesh);
            glFinish();
            int bright = rgbRed();
            op(graphics,"graphics.opengl.clear",0,0,0);
            op(graphics,"graphics.shader.uniform4",litProgram,"uLight",0.0,0.0,-1.0,0.0);
            op(graphics,"graphics.mesh.draw",litMesh);
            glFinish();
            int dark = rgbRed();
            check(bright > 225 && dark < 70 && bright - dark > 150,
                    "Normal-based diffuse lighting must darken the back-facing light test");

            op(graphics,"graphics.mesh.destroy",nearMesh);
            op(graphics,"graphics.mesh.destroy",farMesh);
            op(graphics,"graphics.mesh.destroy",litMesh);
            op(graphics,"graphics.shader.destroy",program);
            op(graphics,"graphics.shader.destroy",litProgram);
            check(glGetError() == GL_NO_ERROR, "OpenGL must report no errors");
            op(graphics,"graphics.window.close");
        }
        System.out.println("PASS: depth occlusion, depth toggle, geometry normals and diffuse brightness");
    }
}

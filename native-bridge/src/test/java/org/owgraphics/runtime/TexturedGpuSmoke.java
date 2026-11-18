package org.owgraphics.runtime;

import java.nio.ByteBuffer;
import java.util.List;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33.*;

/** Real Mesa/Xvfb integration test for textures, index buffers and mat4 transforms. */
public final class TexturedGpuSmoke {
    private static int invoke(GraphicsRuntimeExtension gpu, String operation, Object... args) {
        return (int) gpu.invoke(operation, List.of(args));
    }
    private static void check(boolean yes, String message) {
        if (!yes) throw new AssertionError(message);
    }
    private static void assertPixel(int x, int y, int r, int g, int b) {
        ByteBuffer bytes = BufferUtils.createByteBuffer(4);
        glReadBuffer(GL_BACK);
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
        int actualR = bytes.get(0) & 0xff;
        int actualG = bytes.get(1) & 0xff;
        int actualB = bytes.get(2) & 0xff;
        int tolerance = 12;
        check(Math.abs(actualR - r) <= tolerance
                && Math.abs(actualG - g) <= tolerance
                && Math.abs(actualB - b) <= tolerance,
                "Unexpected pixel at " + x + "," + y + ": " + actualR + ","
                        + actualG + "," + actualB + " expected " + r + "," + g + "," + b);
    }

    public static void main(String[] args) throws Exception {
        try (GraphicsRuntimeExtension gpu = new GraphicsRuntimeExtension()) {
            check(invoke(gpu, "graphics.window.open", 320, 240, "OW Textures GPU smoke", "opengl") == 1,
                    "OpenGL 3.3 window creation");
            int shader = invoke(gpu, "graphics.shader.create",
                    "#version 330 core\nlayout(location=0) in vec3 aPosition;\nlayout(location=1) in vec2 aUv;\nout vec2 vUv;\nuniform mat4 uModel;\nvoid main(){vUv=aUv;gl_Position=uModel*vec4(aPosition,1.0);}",
                    "#version 330 core\nin vec2 vUv;\nout vec4 pixel;\nuniform sampler2D uTexture;\nvoid main(){pixel=texture(uTexture,vUv);}");
            int mesh = invoke(gpu, "graphics.mesh.create_indexed",
                    List.of(-0.8,-0.8,0.0, 0.8,-0.8,0.0, 0.8,0.8,0.0, -0.8,0.8,0.0),
                    List.of(0.0,0.0, 1.0,0.0, 1.0,1.0, 0.0,1.0),
                    List.of(0,1,2, 2,3,0));
            int texture = invoke(gpu, "graphics.texture.create_rgba8", 2, 2,
                    List.of(255,0,0,255, 0,255,0,255,
                            0,0,255,255, 255,255,0,255));
            check(mesh > 0 && texture > 0 && shader > 0, "Allocated resources");
            invoke(gpu, "graphics.opengl.clear", 0, 0, 0);
            invoke(gpu, "graphics.shader.bind", shader);
            check(invoke(gpu, "graphics.shader.uniform_mat4", shader, "uModel",
                    List.of(0.5,0.0,0.0,0.0, 0.0,0.5,0.0,0.0,
                            0.0,0.0,1.0,0.0, 0.25,-0.1,0.0,1.0)) == 1, "Matrix uniform");
            check(invoke(gpu, "graphics.shader.uniform_int", shader, "uTexture", 0) == 1,
                    "Sampler uniform");
            check(invoke(gpu, "graphics.texture.bind", texture, 0) == 1,
                    "Texture binding");
            check(invoke(gpu, "graphics.mesh.draw", mesh) == 1,
                    "glDrawElements call");
            glFinish();
            // Nonidentity model matrix scales to 50% and translates (+0.25,-0.10).
            // These four positions are inside the transformed textured quadrants.
            assertPixel(180, 90, 255, 0, 0);     // bottom-left
            assertPixel(220, 90, 0, 255, 0);     // bottom-right
            assertPixel(180, 130, 0, 0, 255);    // top-left
            assertPixel(220, 130, 255, 255, 0);  // top-right
            assertPixel(80, 60, 0, 0, 0);        // background (outside moved mesh)

            check(invoke(gpu, "graphics.texture.destroy", texture) == 1, "release texture");
            check(invoke(gpu, "graphics.mesh.destroy", mesh) == 1, "release index buffers");
            check(invoke(gpu, "graphics.shader.destroy", shader) == 1, "release shader");
            boolean rejectsDestroyed = false;
            try { invoke(gpu, "graphics.texture.bind", texture, 0); }
            catch (IllegalArgumentException expected) { rejectsDestroyed = true; }
            check(rejectsDestroyed, "Destroyed texture handle must not work");
            invoke(gpu, "graphics.window.close");
            invoke(gpu, "graphics.window.open", 64, 64, "reopen", "opengl");
            rejectsDestroyed = false;
            try { invoke(gpu, "graphics.mesh.draw", mesh); }
            catch (IllegalArgumentException expected) { rejectsDestroyed = true; }
            check(rejectsDestroyed, "Previous-context indexed mesh must not work");
        }
        System.out.println("PASS: indexed textured quad, four RGBA pixels, mat4 + GPU cleanup");
    }
}

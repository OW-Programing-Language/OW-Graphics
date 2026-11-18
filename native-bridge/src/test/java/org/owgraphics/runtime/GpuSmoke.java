package org.owgraphics.runtime;

import dev.oreo.RuntimeExtension;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.ServiceLoader;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33.*;

/**
 * Opt-in GPU integration test (NOT a Maven unit test). Run under Xvfb/Mesa
 * with `java -cp ... org.owgraphics.runtime.GpuSmoke`.
 * Renders one real GLSL triangle and checks the framebuffer's center pixel.
 */
public final class GpuSmoke {
    private static int invokeInt(RuntimeExtension provider, String operation, Object... args) {
        return (int) provider.invoke(operation, List.of(args));
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        long count = ServiceLoader.load(RuntimeExtension.class).stream()
                .filter(p -> p.type() == GraphicsRuntimeExtension.class).count();
        check(count == 1, "SPI must register exactly one GraphicsRuntimeExtension");

        try (GraphicsRuntimeExtension graphics = new GraphicsRuntimeExtension()) {
            check(invokeInt(graphics, "graphics.window.open",
                    320, 240, "OW Graphics GPU smoke", "opengl") == 1, "GLFW open");
            int shader = invokeInt(graphics, "graphics.shader.create",
                    "#version 330 core\nlayout (location=0) in vec3 xyz;\nvoid main(){gl_Position=vec4(xyz,1.0);}",
                    "#version 330 core\nuniform vec4 tint;\nout vec4 pixel;\nvoid main(){pixel=tint;}");
            int mesh = invokeInt(graphics, "graphics.mesh.create",
                    List.of(-0.65, -0.55, 0.0, 0.65, -0.55, 0.0, 0.0, 0.68, 0.0));
            check(shader > 0 && mesh > 0, "GPU handles must be allocated");
            check(invokeInt(graphics, "graphics.opengl.clear", 16, 23, 39) == 1, "glClear");
            check(invokeInt(graphics, "graphics.shader.bind", shader) == 1, "bind shader");
            check(invokeInt(graphics, "graphics.shader.uniform4",
                    shader, "tint", 0.20, 0.74, 0.86, 1.0) == 1, "set tint uniform");
            check(invokeInt(graphics, "graphics.mesh.draw", mesh) == 1, "draw mesh");

            // Read back from GL_BACK before swapping; center is inside the triangle.
            ByteBuffer pixel = BufferUtils.createByteBuffer(4);
            glReadBuffer(GL_BACK);
            glReadPixels(160, 120, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            int red = pixel.get(0) & 0xFF;
            int green = pixel.get(1) & 0xFF;
            int blue = pixel.get(2) & 0xFF;
            check(red >= 35 && red <= 75 && green >= 155 && green <= 220
                            && blue >= 185 && blue <= 245,
                    "Framebuffer mismatch: rgb=" + red + "," + green + "," + blue);

            check(invokeInt(graphics, "graphics.window.present") == 1, "glfwSwapBuffers");
            check(invokeInt(graphics, "graphics.mesh.destroy", mesh) == 1, "release mesh");
            check(invokeInt(graphics, "graphics.shader.destroy", shader) == 1, "release shader");

            boolean rejected = false;
            try {
                invokeInt(graphics, "graphics.mesh.draw", mesh);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            check(rejected, "Destroyed mesh must never be usable");
            check(invokeInt(graphics, "graphics.window.close") == 1, "release window");

            // Allocator must never reuse old handles across OpenGL contexts.
            check(invokeInt(graphics, "graphics.window.open",
                    128, 128, "OW Graphics reopen", "opengl") == 1, "reopen GLFW");
            rejected = false;
            try {
                invokeInt(graphics, "graphics.shader.bind", shader);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            check(rejected, "Old-context shader handle must be rejected");
            check(invokeInt(graphics, "graphics.window.close") == 1, "close second window");
        }
        System.out.println("PASS: GLFW/OpenGL 3.3 software render + pixel/readback + resource lifecycle");
    }
}

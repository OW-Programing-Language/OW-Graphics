package org.owgraphics.runtime;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33.*;

/**
 * Mesa/Xvfb GPU test: load an actual PNG file and render two separate
 * objects with independent material tints and model matrices.
 */
public final class MaterialSceneGpuSmoke {
    private static int op(GraphicsRuntimeExtension gpu, String operation, Object... args) {
        return (int) gpu.invoke(operation, List.of(args));
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static int[] pixel(int x, int y) {
        ByteBuffer rgba = BufferUtils.createByteBuffer(4);
        glReadBuffer(GL_BACK);
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        return new int[]{rgba.get(0) & 255, rgba.get(1) & 255, rgba.get(2) & 255};
    }

    public static void main(String[] args) {
        try (var gpu = new GraphicsRuntimeExtension()) {
            check(op(gpu, "graphics.window.open", 320, 240, "OW material scene smoke", "opengl") == 1, "window");
            int shader = op(gpu, "graphics.shader.create",
                    "#version 330 core\nlayout(location=0) in vec3 position;\nlayout(location=1) in vec2 uv;\nout vec2 vUv;\nuniform mat4 uModel;\nvoid main(){vUv=uv;gl_Position=uModel*vec4(position,1.0);}",
                    "#version 330 core\nin vec2 vUv;\nout vec4 pixel;\nuniform sampler2D uTexture;\nuniform vec4 uTint;\nvoid main(){pixel=texture(uTexture,vUv)*uTint;}");
            int geometry = op(gpu, "graphics.mesh.create_indexed",
                    List.of(-0.32,-0.5,0.0, 0.32,-0.5,0.0,
                            0.32,0.5,0.0, -0.32,0.5,0.0),
                    List.of(0.0,0.0,1.0,0.0,1.0,1.0,0.0,1.0),
                    List.of(0,1,2,2,3,0));
            int texture = op(gpu, "graphics.texture.load_image",
                    "examples/material_scene/assets/checker.png");
            check(shader > 0 && geometry > 0 && texture > 0, "allocate handles");
            op(gpu, "graphics.opengl.clear", 0, 0, 0);
            op(gpu, "graphics.shader.bind", shader);
            op(gpu, "graphics.shader.uniform_int", shader, "uTexture", 0);
            op(gpu, "graphics.texture.bind", texture, 0);

            op(gpu, "graphics.shader.uniform_mat4", shader, "uModel", List.of(
                    1.0,0.0,0.0,0.0, 0.0,1.0,0.0,0.0, 0.0,0.0,1.0,0.0,
                    -0.52,0.0,0.0,1.0));
            op(gpu, "graphics.shader.uniform4", shader, "uTint", 1.0,0.2,0.2,1.0);
            op(gpu, "graphics.mesh.draw", geometry);

            op(gpu, "graphics.shader.uniform_mat4", shader, "uModel", List.of(
                    1.0,0.0,0.0,0.0, 0.0,1.0,0.0,0.0, 0.0,0.0,1.0,0.0,
                    0.52,0.0,0.0,1.0));
            op(gpu, "graphics.shader.uniform4", shader, "uTint", 0.2,0.3,1.0,1.0);
            op(gpu, "graphics.mesh.draw", geometry);
            glFinish();
            int[] left = pixel(77, 120);
            int[] right = pixel(243, 120);
            int[] middle = pixel(160, 120);
            check(left[0] > 120 && left[1] < 85 && left[2] < 85,
                    "Left material should tint the PNG red: " + left[0] + "," + left[1] + "," + left[2]);
            check(right[2] > 120 && right[0] < 85 && right[1] < 120,
                    "Right material should tint the PNG blue: " + right[0] + "," + right[1] + "," + right[2]);
            check(middle[0] < 20 && middle[1] < 20 && middle[2] < 20,
                    "Background between separate objects should be clear");

            op(gpu, "graphics.texture.destroy", texture);
            op(gpu, "graphics.mesh.destroy", geometry);
            op(gpu, "graphics.shader.destroy", shader);
            boolean rejected = false;
            try { op(gpu, "graphics.texture.bind", texture, 0); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "destroyed file-backed texture must be invalid");
        }
        System.out.println("PASS: PNG GPU upload, two independent materials and model transforms");
    }
}

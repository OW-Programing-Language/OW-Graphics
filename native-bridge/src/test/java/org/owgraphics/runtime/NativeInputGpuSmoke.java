package org.owgraphics.runtime;

import java.util.List;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

/** GUI-backed input test: cursor polling, named keys/buttons and scroll reset. */
public final class NativeInputGpuSmoke {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        try (var graphics = new GraphicsRuntimeExtension()) {
            check((int) graphics.invoke("graphics.window.open",
                    List.of(320, 240, "OW input smoke", "opengl")) == 1, "open");
            check((int) graphics.invoke("graphics.input.key_down", List.of("LEFT")) == 0,
                    "no arrow key pressed");
            check((int) graphics.invoke("graphics.input.mouse_button_down", List.of("LEFT")) == 0,
                    "no left mouse pressed");
            var first = (List<?>) graphics.invoke("graphics.input.cursor_delta", List.of());
            check(first.size() == 2 && ((Number) first.get(0)).doubleValue() == 0.0,
                    "first cursor sample must be zero to avoid a camera jump");
            double initialX, initialY;
            long window = GLFW.glfwGetCurrentContext();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var x = stack.mallocDouble(1);
                var y = stack.mallocDouble(1);
                GLFW.glfwGetCursorPos(window, x, y);
                initialX = x.get(0);
                initialY = y.get(0);
            }
            GLFW.glfwSetCursorPos(window, initialX + 32.0, initialY + 18.0);
            graphics.invoke("graphics.window.poll", List.of());
            var second = (List<?>) graphics.invoke("graphics.input.cursor_delta", List.of());
            double dx = ((Number) second.get(0)).doubleValue();
            double dy = ((Number) second.get(1)).doubleValue();
            check(Math.abs(dx - 32.0) < 2.1 && Math.abs(dy - 18.0) < 2.1,
                    "cursor polling must return real mouse changes: " + dx + "," + dy);
            double scroll = ((Number) graphics.invoke("graphics.input.scroll_delta", List.of())).doubleValue();
            check(scroll == 0.0, "initial scroll must be zero");
            check(((Number) graphics.invoke("graphics.input.scroll_delta", List.of())).doubleValue() == 0.0,
                    "scroll accumulator resets between polls");

            boolean invalid = false;
            try { graphics.invoke("graphics.input.key_down", List.of("INVALID")); }
            catch (IllegalArgumentException expected) { invalid = true; }
            check(invalid, "Reject unknown key names");
            invalid = false;
            try { graphics.invoke("graphics.input.mouse_button_down", List.of("INVALID")); }
            catch (IllegalArgumentException expected) { invalid = true; }
            check(invalid, "Reject unknown mouse button names");
            graphics.invoke("graphics.window.close", List.of());
            graphics.invoke("graphics.window.open", List.of(320, 240, "OW reopen", "opengl"));
            var reopen = (List<?>) graphics.invoke("graphics.input.cursor_delta", List.of());
            check(((Number) reopen.get(0)).doubleValue() == 0.0,
                    "reopening must reset cursor state");
        }
        System.out.println("PASS: GLFW keys/buttons, real cursor deltas and input reset");
    }
}

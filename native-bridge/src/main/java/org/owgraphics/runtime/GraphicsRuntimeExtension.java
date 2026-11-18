package org.owgraphics.runtime;

import dev.oreo.RuntimeExtension;
import java.nio.FloatBuffer;
import java.util.List;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/**
 * OW graphics operations, backed by actual native GLFW and OpenGL libraries.
 * An instance is scoped to a single OW Interpreter through ServiceLoader.
 *
 * All graphics/window calls MUST run on the window's owning thread. This
 * initial driver has one OpenGL 3.3 window per interpreter and no Vulkan yet.
 */
public final class GraphicsRuntimeExtension implements RuntimeExtension {
    private long window;
    private Thread owner;
    private boolean glfwInitialized;
    private int program;
    private int vertexArray;
    private int vertexBuffer;

    @Override
    public boolean supports(String operation) {
        return switch (operation) {
            case "graphics.window.open", "graphics.window.poll",
                 "graphics.window.present", "graphics.window.should_close",
                 "graphics.window.close", "graphics.input.escape",
                 "graphics.opengl.clear", "graphics.opengl.triangle" -> true;
            default -> false;
        };
    }

    @Override
    public Object invoke(String operation, List<Object> args) {
        if (!supports(operation))
            throw new IllegalArgumentException("Unknown graphics operation: " + operation);
        return switch (operation) {
            case "graphics.window.open" -> open(args);
            case "graphics.window.poll" -> poll(args);
            case "graphics.window.present" -> present(args);
            case "graphics.window.should_close" -> shouldClose(args);
            case "graphics.window.close" -> closeWindow(args);
            case "graphics.input.escape" -> escapePressed(args);
            case "graphics.opengl.clear" -> clearFrame(args);
            case "graphics.opengl.triangle" -> drawTriangle(args);
            default -> throw new AssertionError("Unreachable graphics operation");
        };
    }

    private static void count(List<Object> args, int expected) {
        if (args.size() != expected)
            throw new IllegalArgumentException("Expected " + expected
                    + " graphics argument(s), got " + args.size());
    }

    private static int intArg(List<Object> args, int position, String name) {
        Object value = args.get(position);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != n.intValue())
            throw new IllegalArgumentException(name + " must be an integer");
        return ((Number) value).intValue();
    }

    private static String stringArg(List<Object> args, int position, String name) {
        Object value = args.get(position);
        if (!(value instanceof String s))
            throw new IllegalArgumentException(name + " must be a string");
        return (String) value;
    }

    private void ensureOwner() {
        if (window == 0L)
            throw new IllegalStateException("No graphics window is open");
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Graphics operation must run on the GLFW owner thread");
    }

    private int open(List<Object> args) {
        count(args, 4);
        int width = intArg(args, 0, "width");
        int height = intArg(args, 1, "height");
        String title = stringArg(args, 2, "title");
        String backend = stringArg(args, 3, "backend");
        if (!"opengl".equals(backend))
            throw new UnsupportedOperationException("Only OpenGL is implemented; Vulkan is planned");
        if (width < 1 || width > 8192 || height < 1 || height > 8192)
            throw new IllegalArgumentException("Window dimensions must be 1..8192");
        if (title.isBlank())
            throw new IllegalArgumentException("Window title must not be blank");
        if (window != 0)
            throw new IllegalStateException("A graphics window is already open");
        if (owner != null && owner != Thread.currentThread())
            throw new IllegalStateException("GLFW initialized by another thread");

        owner = Thread.currentThread();
        if (!glfwInit()) {
            owner = null;
            throw new IllegalStateException("GLFW initialization failed (desktop session required)");
        }
        glfwInitialized = true;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            window = glfwCreateWindow(width, height, title, 0L, 0L);
            if (window == 0L)
                throw new IllegalStateException("Unable to create a GLFW OpenGL 3.3 window");
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            glfwSwapInterval(1);
            glfwShowWindow(window);
            return 1;
        } catch (RuntimeException | Error ex) {
            release();
            throw ex;
        }
    }

    private int poll(List<Object> args) {
        count(args, 0);
        ensureOwner();
        glfwPollEvents();
        return 1;
    }

    private int present(List<Object> args) {
        count(args, 0);
        ensureOwner();
        glfwSwapBuffers(window);
        return 1;
    }

    private int shouldClose(List<Object> args) {
        count(args, 0);
        ensureOwner();
        return glfwWindowShouldClose(window) ? 1 : 0;
    }

    private int escapePressed(List<Object> args) {
        count(args, 0);
        ensureOwner();
        return glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS ? 1 : 0;
    }

    private int clearFrame(List<Object> args) {
        count(args, 3);
        int r = intArg(args, 0, "red");
        int g = intArg(args, 1, "green");
        int b = intArg(args, 2, "blue");
        if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255)
            throw new IllegalArgumentException("RGB channels must be in 0..255");
        ensureOwner();
        int[] viewport = new int[4];
        org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_VIEWPORT, viewport);
        int[] framebufferWidth = new int[1];
        int[] framebufferHeight = new int[1];
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var w = stack.mallocInt(1);
            var h = stack.mallocInt(1);
            glfwGetFramebufferSize(window, w, h);
            glViewport(0, 0, w.get(0), h.get(0));
        }
        glClearColor(r / 255f, g / 255f, b / 255f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);
        return 1;
    }

    private int drawTriangle(List<Object> args) {
        count(args, 0);
        ensureOwner();
        if (program == 0)
            prepareTriangle();
        glUseProgram(program);
        glBindVertexArray(vertexArray);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        glUseProgram(0);
        return 1;
    }

    private void prepareTriangle() {
        int vertexShader = compile(GL_VERTEX_SHADER, """
                #version 330 core
                layout (location = 0) in vec3 aPosition;
                void main() { gl_Position = vec4(aPosition, 1.0); }
                """);
        int fragmentShader = 0;
        try {
            fragmentShader = compile(GL_FRAGMENT_SHADER, """
                    #version 330 core
                    out vec4 pixel;
                    void main() { pixel = vec4(0.96, 0.75, 0.34, 1.0); }
                    """);
            program = glCreateProgram();
            glAttachShader(program, vertexShader);
            glAttachShader(program, fragmentShader);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException("OpenGL shader link failed: " + glGetProgramInfoLog(program));

            FloatBuffer vertices = BufferUtils.createFloatBuffer(9);
            vertices.put(new float[]{
                    -0.6f, -0.5f, 0f,
                     0.6f, -0.5f, 0f,
                     0.0f,  0.6f, 0f
            }).flip();
            vertexArray = glGenVertexArrays();
            vertexBuffer = glGenBuffers();
            glBindVertexArray(vertexArray);
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
            glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);
            glEnableVertexAttribArray(0);
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        } finally {
            glDeleteShader(vertexShader);
            if (fragmentShader != 0)
                glDeleteShader(fragmentShader);
        }
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("OpenGL shader compilation failed: " + log);
        }
        return shader;
    }

    private int closeWindow(List<Object> args) {
        count(args, 0);
        if (window == 0L) {
            if (glfwInitialized)
                release();
            return 1;
        }
        ensureOwner();
        release();
        return 1;
    }

    private void release() {
        if (window != 0L) {
            if (program != 0) glDeleteProgram(program);
            if (vertexBuffer != 0) glDeleteBuffers(vertexBuffer);
            if (vertexArray != 0) glDeleteVertexArrays(vertexArray);
            program = vertexBuffer = vertexArray = 0;
            glfwDestroyWindow(window);
            window = 0L;
            GL.setCapabilities(null);
        }
        if (glfwInitialized) {
            glfwTerminate();
            glfwInitialized = false;
        }
        owner = null;
    }

    @Override
    public void close() {
        if (window != 0L && Thread.currentThread() != owner)
            throw new IllegalStateException("GLFW cleanup must run on the owner thread");
        release();
    }
}

package org.owgraphics.runtime;

import dev.oreo.RuntimeExtension;
import java.nio.FloatBuffer;
import java.util.List;
import java.nio.file.Path;
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
    private boolean contextReady;
    private boolean cursorInitialized;
    private double previousCursorX;
    private double previousCursorY;
    private double accumulatedScroll;
    private int program;
    private int vertexArray;
    private int vertexBuffer;
    private final OpenGlResources resources = new OpenGlResources();

    @Override
    public boolean supports(String operation) {
        return switch (operation) {
            case "graphics.window.open", "graphics.window.poll",
                 "graphics.window.present", "graphics.window.should_close",
                 "graphics.window.close", "graphics.window.request_close",
                 "graphics.input.escape", "graphics.opengl.clear", "graphics.opengl.depth_test", "graphics.opengl.triangle",
                 "graphics.shader.create", "graphics.shader.bind",
                 "graphics.shader.uniform4", "graphics.shader.uniform_mat4",
                 "graphics.shader.uniform_int", "graphics.shader.destroy",
                 "graphics.mesh.create", "graphics.mesh.create_indexed", "graphics.mesh.create_lit_indexed",
                 "graphics.mesh.draw", "graphics.mesh.destroy",
                 "graphics.texture.create_rgba8", "graphics.texture.load_image", "graphics.texture.bind",
                 "graphics.texture.destroy", "graphics.input.key_down",
                 "graphics.input.cursor_delta", "graphics.input.scroll_delta",
                 "graphics.input.mouse_button_down",
                 "graphics.model.parse_obj", "graphics.model.load_obj",
                 "graphics.model.parse_gltf", "graphics.model.load_gltf",
                 "graphics.scene.parse_gltf", "graphics.scene.load_gltf",
                 "graphics.mesh.draw_instanced" -> true;
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
            case "graphics.window.request_close" -> requestClose(args);
            case "graphics.input.escape" -> escapePressed(args);
            case "graphics.input.key_down" -> keyDown(args);
            case "graphics.input.cursor_delta" -> cursorDelta(args);
            case "graphics.input.mouse_button_down" -> mouseButtonDown(args);
            case "graphics.input.scroll_delta" -> scrollDelta(args);
            case "graphics.model.parse_obj" -> parseObj(args);
            case "graphics.model.load_obj" -> loadObj(args);
            case "graphics.model.parse_gltf" -> parseGltf(args);
            case "graphics.model.load_gltf" -> loadGltf(args);
            case "graphics.scene.parse_gltf" -> parseSceneGltf(args);
            case "graphics.scene.load_gltf" -> loadSceneGltf(args);
            case "graphics.opengl.clear" -> clearFrame(args);
            case "graphics.opengl.depth_test" -> configureDepth(args);
            case "graphics.opengl.triangle" -> drawTriangle(args);
            case "graphics.shader.create" -> createShader(args);
            case "graphics.shader.bind" -> bindShader(args);
            case "graphics.shader.uniform4" -> setShaderVec4(args);
            case "graphics.shader.uniform_mat4" -> setShaderMat4(args);
            case "graphics.shader.uniform_int" -> setShaderInt(args);
            case "graphics.shader.destroy" -> destroyShader(args);
            case "graphics.mesh.create" -> createMesh(args);
            case "graphics.mesh.create_indexed" -> createIndexedMesh(args);
            case "graphics.mesh.create_lit_indexed" -> createLitMesh(args);
            case "graphics.mesh.draw" -> drawMesh(args);
            case "graphics.mesh.draw_instanced" -> drawInstanced(args);
            case "graphics.mesh.destroy" -> destroyMesh(args);
            case "graphics.texture.create_rgba8" -> createTexture(args);
            case "graphics.texture.load_image" -> loadImage(args);
            case "graphics.texture.bind" -> bindTexture(args);
            case "graphics.texture.destroy" -> destroyTexture(args);
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
            glfwWindowHint(GLFW_DEPTH_BITS, 24);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            window = glfwCreateWindow(width, height, title, 0L, 0L);
            if (window == 0L)
                throw new IllegalStateException("Unable to create a GLFW OpenGL 3.3 window");
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            contextReady = true;
            glfwSwapInterval(1);
            glfwSetScrollCallback(window, (handle, dx, dy) -> {
                if (Double.isFinite(dy))
                    accumulatedScroll = Math.max(-1000.0, Math.min(1000.0, accumulatedScroll + dy));
            });
            cursorInitialized = false;
            accumulatedScroll = 0.0;
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

    /**
     * Case-sensitive named keys prevent passing unvalidated arbitrary GLFW
     * key codes across the OW/native boundary.
     */
    private int keyDown(List<Object> args) {
        count(args, 1);
        String name = GraphicsArguments.string(args.get(0), "key");
        int key = switch (name) {
            case "ESCAPE" -> GLFW_KEY_ESCAPE;
            case "W" -> GLFW_KEY_W;
            case "A" -> GLFW_KEY_A;
            case "S" -> GLFW_KEY_S;
            case "D" -> GLFW_KEY_D;
            case "LEFT" -> GLFW_KEY_LEFT;
            case "RIGHT" -> GLFW_KEY_RIGHT;
            case "UP" -> GLFW_KEY_UP;
            case "DOWN" -> GLFW_KEY_DOWN;
            case "SPACE" -> GLFW_KEY_SPACE;
            case "LEFT_SHIFT" -> GLFW_KEY_LEFT_SHIFT;
            default -> throw new IllegalArgumentException("Unsupported graphics key: " + name);
        };
        ensureOwner();
        return glfwGetKey(window, key) == GLFW_PRESS ? 1 : 0;
    }

    private int mouseButtonDown(List<Object> args) {
        count(args, 1);
        String name = GraphicsArguments.string(args.get(0), "mouse button");
        int button = switch (name) {
            case "LEFT" -> GLFW_MOUSE_BUTTON_LEFT;
            case "RIGHT" -> GLFW_MOUSE_BUTTON_RIGHT;
            case "MIDDLE" -> GLFW_MOUSE_BUTTON_MIDDLE;
            default -> throw new IllegalArgumentException("Unsupported mouse button: " + name);
        };
        ensureOwner();
        return glfwGetMouseButton(window, button) == GLFW_PRESS ? 1 : 0;
    }

    private List<Double> cursorDelta(List<Object> args) {
        count(args, 0);
        ensureOwner();
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var x = stack.mallocDouble(1);
            var y = stack.mallocDouble(1);
            glfwGetCursorPos(window, x, y);
            double currentX = x.get(0);
            double currentY = y.get(0);
            if (!Double.isFinite(currentX) || !Double.isFinite(currentY))
                throw new IllegalStateException("GLFW returned invalid cursor coordinates");
            if (!cursorInitialized) {
                cursorInitialized = true;
                previousCursorX = currentX;
                previousCursorY = currentY;
                return List.of(0.0, 0.0);
            }
            double dx = currentX - previousCursorX;
            double dy = currentY - previousCursorY;
            previousCursorX = currentX;
            previousCursorY = currentY;
            return List.of(dx, dy);
        }
    }

    private double scrollDelta(List<Object> args) {
        count(args, 0);
        ensureOwner();
        double delta = accumulatedScroll;
        accumulatedScroll = 0.0;
        return delta;
    }

    private List<Object> parseObj(List<Object> args) {
        count(args, 1);
        String source = GraphicsArguments.string(args.get(0), "OBJ source");
        return ObjModelParser.parse(source);
    }

    private List<Object> loadObj(List<Object> args) {
        count(args, 1);
        String filename = GraphicsArguments.string(args.get(0), "OBJ path");
        return ObjModelParser.loadRelative(filename, Path.of("").toAbsolutePath());
    }

    private int clearFrame(List<Object> args) {
        count(args, 3);
        int r = intArg(args, 0, "red");
        int g = intArg(args, 1, "green");
        int b = intArg(args, 2, "blue");
        if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255)
            throw new IllegalArgumentException("RGB channels must be in 0..255");
        ensureOwner();
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var w = stack.mallocInt(1);
            var h = stack.mallocInt(1);
            glfwGetFramebufferSize(window, w, h);
            glViewport(0, 0, w.get(0), h.get(0));
        }
        glClearColor(r / 255f, g / 255f, b / 255f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        return 1;
    }

    /** Opt-in depth testing: older OW 2D demos keep their original behavior. */
    private int configureDepth(List<Object> args) {
        count(args, 1);
        int enabled = GraphicsArguments.integer(args.get(0), "depth enabled");
        if (enabled != 0 && enabled != 1)
            throw new IllegalArgumentException("depth_test accepts only 0 or 1");
        ensureOwner();
        if (enabled == 1) {
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            glClearDepth(1.0);
        } else {
            glDisable(GL_DEPTH_TEST);
        }
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

    private int requestClose(List<Object> args) {
        count(args, 0);
        ensureOwner();
        glfwSetWindowShouldClose(window, true);
        return 1;
    }

    private int createShader(List<Object> args) {
        count(args, 2);
        String vertexSource = GraphicsArguments.shaderSource(args.get(0), "vertexSource");
        String fragmentSource = GraphicsArguments.shaderSource(args.get(1), "fragmentSource");
        ensureOwner();
        return resources.createProgram(vertexSource, fragmentSource);
    }

    private int bindShader(List<Object> args) {
        count(args, 1);
        int handle = GraphicsArguments.integer(args.get(0), "shader handle");
        ensureOwner();
        resources.bindProgram(handle);
        return 1;
    }

    private int setShaderVec4(List<Object> args) {
        count(args, 6);
        int handle = GraphicsArguments.integer(args.get(0), "shader handle");
        String name = GraphicsArguments.string(args.get(1), "uniform name");
        float r = GraphicsArguments.finiteFloat(args.get(2), "r");
        float g = GraphicsArguments.finiteFloat(args.get(3), "g");
        float b = GraphicsArguments.finiteFloat(args.get(4), "b");
        float a = GraphicsArguments.finiteFloat(args.get(5), "a");
        ensureOwner();
        return resources.uniform4(handle, name, r, g, b, a);
    }

    private int setShaderMat4(List<Object> args) {
        count(args, 3);
        int handle = GraphicsArguments.integer(args.get(0), "shader handle");
        String name = GraphicsArguments.string(args.get(1), "uniform name");
        float[] values = GraphicsArguments.matrix4(args.get(2));
        ensureOwner();
        return resources.uniformMatrix4(handle, name, values);
    }

    private int setShaderInt(List<Object> args) {
        count(args, 3);
        int handle = GraphicsArguments.integer(args.get(0), "shader handle");
        String name = GraphicsArguments.string(args.get(1), "uniform name");
        int value = GraphicsArguments.integer(args.get(2), "uniform value");
        ensureOwner();
        return resources.uniformInt(handle, name, value);
    }

    private int destroyShader(List<Object> args) {
        count(args, 1);
        int handle = GraphicsArguments.integer(args.get(0), "shader handle");
        ensureOwner();
        resources.destroyProgram(handle);
        return 1;
    }

    private List<Object> parseGltf(List<Object> args) {
        count(args, 1);
        return GltfModelParser.parse(GraphicsArguments.string(args.get(0), "glTF source"));
    }

    private List<Object> loadGltf(List<Object> args) {
        count(args, 1);
        return GltfModelParser.loadRelative(
                GraphicsArguments.string(args.get(0), "glTF path"), Path.of("").toAbsolutePath());
    }

    /** Decodes a complete static glTF scene on CPU without a GL context. */
    private List<Object> parseSceneGltf(List<Object> args) {
        count(args, 1);
        return GltfSceneParser.parseJson(
                GraphicsArguments.string(args.get(0), "scene JSON"));
    }

    /** Supports project-relative .gltf JSON and binary .glb scene containers. */
    private List<Object> loadSceneGltf(List<Object> args) {
        count(args, 1);
        return GltfSceneParser.loadRelative(
                GraphicsArguments.string(args.get(0), "scene filename"),
                Path.of("").toAbsolutePath());
    }

    private int createMesh(List<Object> args) {
        count(args, 1);
        float[] positions = GraphicsArguments.trianglePositions(args.get(0));
        ensureOwner();
        return resources.createMesh(positions);
    }

    private int createIndexedMesh(List<Object> args) {
        count(args, 3);
        var geometry = GraphicsArguments.indexedGeometry(args.get(0), args.get(1), args.get(2));
        ensureOwner();
        return resources.createIndexedMesh(geometry);
    }

    private int createLitMesh(List<Object> args) {
        count(args, 4);
        var geometry = GraphicsArguments.litGeometry(
                args.get(0), args.get(1), args.get(2), args.get(3));
        ensureOwner();
        return resources.createLitMesh(geometry);
    }

    private int createTexture(List<Object> args) {
        count(args, 3);
        var image = GraphicsArguments.rgba8(args.get(0), args.get(1), args.get(2));
        ensureOwner();
        return resources.createTexture(image);
    }

    private int loadImage(List<Object> args) {
        count(args, 1);
        String filename = GraphicsArguments.string(args.get(0), "image path");
        // Validate and decode on CPU before accessing GL, preventing GPU
        // resource allocation on invalid or oversized files.
        var image = ImageAssetLoader.loadRelative(filename, Path.of("").toAbsolutePath());
        ensureOwner();
        return resources.createTexture(image);
    }

    private int bindTexture(List<Object> args) {
        count(args, 2);
        int handle = GraphicsArguments.integer(args.get(0), "texture handle");
        int slot = GraphicsArguments.integer(args.get(1), "texture slot");
        ensureOwner();
        resources.bindTexture(handle, slot);
        return 1;
    }

    private int destroyTexture(List<Object> args) {
        count(args, 1);
        int handle = GraphicsArguments.integer(args.get(0), "texture handle");
        ensureOwner();
        resources.destroyTexture(handle);
        return 1;
    }

    private int drawMesh(List<Object> args) {
        count(args, 1);
        int handle = GraphicsArguments.integer(args.get(0), "mesh handle");
        ensureOwner();
        resources.drawMesh(handle);
        return 1;
    }

    private int drawInstanced(List<Object> args) {
        count(args, 2);
        int handle = GraphicsArguments.integer(args.get(0), "mesh handle");
        float[] matrices = GraphicsArguments.instanceMatrices(args.get(1));
        ensureOwner();
        resources.drawInstanced(handle, matrices);
        return 1;
    }

    private int destroyMesh(List<Object> args) {
        count(args, 1);
        int handle = GraphicsArguments.integer(args.get(0), "mesh handle");
        ensureOwner();
        resources.destroyMesh(handle);
        return 1;
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
            if (contextReady) {
                resources.releaseAll();
                if (program != 0) glDeleteProgram(program);
                if (vertexBuffer != 0) glDeleteBuffers(vertexBuffer);
                if (vertexArray != 0) glDeleteVertexArrays(vertexArray);
                GL.setCapabilities(null);
            }
            program = vertexBuffer = vertexArray = 0;
            contextReady = false;
            org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(window);
            glfwDestroyWindow(window);
            window = 0L;
            cursorInitialized = false;
            accumulatedScroll = 0.0;
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

package org.owgraphics.runtime;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33.*;

/**
 * Private OpenGL ownership boundary. Handles are interpreter-local, monotonically
 * allocated (never reused after a window reopen) and scoped to its active context.
 * The caller enforces the current GLFW thread and validates all input data.
 */
final class OpenGlResources {
    private record Mesh(int vao, int vbo, int vertexCount) {}

    private final Map<Integer, Integer> programs = new HashMap<>();
    private final Map<Integer, Mesh> meshes = new HashMap<>();
    private int nextHandle = 1;

    private int allocateHandle() {
        if (nextHandle < 1 || nextHandle == Integer.MAX_VALUE)
            throw new IllegalStateException("Graphics resource handle limit reached");
        return nextHandle++;
    }

    private static int compile(int shaderType, String source) {
        int shader = glCreateShader(shaderType);
        if (shader == 0)
            throw new IllegalStateException("OpenGL did not allocate a shader");
        try {
            glShaderSource(shader, source);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                throw new IllegalArgumentException("GLSL compilation failed: "
                        + glGetShaderInfoLog(shader));
            return shader;
        } catch (RuntimeException | Error failure) {
            glDeleteShader(shader);
            throw failure;
        }
    }

    int createProgram(String vertexSource, String fragmentSource) {
        int vertex = compile(GL_VERTEX_SHADER, vertexSource);
        int fragment = 0;
        int linkedProgram = 0;
        boolean success = false;
        try {
            fragment = compile(GL_FRAGMENT_SHADER, fragmentSource);
            linkedProgram = glCreateProgram();
            if (linkedProgram == 0)
                throw new IllegalStateException("OpenGL did not allocate a program");
            glAttachShader(linkedProgram, vertex);
            glAttachShader(linkedProgram, fragment);
            glLinkProgram(linkedProgram);
            if (glGetProgrami(linkedProgram, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalArgumentException("GLSL linking failed: "
                        + glGetProgramInfoLog(linkedProgram));
            int handle = allocateHandle();
            programs.put(handle, linkedProgram);
            success = true;
            return handle;
        } finally {
            glDeleteShader(vertex);
            if (fragment != 0) glDeleteShader(fragment);
            if (!success && linkedProgram != 0) glDeleteProgram(linkedProgram);
        }
    }

    private int requireProgram(int handle) {
        Integer glProgram = programs.get(handle);
        if (glProgram == null)
            throw new IllegalArgumentException("Unknown or destroyed shader handle: " + handle);
        return glProgram;
    }

    void bindProgram(int handle) {
        glUseProgram(requireProgram(handle));
    }

    int uniform4(int handle, String name, float r, float g, float b, float a) {
        if (name.isBlank() || name.length() > 128)
            throw new IllegalArgumentException("Uniform name must contain 1..128 characters");
        int glProgram = requireProgram(handle);
        if (glGetInteger(GL_CURRENT_PROGRAM) != glProgram)
            throw new IllegalStateException("Bind shader " + handle + " before setting uniforms");
        int location = glGetUniformLocation(glProgram, name);
        if (location < 0) return 0; // optimized-out or unknown uniform
        glUniform4f(location, r, g, b, a);
        return 1;
    }

    void destroyProgram(int handle) {
        int program = requireProgram(handle);
        if (glGetInteger(GL_CURRENT_PROGRAM) == program)
            glUseProgram(0);
        glDeleteProgram(program);
        programs.remove(handle);
    }

    int createMesh(float[] positions) {
        int vao = 0;
        int vbo = 0;
        boolean success = false;
        try {
            vao = glGenVertexArrays();
            vbo = glGenBuffers();
            if (vao == 0 || vbo == 0)
                throw new IllegalStateException("OpenGL could not allocate mesh buffers");

            FloatBuffer vertices = BufferUtils.createFloatBuffer(positions.length);
            vertices.put(positions).flip();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);
            glEnableVertexAttribArray(0);
            int handle = allocateHandle();
            meshes.put(handle, new Mesh(vao, vbo, positions.length / 3));
            success = true;
            return handle;
        } finally {
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);
            if (!success) {
                if (vbo != 0) glDeleteBuffers(vbo);
                if (vao != 0) glDeleteVertexArrays(vao);
            }
        }
    }

    private Mesh requireMesh(int handle) {
        Mesh mesh = meshes.get(handle);
        if (mesh == null)
            throw new IllegalArgumentException("Unknown or destroyed mesh handle: " + handle);
        return mesh;
    }

    void drawMesh(int handle) {
        Mesh mesh = requireMesh(handle);
        if (glGetInteger(GL_CURRENT_PROGRAM) == 0)
            throw new IllegalStateException("Bind a shader before drawing a mesh");
        glBindVertexArray(mesh.vao());
        try {
            glDrawArrays(GL_TRIANGLES, 0, mesh.vertexCount());
        } finally {
            glBindVertexArray(0);
        }
    }

    void destroyMesh(int handle) {
        Mesh mesh = requireMesh(handle);
        glDeleteBuffers(mesh.vbo());
        glDeleteVertexArrays(mesh.vao());
        meshes.remove(handle);
    }

    void releaseAll() {
        glUseProgram(0);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        for (Mesh mesh : meshes.values()) {
            glDeleteBuffers(mesh.vbo());
            glDeleteVertexArrays(mesh.vao());
        }
        meshes.clear();
        for (int program : programs.values())
            glDeleteProgram(program);
        programs.clear();
    }
}

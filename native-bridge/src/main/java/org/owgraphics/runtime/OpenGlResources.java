package org.owgraphics.runtime;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ByteBuffer;
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
    private record Mesh(int vao, int vbo, int ebo, int vertexCount, int indexCount) {}

    private final Map<Integer, Integer> programs = new HashMap<>();
    private final Map<Integer, Mesh> meshes = new HashMap<>();
    private final Map<Integer, Integer> instanceBuffers = new HashMap<>();
    private final Map<Integer, Integer> textures = new HashMap<>();
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

    int uniformInt(int handle, String name, int value) {
        int location = uniformLocation(handle, name);
        if (location < 0) return 0;
        glUniform1i(location, value);
        return 1;
    }

    int uniformMatrix4(int handle, String name, float[] values) {
        int location = uniformLocation(handle, name);
        if (location < 0) return 0;
        FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
        matrix.put(values).flip();
        glUniformMatrix4fv(location, false, matrix);
        return 1;
    }

    int uniformMatrix4Array(int handle,String name,float[] flattened) {
        if(name==null || name.isBlank() || name.length()>120
                || flattened.length<16 || flattened.length>512 || flattened.length%16!=0)
            throw new IllegalArgumentException("Joint uniform array must have 1..32 mat4 matrices");
        int location=uniformLocation(handle,name+"[0]");
        if(location<0)return 0;
        FloatBuffer values=BufferUtils.createFloatBuffer(flattened.length);
        values.put(flattened).flip();
        glUniformMatrix4fv(location,false,values);
        return 1;
    }

    private int uniformLocation(int handle, String name) {
        if (name.isBlank() || name.length() > 128)
            throw new IllegalArgumentException("Uniform name must contain 1..128 characters");
        int program = requireProgram(handle);
        if (glGetInteger(GL_CURRENT_PROGRAM) != program)
            throw new IllegalStateException("Bind shader " + handle + " before setting uniforms");
        return glGetUniformLocation(program, name);
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
            meshes.put(handle, new Mesh(vao, vbo, 0, positions.length / 3, 0));
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

    int createIndexedMesh(GraphicsArguments.IndexedGeometry geometry) {
        return uploadIndexedMesh(geometry, null);
    }

    int createLitMesh(GraphicsArguments.LitGeometry lit) {
        return uploadIndexedMesh(lit.base(), lit.normals());
    }

    int createSkinnedMesh(GraphicsArguments.SkinnedGeometry skin) {
        return uploadIndexedMesh(skin.lit().base(),skin.lit().normals(),
                skin.joints(),skin.weights());
    }

    /**
     * Vertex layout: xyz at location 0, UV at 1, optional normal xyz at 2.
     * Each face of a box can have separate vertex normals while still sharing
     * corner positions only within that face. Existing 5-float meshes remain valid.
     */
    private int uploadIndexedMesh(GraphicsArguments.IndexedGeometry geometry, float[] normals) {
        return uploadIndexedMesh(geometry,normals,null,null);
    }

    private int uploadIndexedMesh(GraphicsArguments.IndexedGeometry geometry,float[] normals,
                                  float[] joints,float[] weights) {
        int vao = 0, vbo = 0, ebo = 0;
        boolean success = false;
        try {
            vao = glGenVertexArrays();
            vbo = glGenBuffers();
            ebo = glGenBuffers();
            if (vao == 0 || vbo == 0 || ebo == 0)
                throw new IllegalStateException("OpenGL could not allocate indexed mesh buffers");

            float[] positions = geometry.positions();
            float[] uv = geometry.uvs();
            int[] indices = geometry.indices();
            int vertexCount = positions.length / 3;
            int stride = normals == null ? 5 : joints == null ? 8 : 16;
            FloatBuffer interleaved = BufferUtils.createFloatBuffer(vertexCount * stride);
            for (int i = 0; i < vertexCount; i++) {
                interleaved.put(positions[i * 3]);
                interleaved.put(positions[i * 3 + 1]);
                interleaved.put(positions[i * 3 + 2]);
                interleaved.put(uv[i * 2]);
                interleaved.put(uv[i * 2 + 1]);
                if (normals != null) {
                    interleaved.put(normals[i * 3]);
                    interleaved.put(normals[i * 3 + 1]);
                    interleaved.put(normals[i * 3 + 2]);
                }
                if(joints!=null) {
                    for(int c=0;c<4;c++)interleaved.put(joints[i*4+c]);
                    for(int c=0;c<4;c++)interleaved.put(weights[i*4+c]);
                }
            }
            interleaved.flip();
            IntBuffer elements = BufferUtils.createIntBuffer(indices.length);
            elements.put(indices).flip();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, interleaved, GL_STATIC_DRAW);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride * Float.BYTES, 0L);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(1, 2, GL_FLOAT, false, stride * Float.BYTES, 3L * Float.BYTES);
            glEnableVertexAttribArray(1);
            if (normals != null) {
                glVertexAttribPointer(2, 3, GL_FLOAT, false, stride * Float.BYTES, 5L * Float.BYTES);
                glEnableVertexAttribArray(2);
            }
            if(joints!=null) {
                // Attribute slots 3..6 are reserved for optional instance mat4.
                // Slots 7/8 store four float joint IDs (integer-valued) and weights.
                glVertexAttribPointer(7,4,GL_FLOAT,false,stride*Float.BYTES,8L*Float.BYTES);
                glEnableVertexAttribArray(7);
                glVertexAttribPointer(8,4,GL_FLOAT,false,stride*Float.BYTES,12L*Float.BYTES);
                glEnableVertexAttribArray(8);
            }
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, elements, GL_STATIC_DRAW);
            int handle = allocateHandle();
            meshes.put(handle, new Mesh(vao, vbo, ebo, vertexCount, indices.length));
            success = true;
            return handle;
        } finally {
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            if (!success) {
                if (ebo != 0) glDeleteBuffers(ebo);
                if (vbo != 0) glDeleteBuffers(vbo);
                if (vao != 0) glDeleteVertexArrays(vao);
            }
        }
    }

    int createTexture(GraphicsArguments.ImageData image) {
        int texture = glGenTextures();
        if (texture == 0)
            throw new IllegalStateException("OpenGL failed to allocate a texture");
        int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        int alignment = glGetInteger(GL_UNPACK_ALIGNMENT);
        boolean success = false;
        try {
            ByteBuffer pixelBuffer = BufferUtils.createByteBuffer(image.rgba().length);
            pixelBuffer.put(image.rgba()).flip();
            glBindTexture(GL_TEXTURE_2D, texture);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, image.width(), image.height(),
                    0, GL_RGBA, GL_UNSIGNED_BYTE, pixelBuffer);
            int handle = allocateHandle();
            textures.put(handle, texture);
            success = true;
            return handle;
        } finally {
            glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);
            glBindTexture(GL_TEXTURE_2D, previous);
            if (!success) glDeleteTextures(texture);
        }
    }

    private int requireTexture(int handle) {
        Integer texture = textures.get(handle);
        if (texture == null)
            throw new IllegalArgumentException("Unknown or destroyed texture handle: " + handle);
        return texture;
    }

    void bindTexture(int handle, int slot) {
        int texture = requireTexture(handle);
        if (slot < 0 || slot >= glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS))
            throw new IllegalArgumentException("Texture slot outside supported range");
        glActiveTexture(GL_TEXTURE0 + slot);
        glBindTexture(GL_TEXTURE_2D, texture);
    }

    void destroyTexture(int handle) {
        int texture = requireTexture(handle);
        glDeleteTextures(texture);
        textures.remove(handle);
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
            if (mesh.indexCount() > 0)
                glDrawElements(GL_TRIANGLES, mesh.indexCount(), GL_UNSIGNED_INT, 0L);
            else
                glDrawArrays(GL_TRIANGLES, 0, mesh.vertexCount());
        } finally {
            glBindVertexArray(0);
        }
    }

    /**
     * Batch identical geometry in one OpenGL draw call using an interleaved
     * mat4 instance buffer at vertex attributes 3, 4, 5 and 6.
     * The buffer is reused across frames, and freed with its parent mesh.
     * Shaders must explicitly opt into layout(location=3) in mat4 aModel.
     */
    void drawInstanced(int handle, float[] matrices) {
        Mesh mesh = requireMesh(handle);
        if (glGetInteger(GL_CURRENT_PROGRAM) == 0)
            throw new IllegalStateException("Bind shader before instanced draw");
        int buffer = instanceBuffers.getOrDefault(handle, 0);
        if (buffer == 0) {
            buffer = glGenBuffers();
            if (buffer == 0)
                throw new IllegalStateException("Cannot allocate instance matrix buffer");
            instanceBuffers.put(handle, buffer);
        }
        FloatBuffer bufferData = BufferUtils.createFloatBuffer(matrices.length);
        bufferData.put(matrices).flip();
        glBindVertexArray(mesh.vao());
        try {
            glBindBuffer(GL_ARRAY_BUFFER, buffer);
            glBufferData(GL_ARRAY_BUFFER, bufferData, GL_DYNAMIC_DRAW);
            for (int i=0;i<4;i++) {
                int attribute = 3 + i;
                glEnableVertexAttribArray(attribute);
                glVertexAttribPointer(attribute, 4, GL_FLOAT, false,
                        16 * Float.BYTES, (long)(i * 4 * Float.BYTES));
                glVertexAttribDivisor(attribute, 1);
            }
            int instanceCount = matrices.length / 16;
            if (mesh.indexCount() > 0)
                glDrawElementsInstanced(GL_TRIANGLES, mesh.indexCount(), GL_UNSIGNED_INT, 0L, instanceCount);
            else
                glDrawArraysInstanced(GL_TRIANGLES, 0, mesh.vertexCount(), instanceCount);
        } finally {
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }
    }

    void destroyMesh(int handle) {
        Mesh mesh = requireMesh(handle);
        Integer instanceBuffer = instanceBuffers.remove(handle);
        if (instanceBuffer != null) glDeleteBuffers(instanceBuffer);
        if (mesh.ebo() != 0) glDeleteBuffers(mesh.ebo());
        glDeleteBuffers(mesh.vbo());
        glDeleteVertexArrays(mesh.vao());
        meshes.remove(handle);
    }

    void releaseAll() {
        glUseProgram(0);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        for (Mesh mesh : meshes.values()) {
            if (mesh.ebo() != 0) glDeleteBuffers(mesh.ebo());
            glDeleteBuffers(mesh.vbo());
            glDeleteVertexArrays(mesh.vao());
        }
        meshes.clear();
        for (int buffer : instanceBuffers.values()) glDeleteBuffers(buffer);
        instanceBuffers.clear();
        for (int texture : textures.values())
            glDeleteTextures(texture);
        textures.clear();
        for (int program : programs.values())
            glDeleteProgram(program);
        programs.clear();
    }
}

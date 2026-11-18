package org.owgraphics.runtime;

import java.util.List;

/**
 * Pure validation at the OW-to-native boundary. No GLFW/OpenGL calls.
 * All validation is safe to run in CI without a display or graphics driver.
 */
final class GraphicsArguments {
    private static final int MAX_SHADER_CHARS = 65_536;
    private static final int MAX_POSITION_FLOATS = 300_000;

    private GraphicsArguments() {}

    static void arity(List<?> args, int expected) {
        if (args.size() != expected)
            throw new IllegalArgumentException("Expected " + expected
                    + " graphics argument(s), got " + args.size());
    }

    static int integer(Object value, String name) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() < Integer.MIN_VALUE
                || n.doubleValue() > Integer.MAX_VALUE
                || n.doubleValue() != n.intValue())
            throw new IllegalArgumentException(name + " must be a 32-bit integer");
        return n.intValue();
    }

    static String string(Object value, String name) {
        if (!(value instanceof String s))
            throw new IllegalArgumentException(name + " must be a string");
        return s;
    }

    static String shaderSource(Object value, String name) {
        String source = string(value, name);
        if (source.isBlank() || source.length() > MAX_SHADER_CHARS)
            throw new IllegalArgumentException(name + " must contain 1.."
                    + MAX_SHADER_CHARS + " nonblank characters");
        return source;
    }

    static float finiteFloat(Object value, String name) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || Math.abs(n.doubleValue()) > Float.MAX_VALUE)
            throw new IllegalArgumentException(name + " must be a finite 32-bit float");
        return n.floatValue();
    }

    /**
     * OW arrays arrive as List<?> from Interpreter.evalExpr(ArrayLit).
     * Nonindexed GL_TRIANGLES: 3 coordinates * 3 vertices per triangle.
     */
    static float[] trianglePositions(Object value) {
        if (!(value instanceof List<?> coordinates))
            throw new IllegalArgumentException("positions must be an OW array");
        if (coordinates.size() < 9 || coordinates.size() > MAX_POSITION_FLOATS
                || coordinates.size() % 9 != 0)
            throw new IllegalArgumentException("positions must contain 9.."
                    + MAX_POSITION_FLOATS + " numbers, divisible by 9 (xyz triangles)");
        float[] result = new float[coordinates.size()];
        for (int i = 0; i < result.length; i++)
            result[i] = finiteFloat(coordinates.get(i), "positions[" + i + "]");
        return result;
    }

    /** A compact CPU-side interleaved position/UV representation before GPU upload. */
    record IndexedGeometry(float[] positions, float[] uvs, int[] indices) {}

    static IndexedGeometry indexedGeometry(Object vertices, Object textureCoordinates, Object elements) {
        if (!(vertices instanceof List<?> positions))
            throw new IllegalArgumentException("positions must be an OW array");
        if (positions.size() < 9 || positions.size() > MAX_POSITION_FLOATS || positions.size() % 3 != 0)
            throw new IllegalArgumentException("indexed positions must be xyz triples, 3..100000 vertices");
        int vertexCount = positions.size() / 3;
        if (!(textureCoordinates instanceof List<?> uv) || uv.size() != vertexCount * 2)
            throw new IllegalArgumentException("uv array must have exactly two coordinates per vertex");
        if (!(elements instanceof List<?> indexList) || indexList.size() < 3
                || indexList.size() > MAX_POSITION_FLOATS || indexList.size() % 3 != 0)
            throw new IllegalArgumentException("indices must be a nonempty array of triangle triples");
        float[] xyz = new float[positions.size()];
        float[] coords = new float[uv.size()];
        int[] indices = new int[indexList.size()];
        for (int i = 0; i < xyz.length; i++)
            xyz[i] = finiteFloat(positions.get(i), "positions[" + i + "]");
        for (int i = 0; i < coords.length; i++)
            coords[i] = finiteFloat(uv.get(i), "uv[" + i + "]");
        for (int i = 0; i < indices.length; i++) {
            int index = integer(indexList.get(i), "indices[" + i + "]");
            if (index < 0 || index >= vertexCount)
                throw new IllegalArgumentException("indices[" + i + "] outside vertex range");
            indices[i] = index;
        }
        return new IndexedGeometry(xyz, coords, indices);
    }

    static float[] matrix4(Object value) {
        if (!(value instanceof List<?> matrix) || matrix.size() != 16)
            throw new IllegalArgumentException("mat4 uniform requires exactly 16 floats (column-major)");
        float[] floats = new float[16];
        for (int i = 0; i < floats.length; i++)
            floats[i] = finiteFloat(matrix.get(i), "matrix[" + i + "]");
        return floats;
    }

    record ImageData(int width, int height, byte[] rgba) {}

    /**
     * CPU input: row-major RGBA8, bottom row first (OpenGL origin convention).
     * Keep an explicit size ceiling to prevent an unbounded OW array allocation.
     */
    static ImageData rgba8(Object rawWidth, Object rawHeight, Object rawPixels) {
        int width = integer(rawWidth, "width");
        int height = integer(rawHeight, "height");
        if (width < 1 || height < 1 || width > 2048 || height > 2048)
            throw new IllegalArgumentException("texture dimensions must be in 1..2048");
        if (!(rawPixels instanceof List<?> pixels) || pixels.size() != (long) width * height * 4)
            throw new IllegalArgumentException("RGBA8 texture requires width*height*4 channels");
        byte[] data = new byte[pixels.size()];
        for (int i = 0; i < pixels.size(); i++) {
            int channel = integer(pixels.get(i), "rgba[" + i + "]");
            if (channel < 0 || channel > 255)
                throw new IllegalArgumentException("rgba[" + i + "] must be 0..255");
            data[i] = (byte) channel;
        }
        return new ImageData(width, height, data);
    }

    /** Per-vertex normals are required to shade edges of an indexed cube. */
    record LitGeometry(IndexedGeometry base, float[] normals) {}

    static LitGeometry litGeometry(Object vertices, Object textureCoordinates,
                                   Object rawNormals, Object elements) {
        IndexedGeometry geometry = indexedGeometry(vertices, textureCoordinates, elements);
        if (!(rawNormals instanceof List<?> values)
                || values.size() != geometry.positions().length)
            throw new IllegalArgumentException("normals must have three components per vertex");
        float[] normals = new float[values.size()];
        for (int i = 0; i < normals.length; i++)
            normals[i] = finiteFloat(values.get(i), "normals[" + i + "]");
        for (int i = 0; i < normals.length; i += 3) {
            float lengthSq = normals[i] * normals[i] + normals[i + 1] * normals[i + 1]
                    + normals[i + 2] * normals[i + 2];
            if (!Float.isFinite(lengthSq) || lengthSq < 0.000001f)
                throw new IllegalArgumentException("normal at vertex " + (i / 3) + " is zero-length");
        }
        return new LitGeometry(geometry, normals);
    }

}

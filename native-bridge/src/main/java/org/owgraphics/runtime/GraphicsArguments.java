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
}

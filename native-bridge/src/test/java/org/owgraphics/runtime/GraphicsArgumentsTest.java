package org.owgraphics.runtime;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GraphicsArgumentsTest {
    @Test public void acceptsMultipleTrianglesFromOwArrays() {
        float[] triangle = GraphicsArguments.trianglePositions(List.of(
            -0.5, -0.5, 0.0, 0.5, -0.5, 0.0, 0.0, 0.5, 0.0));
        assertEquals(9, triangle.length);
        assertEquals(-0.5f, triangle[0], 0.0001f);
        assertEquals(0.5f, triangle[7], 0.0001f);
    }

    @Test public void rejectsMalformedVertexPositions() {
        assertThrows(IllegalArgumentException.class,
            () -> GraphicsArguments.trianglePositions("not-array"));
        assertThrows(IllegalArgumentException.class,
            () -> GraphicsArguments.trianglePositions(List.of(0, 1, 2)));
        assertThrows(IllegalArgumentException.class,
            () -> GraphicsArguments.trianglePositions(List.of(0,0,0, 1,0,0, 0,1,"bad")));
        assertThrows(IllegalArgumentException.class,
            () -> GraphicsArguments.trianglePositions(List.of(0,0,0, 1,0,0, 0,1,Double.NaN)));
        assertThrows(IllegalArgumentException.class,
            () -> GraphicsArguments.trianglePositions(List.of(0,0,0, 1,0,0, 0,1,Double.POSITIVE_INFINITY)));
    }

    @Test public void rejectsInvalidShaderSourceAndNumbers() {
        assertThrows(IllegalArgumentException.class,
            () -> GraphicsArguments.shaderSource("   ", "vertex"));
        assertThrows(IllegalArgumentException.class,
            () -> GraphicsArguments.shaderSource(1, "vertex"));
        assertThrows(IllegalArgumentException.class,
            () -> GraphicsArguments.finiteFloat(Double.NaN, "alpha"));
        assertThrows(IllegalArgumentException.class,
            () -> GraphicsArguments.finiteFloat(Double.MAX_VALUE, "alpha"));
        assertThrows(IllegalArgumentException.class,
            () -> GraphicsArguments.integer(1.5, "id"));
    }

    @Test public void convertsOwNumbersAndValidatesArity() {
        assertEquals(42, GraphicsArguments.integer(42L, "id"));
        assertEquals(0.2f, GraphicsArguments.finiteFloat(0.2, "r"), 0.0001f);
        GraphicsArguments.arity(List.of("hello"), 1);
        assertThrows(IllegalArgumentException.class,
            () -> GraphicsArguments.arity(List.of("hello"), 2));
    }
}

package org.owgraphics.runtime;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class Graphics3DArgumentsTest {
    private static final List<Double> XYZ = List.of(
            -0.5, -0.5, 0.0, 0.5, -0.5, 0.0, 0.0, 0.5, 0.0);
    private static final List<Double> UV = List.of(0.0, 0.0, 1.0, 0.0, 0.5, 1.0);
    private static final List<Integer> INDICES = List.of(0, 1, 2);

    @Test public void acceptsLitVerticesWithFiniteNormals() {
        var geometry = GraphicsArguments.litGeometry(XYZ, UV,
                List.of(0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0), INDICES);
        assertEquals(3, geometry.base().positions().length / 3);
        assertEquals(9, geometry.normals().length);
        assertEquals(1.0f, geometry.normals()[2], 0.001f);
    }

    @Test public void refusesMalformedOrZeroNormals() {
        assertThrows(IllegalArgumentException.class, () -> GraphicsArguments.litGeometry(
                XYZ, UV, List.of(0.0), INDICES));
        assertThrows(IllegalArgumentException.class, () -> GraphicsArguments.litGeometry(
                XYZ, UV, List.of(0.0,0.0,0.0, 0.0,0.0,1.0, 0.0,0.0,1.0), INDICES));
        assertThrows(IllegalArgumentException.class, () -> GraphicsArguments.litGeometry(
                XYZ, UV, List.of(0.0,0.0,Double.POSITIVE_INFINITY,
                        0.0,0.0,1.0, 0.0,0.0,1.0), INDICES));
    }

    @Test public void depthTestRejectsInvalidArgumentsWithoutGlContext() {
        var provider = new GraphicsRuntimeExtension();
        assertTrue(provider.supports("graphics.opengl.depth_test"));
        assertTrue(provider.supports("graphics.mesh.create_lit_indexed"));
        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("graphics.opengl.depth_test", List.of(2)));
        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("graphics.opengl.depth_test", List.of(-1)));
        assertThrows(IllegalStateException.class,
                () -> provider.invoke("graphics.opengl.depth_test", List.of(1)));
        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("graphics.mesh.create_lit_indexed",
                        List.of(XYZ, UV, List.of(1), INDICES)));
    }
}

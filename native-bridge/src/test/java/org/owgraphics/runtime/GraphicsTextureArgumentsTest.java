package org.owgraphics.runtime;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GraphicsTextureArgumentsTest {
    private static List<Double> quadPositions() {
        return List.of(-0.8, -0.8, 0.0, 0.8, -0.8, 0.0, 0.8, 0.8, 0.0, -0.8, 0.8, 0.0);
    }
    private static List<Double> quadUvs() {
        return List.of(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0);
    }

    @Test public void acceptsIndexedGeometryWithVertexUvs() {
        var mesh = GraphicsArguments.indexedGeometry(quadPositions(), quadUvs(),
                List.of(0, 1, 2, 2, 3, 0));
        assertEquals(12, mesh.positions().length);
        assertEquals(8, mesh.uvs().length);
        assertArrayEquals(new int[] {0, 1, 2, 2, 3, 0}, mesh.indices());
    }

    @Test public void refusesBadUvsIndicesAndCoordinates() {
        assertThrows(IllegalArgumentException.class, () ->
                GraphicsArguments.indexedGeometry(quadPositions(), List.of(0.0), List.of(0,1,2)));
        assertThrows(IllegalArgumentException.class, () ->
                GraphicsArguments.indexedGeometry(quadPositions(), quadUvs(), List.of(0,1,4)));
        assertThrows(IllegalArgumentException.class, () ->
                GraphicsArguments.indexedGeometry(quadPositions(), quadUvs(), List.of(0,1,2.5)));
        assertThrows(IllegalArgumentException.class, () ->
                GraphicsArguments.indexedGeometry(quadPositions(), quadUvs(), List.of(0,1)));
        assertThrows(IllegalArgumentException.class, () ->
                GraphicsArguments.indexedGeometry(quadPositions(), quadUvs(), List.of(-1,1,2)));
        assertThrows(IllegalArgumentException.class, () ->
                GraphicsArguments.indexedGeometry(List.of(Double.NaN,0,0,1,0,0,0,1,0),
                        List.of(0,0,1,0,0,1),List.of(0,1,2)));
    }

    @Test public void validatesRgba8AndBoundedDimensions() {
        var image = GraphicsArguments.rgba8(1, 2,
                List.of(255, 0, 0, 255, 0, 255, 0, 255));
        assertEquals(1, image.width());
        assertEquals(2, image.height());
        assertEquals(8, image.rgba().length);
        assertEquals(255, image.rgba()[0] & 0xFF);
        assertThrows(IllegalArgumentException.class,
                () -> GraphicsArguments.rgba8(0, 2, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> GraphicsArguments.rgba8(2049, 1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> GraphicsArguments.rgba8(1, 1, List.of(0, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> GraphicsArguments.rgba8(1, 1, List.of(0, 0, 0, 256)));
        assertThrows(IllegalArgumentException.class,
                () -> GraphicsArguments.rgba8(1, 1, List.of(0, 0, 0, 1.5)));
    }

    @Test public void validatesColumnMajorMatrix() {
        var values = Arrays.asList(1.0,0.0,0.0,0.0, 0.0,1.0,0.0,0.0,
                0.0,0.0,1.0,0.0, 0.5,-0.25,0.0,1.0);
        var matrix = GraphicsArguments.matrix4(values);
        assertEquals(16, matrix.length);
        assertEquals(0.5f, matrix[12], 0.0001f);
        assertThrows(IllegalArgumentException.class, () -> GraphicsArguments.matrix4(List.of(1,2)));
        var malformed = new java.util.ArrayList<>(values);
        malformed.set(4, Double.POSITIVE_INFINITY);
        assertThrows(IllegalArgumentException.class, () -> GraphicsArguments.matrix4(malformed));
    }
}

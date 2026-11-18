package org.owgraphics.runtime;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class GraphicsRuntimeExtensionTest {
    @Test public void onlyKnownOperationsAreSupported() {
        var provider = new GraphicsRuntimeExtension();
        assertTrue(provider.supports("graphics.window.open"));
        assertTrue(provider.supports("graphics.opengl.triangle"));
        assertFalse(provider.supports("graphics.vulkan.draw"));
        assertFalse(provider.supports("night.security.enable"));
    }

    @Test public void drawingWithoutWindowFailsSafely() {
        var provider = new GraphicsRuntimeExtension();
        assertThrows(IllegalStateException.class,
                () -> provider.invoke("graphics.opengl.triangle", List.of()));
    }

    @Test public void windowDimensionsValidatedBeforeNativeInitialization() {
        var provider = new GraphicsRuntimeExtension();
        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("graphics.window.open", List.of(0, 480, "test", "opengl")));
        assertThrows(UnsupportedOperationException.class,
                () -> provider.invoke("graphics.window.open", List.of(640, 480, "test", "vulkan")));
    }

    @Test public void argumentArityAndValuesAreValidated() {
        var provider = new GraphicsRuntimeExtension();
        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("graphics.opengl.clear", List.of(1, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("graphics.opengl.clear", List.of(1, 2, 999)));
        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("graphics.window.open", List.of(640, 480, "test", "opengl", 0)));
    }
}

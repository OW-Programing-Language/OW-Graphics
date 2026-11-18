package org.owgraphics.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ObjModelParserTest {
    private static final String QUAD = """
            v -0.5 -0.5 0
            v 0.5 -0.5 0
            v 0.5 0.5 0
            v -0.5 0.5 0
            vt 0 0
            vt 1 0
            vt 1 1
            vt 0 1
            f 1/1 2/2 3/3 4/4
            """;

    @SuppressWarnings("unchecked")
    @Test public void triangulatesQuadsAndComputesFaceNormals() {
        List<Object> data = ObjModelParser.parse(QUAD);
        assertEquals(4, data.size());
        assertEquals(18, ((List<?>) data.get(0)).size());
        assertEquals(12, ((List<?>) data.get(1)).size());
        assertEquals(18, ((List<?>) data.get(2)).size());
        assertEquals(List.of(0,1,2,3,4,5), data.get(3));
        assertEquals(1.0, (Double) ((List<?>) data.get(2)).get(2), 0.00001);
    }

    @Test public void negativeReferencesAndExplicitNormals() {
        String obj = """
                v 0 0 0
                v 1 0 0
                v 0 1 0
                vt 0 0
                vt 1 0
                vt 0 1
                vn 0 0 -1
                f -3/-3/-1 -2/-2/-1 -1/-1/-1
                """;
        var geometry = ObjModelParser.parse(obj);
        assertEquals(9, ((List<?>) geometry.get(0)).size());
        assertEquals(-1.0, (Double) ((List<?>) geometry.get(2)).get(2), 0.0001);
    }

    @Test public void rejectsInvalidGeometryAndIndices() {
        assertThrows(IllegalArgumentException.class, () -> ObjModelParser.parse("v 0 0 0"));
        assertThrows(IllegalArgumentException.class, () -> ObjModelParser.parse(
                "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 0 2 3\n"));
        assertThrows(IllegalArgumentException.class, () -> ObjModelParser.parse(
                "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 4\n"));
        assertThrows(IllegalArgumentException.class, () -> ObjModelParser.parse(
                "v 0 0 0\nv 1 0 0\nv 2 0 0\nf 1 2 3\n"));
        assertThrows(IllegalArgumentException.class, () -> ObjModelParser.parse(
                "v NaN 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"));
        assertThrows(IllegalArgumentException.class, () -> ObjModelParser.parse(
                "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1//1 2 3\n"));
    }

    @Test public void enforcesPathSandboxAndReadsSmallObj() throws Exception {
        Path dir = Files.createTempDirectory("owgraphics-obj");
        Path valid = dir.resolve("demo.obj");
        Path outside = Files.createTempFile("owgraphics-outside", ".obj");
        try {
            Files.writeString(valid, QUAD);
            Files.writeString(outside, QUAD);
            assertEquals(6, ((List<?>) ObjModelParser.loadRelative("demo.obj", dir).get(3)).size());
            assertThrows(IllegalArgumentException.class,
                    () -> ObjModelParser.loadRelative("../other.obj", dir));
            assertThrows(IllegalArgumentException.class,
                    () -> ObjModelParser.loadRelative(outside.toString(), dir));
            assertThrows(IllegalArgumentException.class,
                    () -> ObjModelParser.loadRelative("missing.png", dir));
            Path escape = dir.resolve("escape.obj");
            try {
                Files.createSymbolicLink(escape, outside);
                assertThrows(IllegalArgumentException.class,
                        () -> ObjModelParser.loadRelative("escape.obj", dir));
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
                // Windows tests without developer mode may not support symlinks.
            }
        } finally {
            Files.deleteIfExists(valid);
            Files.deleteIfExists(dir.resolve("escape.obj"));
            Files.deleteIfExists(outside);
            Files.deleteIfExists(dir);
        }
    }
}

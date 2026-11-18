package org.owgraphics.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic, bounded Wavefront OBJ decoder. No GPU/GLFW calls and no native
 * pointers escape into OW. Returns [XYZ, UV, normals, indices] as OW arrays.
 *
 * Supports v, vt, vn, f, positive/negative OBJ indices and fan triangulation
 * of convex polygons. Material libraries, smoothing groups and concave polygon
 * triangulation are outside this intentionally narrow first importer.
 */
final class ObjModelParser {
    static final int MAX_TEXT_BYTES = 1_048_576;
    private static final int MAX_RENDER_VERTICES = 100_000;
    private record FaceRef(int position, int texCoord, int normal) {}

    private ObjModelParser() {}

    static List<Object> loadRelative(String filename, Path root) {
        if (filename == null || filename.isBlank() || filename.length() > 4096
                || !filename.toLowerCase(Locale.ROOT).endsWith(".obj"))
            throw new IllegalArgumentException("OBJ path must be a relative .obj filename");
        Path requested;
        try {
            requested = Path.of(filename);
        } catch (RuntimeException invalidPath) {
            throw new IllegalArgumentException("Invalid OBJ file path", invalidPath);
        }
        if (requested.isAbsolute())
            throw new IllegalArgumentException("OBJ path must be relative to the working directory");
        for (Path part : requested)
            if (part.toString().equals(".."))
                throw new IllegalArgumentException("Parent directory traversal is forbidden");
        try {
            Path realRoot = root.toRealPath();
            Path realFile = realRoot.resolve(requested).toRealPath();
            if (!realFile.startsWith(realRoot) || !Files.isRegularFile(realFile))
                throw new IllegalArgumentException("OBJ must be a regular file inside the project directory");
            long size = Files.size(realFile);
            if (size > MAX_TEXT_BYTES)
                throw new IllegalArgumentException("OBJ exceeds the 1 MiB file limit");
            return parse(Files.readString(realFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read local OBJ: " + filename, e);
        }
    }

    private static double finite(String text, String name) {
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + name + ": " + text, exception);
        }
        if (!Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE)
            throw new IllegalArgumentException(name + " must be a finite float");
        return value;
    }

    private static int index(String value, int count, String context) {
        int i;
        try {
            i = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid OBJ " + context + " index", exception);
        }
        if (i == 0 || i == Integer.MIN_VALUE)
            throw new IllegalArgumentException("OBJ indexes start at 1 or -1");
        int actual = i > 0 ? i - 1 : count + i;
        if (actual < 0 || actual >= count)
            throw new IllegalArgumentException("OBJ " + context + " index out of range");
        return actual;
    }

    private static FaceRef reference(String value, int vertexCount, int uvCount, int normalCount) {
        String[] parts = value.split("/", -1);
        if (parts.length < 1 || parts.length > 3 || parts[0].isBlank())
            throw new IllegalArgumentException("OBJ face corner must be v, v/vt or v/vt/vn");
        int vertex = index(parts[0], vertexCount, "vertex");
        int tex = parts.length > 1 && !parts[1].isBlank() ? index(parts[1], uvCount, "UV") : -1;
        int normal = parts.length > 2 && !parts[2].isBlank()
                ? index(parts[2], normalCount, "normal") : -1;
        return new FaceRef(vertex, tex, normal);
    }

    private static double[] faceNormal(List<FaceRef> refs, List<double[]> positions) {
        double[] a = positions.get(refs.get(0).position());
        double[] b = positions.get(refs.get(1).position());
        double[] c = positions.get(refs.get(2).position());
        double ax = b[0] - a[0], ay = b[1] - a[1], az = b[2] - a[2];
        double bx = c[0] - a[0], by = c[1] - a[1], bz = c[2] - a[2];
        double nx = ay * bz - az * by;
        double ny = az * bx - ax * bz;
        double nz = ax * by - ay * bx;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!Double.isFinite(len) || len < 1e-12)
            throw new IllegalArgumentException("Degenerate or collinear OBJ face");
        return new double[]{nx / len, ny / len, nz / len};
    }

    static List<Object> parse(String source) {
        if (source == null || source.isBlank() || source.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES)
            throw new IllegalArgumentException("OBJ text must contain 1..1048576 UTF-8 bytes");
        List<double[]> vertices = new ArrayList<>();
        List<double[]> texcoords = new ArrayList<>();
        List<double[]> normals = new ArrayList<>();
        List<Double> outPositions = new ArrayList<>();
        List<Double> outUvs = new ArrayList<>();
        List<Double> outNormals = new ArrayList<>();
        List<Integer> outIndices = new ArrayList<>();
        int lineNumber = 0;
        for (String rawLine : source.split("\\R")) {
            lineNumber++;
            String trimmed = rawLine.split("#", 2)[0].trim();
            if (trimmed.isEmpty()) continue;
            String[] words = trimmed.split("\\s+");
            switch (words[0]) {
                case "v" -> {
                    if (words.length < 4)
                        throw new IllegalArgumentException("OBJ line " + lineNumber + ": v requires xyz");
                    vertices.add(new double[]{finite(words[1],"x"),finite(words[2],"y"),finite(words[3],"z")});
                }
                case "vt" -> {
                    if (words.length < 3)
                        throw new IllegalArgumentException("OBJ line " + lineNumber + ": vt requires uv");
                    texcoords.add(new double[]{finite(words[1],"u"),finite(words[2],"v")});
                }
                case "vn" -> {
                    if (words.length < 4)
                        throw new IllegalArgumentException("OBJ line " + lineNumber + ": vn requires xyz");
                    normals.add(new double[]{finite(words[1],"nx"),finite(words[2],"ny"),finite(words[3],"nz")});
                }
                case "f" -> {
                    if (words.length < 4)
                        throw new IllegalArgumentException("OBJ line " + lineNumber + ": face needs >= 3 corners");
                    List<FaceRef> refs = new ArrayList<>();
                    for (int i = 1; i < words.length; i++)
                        refs.add(reference(words[i], vertices.size(), texcoords.size(), normals.size()));
                    double[] derivedNormal = null;
                    if (refs.stream().anyMatch(v -> v.normal() < 0))
                        derivedNormal = faceNormal(refs, vertices);
                    int triangles = refs.size() - 2;
                    if (outIndices.size() / 3 + triangles > MAX_RENDER_VERTICES / 3)
                        throw new IllegalArgumentException("OBJ exceeds output mesh limit");
                    for (int tri = 1; tri < refs.size() - 1; tri++) {
                        for (FaceRef ref : List.of(refs.get(0), refs.get(tri), refs.get(tri + 1))) {
                            double[] pos = vertices.get(ref.position());
                            double[] uv = ref.texCoord() >= 0 ? texcoords.get(ref.texCoord())
                                    : new double[]{0.0, 0.0};
                            double[] normal = ref.normal() >= 0 ? normals.get(ref.normal())
                                    : derivedNormal;
                            double mag = normal[0] * normal[0] + normal[1] * normal[1]
                                    + normal[2] * normal[2];
                            if (!Double.isFinite(mag) || mag < 1e-12)
                                throw new IllegalArgumentException("OBJ normals must be nonzero");
                            for (double value : pos) outPositions.add(value);
                            for (double value : uv) outUvs.add(value);
                            for (double value : normal) outNormals.add(value);
                            outIndices.add(outIndices.size());
                        }
                    }
                }
                default -> {
                    // o, g, s, mtllib, usemtl and vendor-specific annotations
                    // can be ignored: this importer extracts geometry only.
                }
            }
            if (vertices.size() > MAX_RENDER_VERTICES || texcoords.size() > MAX_RENDER_VERTICES
                    || normals.size() > MAX_RENDER_VERTICES)
                throw new IllegalArgumentException("OBJ source vertex/normal/UV count exceeds 100000");
        }
        if (outIndices.isEmpty())
            throw new IllegalArgumentException("OBJ source contains no rendered triangle faces");
        return List.of(outPositions, outUvs, outNormals, outIndices);
    }
}

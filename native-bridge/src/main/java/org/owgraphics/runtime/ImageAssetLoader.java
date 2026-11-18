package org.owgraphics.runtime;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Local PNG/JPEG -> GPU-ready RGBA8, bottom row first.
 *
 * The file is bounded before reading and the decoded dimensions are checked
 * before allocation, so crafted compressed files cannot allocate arbitrary
 * large images. The decoder needs no active OpenGL context.
 */
final class ImageAssetLoader {
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    static final int MAX_DIMENSION = 2048;
    private ImageAssetLoader() {}

    static GraphicsArguments.ImageData loadRelative(String name, Path root) {
        if (name == null || name.isBlank() || name.length() > 4096)
            throw new IllegalArgumentException("Image filename required");
        String lower = name.toLowerCase(Locale.ROOT);
        boolean png = lower.endsWith(".png");
        boolean jpeg = lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        if (!png && !jpeg)
            throw new IllegalArgumentException("Image must have a .png, .jpg or .jpeg extension");
        Path requested;
        try {
            requested = Path.of(name);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid image path", invalid);
        }
        if (requested.isAbsolute())
            throw new IllegalArgumentException("Absolute image path rejected");
        for (Path part : requested)
            if ("..".equals(part.toString()))
                throw new IllegalArgumentException("Image parent traversal rejected");
        try {
            Path realRoot = root.toRealPath();
            Path file = realRoot.resolve(requested).toRealPath();
            if (!file.startsWith(realRoot) || !Files.isRegularFile(file))
                throw new IllegalArgumentException("Image must be a regular project file");
            long bytes = Files.size(file);
            if (bytes < 1 || bytes > MAX_FILE_BYTES)
                throw new IllegalArgumentException("Image file size must be 1..8388608 bytes");
            try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
                if (stream == null)
                    throw new IllegalArgumentException("Unable to open image stream");
                Iterator<ImageReader> candidates = ImageIO.getImageReaders(stream);
                if (!candidates.hasNext())
                    throw new IllegalArgumentException("Unsupported or corrupt image");
                ImageReader reader = candidates.next();
                try {
                    reader.setInput(stream, true, true);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!(png && format.equals("png"))
                            && !(jpeg && (format.equals("jpeg") || format.equals("jpg"))))
                        throw new IllegalArgumentException("Image contents do not match extension");
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width < 1 || height < 1
                            || width > MAX_DIMENSION || height > MAX_DIMENSION)
                        throw new IllegalArgumentException("Image dimensions must be 1..2048");
                    BufferedImage bitmap = reader.read(0);
                    if (bitmap.getWidth() != width || bitmap.getHeight() != height)
                        throw new IllegalArgumentException("Decoded image dimensions changed");
                    byte[] rgba = new byte[width * height * 4];
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int argb = bitmap.getRGB(x, y);
                            int offset = ((height - 1 - y) * width + x) * 4;
                            rgba[offset] = (byte) ((argb >> 16) & 0xff);
                            rgba[offset + 1] = (byte) ((argb >> 8) & 0xff);
                            rgba[offset + 2] = (byte) (argb & 0xff);
                            rgba[offset + 3] = (byte) ((argb >>> 24) & 0xff);
                        }
                    }
                    return new GraphicsArguments.ImageData(width, height, rgba);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot decode local image: " + name, error);
        }
    }
}

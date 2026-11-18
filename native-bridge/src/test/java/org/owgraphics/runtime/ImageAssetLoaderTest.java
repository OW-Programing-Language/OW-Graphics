package org.owgraphics.runtime;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ImageAssetLoaderTest {
    @Test public void preservesPngAlphaAndFlipsTopDownRows() throws Exception {
        Path folder = Files.createTempDirectory("ow-image-test");
        Path imageFile = folder.resolve("colors.png");
        try {
            BufferedImage bitmap = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
            bitmap.setRGB(0, 0, 0xffff0000);  // top-left red
            bitmap.setRGB(1, 0, 0xff00ff00);  // top-right green
            bitmap.setRGB(0, 1, 0x800000ff);  // bottom-left translucent blue
            bitmap.setRGB(1, 1, 0xffffffff);  // bottom-right white
            assertTrue(ImageIO.write(bitmap, "png", imageFile.toFile()));
            var result = ImageAssetLoader.loadRelative("colors.png", folder);
            assertEquals(2, result.width());
            assertEquals(2, result.height());
            byte[] pixels = result.rgba();
            assertEquals(16, pixels.length);
            assertEquals(0, pixels[0] & 255);      // bottom-left blue
            assertEquals(0, pixels[1] & 255);
            assertEquals(255, pixels[2] & 255);
            assertEquals(128, pixels[3] & 255);
            assertEquals(255, pixels[8] & 255);    // top-left red
            assertEquals(0, pixels[9] & 255);
            assertEquals(0, pixels[10] & 255);
            assertEquals(255, pixels[11] & 255);
        } finally {
            Files.deleteIfExists(imageFile);
            Files.deleteIfExists(folder);
        }
    }

    @Test public void decodesJpegWithOpaqueAlpha() throws Exception {
        Path folder = Files.createTempDirectory("ow-image-jpeg");
        Path imageFile = folder.resolve("solid.jpg");
        try {
            BufferedImage bitmap = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 4; y++)
                for (int x = 0; x < 4; x++)
                    bitmap.setRGB(x, y, 0x00ff0000);
            assertTrue(ImageIO.write(bitmap, "jpeg", imageFile.toFile()));
            var result = ImageAssetLoader.loadRelative("solid.jpg", folder);
            assertEquals(4, result.width());
            assertEquals(4, result.height());
            assertTrue((result.rgba()[0] & 255) >= 235);  // JPEG lossy red
            assertEquals(255, result.rgba()[3] & 255);
        } finally {
            Files.deleteIfExists(imageFile);
            Files.deleteIfExists(folder);
        }
    }

    @Test public void boundsFilesAndRejectsTraversalAndFormatSpoofing() throws Exception {
        Path folder = Files.createTempDirectory("ow-image-safe");
        Path file = folder.resolve("tiny.png");
        Path wrong = folder.resolve("wrong.jpg");
        Path huge = folder.resolve("huge.png");
        try {
            BufferedImage bitmap = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            assertTrue(ImageIO.write(bitmap, "png", file.toFile()));
            Files.copy(file, wrong);
            assertThrows(IllegalArgumentException.class,
                    () -> ImageAssetLoader.loadRelative("wrong.jpg", folder));
            assertThrows(IllegalArgumentException.class,
                    () -> ImageAssetLoader.loadRelative("../tiny.png", folder));
            assertThrows(IllegalArgumentException.class,
                    () -> ImageAssetLoader.loadRelative(file.toString(), folder));
            assertThrows(IllegalArgumentException.class,
                    () -> ImageAssetLoader.loadRelative("other.gif", folder));
            Files.write(huge, new byte[(int) ImageAssetLoader.MAX_FILE_BYTES + 1]);
            assertThrows(IllegalArgumentException.class,
                    () -> ImageAssetLoader.loadRelative("huge.png", folder));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(wrong);
            Files.deleteIfExists(huge);
            Files.deleteIfExists(folder);
        }
    }

    @Test public void rejectsDecompressionBombDimensionsWithoutAllocatingImage() throws Exception {
        Path folder = Files.createTempDirectory("ow-image-dimensions");
        Path file = folder.resolve("large.png");
        try {
            BufferedImage bitmap = new BufferedImage(2049, 1, BufferedImage.TYPE_INT_RGB);
            assertTrue(ImageIO.write(bitmap, "png", file.toFile()));
            assertThrows(IllegalArgumentException.class,
                    () -> ImageAssetLoader.loadRelative("large.png", folder));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(folder);
        }
    }
}

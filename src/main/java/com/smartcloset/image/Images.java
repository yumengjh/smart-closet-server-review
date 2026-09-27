package com.smartcloset.image;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Iterator;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;

public final class Images {
    private Images() {}

    public record Decoded(BufferedImage image, String format, int orientation) {}

    public static Decoded decode(byte[] bytes, long maxPixels) {
        if (bytes.length == 0) throw ProcessingException.invalid("图片不能为空。");
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw ProcessingException.invalid("无法读取图片，请上传 JPEG 或 PNG。");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName().toLowerCase();
                if (!format.equals("jpeg") && !format.equals("png"))
                    throw ProcessingException.invalid("目前只支持 JPEG 和 PNG；HEIC 请先导出为 JPEG。");
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 32 || h < 32 || w > 8192 || h > 8192 || (long) w * h > maxPixels)
                    throw ProcessingException.invalid("图片宽高至少 32、最多 8192 像素，总像素不能超过 " + maxPixels + "。");
                BufferedImage decoded = reader.read(0);
                int orientation = orientation(bytes);
                return new Decoded(orient(decoded, orientation), format, orientation);
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException e) {
            throw ProcessingException.invalid("图片损坏或无法解码。");
        }
    }

    private static int orientation(byte[] bytes) {
        try {
            var metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            var dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION))
                return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
        } catch (Exception ignored) { /* EXIF is optional; image decoding still validates the file. */ }
        return 1;
    }

    static BufferedImage orient(BufferedImage source, int orientation) {
        int w = source.getWidth(), h = source.getHeight();
        boolean swap = orientation >= 5 && orientation <= 8;
        BufferedImage target = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int tx = x, ty = y;
            switch (orientation) {
                case 2 -> tx = w - 1 - x;
                case 3 -> { tx = w - 1 - x; ty = h - 1 - y; }
                case 4 -> ty = h - 1 - y;
                case 5 -> { tx = y; ty = x; }
                case 6 -> { tx = h - 1 - y; ty = x; }
                case 7 -> { tx = h - 1 - y; ty = w - 1 - x; }
                case 8 -> { tx = y; ty = w - 1 - x; }
                default -> { }
            }
            int pixel = source.getRGB(x, y), alpha = pixel >>> 24;
            // Flatten existing transparency onto white for the RGB-only engines.
            int r = ((pixel >> 16 & 255) * alpha + 255 * (255 - alpha) + 127) / 255;
            int g = ((pixel >> 8 & 255) * alpha + 255 * (255 - alpha) + 127) / 255;
            int b = ((pixel & 255) * alpha + 255 * (255 - alpha) + 127) / 255;
            target.setRGB(tx, ty, r << 16 | g << 8 | b);
        }
        return target;
    }

    public static BufferedImage resize(BufferedImage input, int width, int height) {
        BufferedImage result = new BufferedImage(width, height,
                input.getType() == BufferedImage.TYPE_BYTE_GRAY ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(input, 0, 0, width, height, null);
        } finally { g.dispose(); }
        return result;
    }
}

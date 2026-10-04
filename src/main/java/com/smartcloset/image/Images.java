package com.smartcloset.image;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Iterator;
import java.util.Set;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;

public final class Images {
    private Images() {}

    public record Decoded(BufferedImage image, String format, int orientation) {}

    public static Decoded decode(byte[] bytes, long maxPixels) {
        return decode(bytes, maxPixels, false);
    }

    public static Decoded decodePreservingAlpha(byte[] bytes, long maxPixels) {
        return decode(bytes, maxPixels, true);
    }

    /** Validate the original dimensions before reading pixels; large phone photos are subsampled. */
    public static Decoded sampled(byte[] bytes, int maxSide) {
        if (bytes.length == 0 || bytes.length > 50L * 1024 * 1024)
            throw ProcessingException.invalid("照片不能为空，且不能超过 50 MB。");
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw ProcessingException.invalid("无法读取照片，请选择 JPEG 或 PNG；HEIC 请先转换。");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName().toLowerCase();
                if (!Set.of("jpeg", "png").contains(format)) throw ProcessingException.invalid("请选择 JPEG 或 PNG，HEIC 请先转换。");
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 32 || h < 32 || w > 16384 || h > 16384 || (long) w * h > 64000000)
                    throw ProcessingException.invalid("照片至少 32 像素，总像素不能超过 6400 万。请降低相机分辨率或导出较小的照片。");
                var param = reader.getDefaultReadParam();
                int sample = Math.max(1, (int) Math.ceil((double) Math.max(w, h) / maxSide));
                param.setSourceSubsampling(sample, sample, 0, 0);
                return new Decoded(orient(reader.read(0, param), orientation(bytes), true), format, orientation(bytes));
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException e) {
            throw ProcessingException.invalid("照片无法解码，请重新拍摄或导出 JPEG 后上传。");
        }
    }

    private static Decoded decode(byte[] bytes, long maxPixels, boolean preserveAlpha) {
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
                return new Decoded(orient(decoded, orientation, preserveAlpha), format, orientation);
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
        return orient(source, orientation, false);
    }

    private static BufferedImage orient(BufferedImage source, int orientation, boolean preserveAlpha) {
        int w = source.getWidth(), h = source.getHeight();
        boolean swap = orientation >= 5 && orientation <= 8;
        boolean alphaImage = preserveAlpha && source.getColorModel().hasAlpha();
        BufferedImage target = new BufferedImage(swap ? h : w, swap ? w : h,
                alphaImage ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
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
            if (alphaImage) { target.setRGB(tx, ty, pixel); continue; }
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

    /** Clockwise degrees, applied after EXIF orientation. Keep the entire rotated photo. */
    public static double rotation(double degrees) {
        if (!Double.isFinite(degrees) || degrees < -360 || degrees > 360)
            throw ProcessingException.invalid("rotationDegrees 必须是 -360 到 360 之间的有限角度，正数表示顺时针。");
        double normalized = (degrees % 360 + 360) % 360;
        return normalized == 0 ? 0 : normalized;
    }

    public static BufferedImage rotate(BufferedImage input, double degrees, long maxPixels) {
        double angle = rotation(degrees);
        if (angle == 0) return input;
        if (angle == 90) return orient(input, 6, true);
        if (angle == 180) return orient(input, 3, true);
        if (angle == 270) return orient(input, 8, true);
        double radians = Math.toRadians(angle), sin = Math.abs(Math.sin(radians)), cos = Math.abs(Math.cos(radians));
        int w = (int) Math.ceil(input.getWidth() * cos + input.getHeight() * sin - 1e-9);
        int h = (int) Math.ceil(input.getHeight() * cos + input.getWidth() * sin - 1e-9);
        if (w > 8192 || h > 8192 || (long) w * h > maxPixels)
            throw ProcessingException.invalid("旋转后画布超过尺寸限制，请先缩小照片再上传。");
        boolean alphaImage = input.getColorModel().hasAlpha();
        BufferedImage corrected = new BufferedImage(w, h, alphaImage ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = corrected.createGraphics();
        try {
            if (!alphaImage) { g.setColor(Color.WHITE); g.fillRect(0, 0, w, h); }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            AffineTransform transform = new AffineTransform();
            transform.translate(w / 2.0, h / 2.0); transform.rotate(radians);
            transform.translate(-input.getWidth() / 2.0, -input.getHeight() / 2.0);
            g.drawImage(input, transform, null);
        } finally { g.dispose(); }
        return corrected;
    }
}

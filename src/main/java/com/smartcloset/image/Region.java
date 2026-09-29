package com.smartcloset.image;

/** Pixels after EXIF and user rotation correction, before any processing resize. */
public record Region(int x, int y, int width, int height) {
    public void validate(int imageWidth, int imageHeight) {
        if (x < 0 || y < 0 || width < 2 || height < 2
                || (long) x + width > imageWidth || (long) y + height > imageHeight) {
            throw ProcessingException.invalid("框选区域必须在修正方向后的原图内，宽高至少为 2 像素。");
        }
    }
}

package com.android.grafika.utils;
import android.graphics.ImageFormat;
import android.media.Image;

import java.nio.ByteBuffer;

public class YuvByteBuffer {
    public final int type;
    public final ByteBuffer buffer;

    public YuvByteBuffer(Image image, ByteBuffer dstBuffer) {
        ImageWrapper wrappedImage = new ImageWrapper(image);

        type = (wrappedImage.u.pixelStride == 1) ? ImageFormat.YUV_420_888 : ImageFormat.NV21;
        int size = image.getWidth() * image.getHeight() * 3 / 2;
        if (dstBuffer == null || dstBuffer.capacity() < size || dstBuffer.isReadOnly() || !dstBuffer.isDirect()) {
            buffer = ByteBuffer.allocateDirect(size);
        } else {
            buffer = dstBuffer;
        }
        buffer.rewind();

        removePadding(wrappedImage);
    }

    // Input buffers are always direct as described in
    // https://developer.android.com/reference/android/media/Image.Plane#getBuffer()
    private void removePadding(ImageWrapper image) {
        int sizeLuma = image.y.width * image.y.height;
        int sizeChroma = image.u.width * image.u.height;

        if (image.y.rowStride > image.y.width) {
            removePaddingCompact(image.y, buffer, 0);
        } else {
            buffer.position(0);
            buffer.put(image.y.buffer);
        }

        if (type == ImageFormat.YUV_420_888) {
            if (image.u.rowStride > image.u.width) {
                removePaddingCompact(image.u, buffer, sizeLuma);
                removePaddingCompact(image.v, buffer, sizeLuma + sizeChroma);
            } else {
                buffer.position(sizeLuma);
                buffer.put(image.u.buffer);
                buffer.position(sizeLuma + sizeChroma);
                buffer.put(image.v.buffer);
            }
        } else {
            if (image.u.rowStride > image.u.width * 2) {
                removePaddingNotCompact(image, buffer, sizeLuma);
            } else {
                buffer.position(sizeLuma);
                ByteBuffer uv = image.v.buffer;
                int properUVSize = image.v.height * image.v.rowStride - 1;
                if (uv.capacity() > properUVSize) {
                    uv = clipBuffer(image.v.buffer, 0, properUVSize);
                }
                buffer.put(uv);
                byte lastOne = image.u.buffer.get(image.u.buffer.capacity() - 1);
                buffer.put(buffer.capacity() - 1, lastOne);
            }
        }
        buffer.rewind();
    }

    private void removePaddingCompact(PlaneWrapper plane, ByteBuffer dst, int offset) {
        if (plane.pixelStride != 1) {
            throw new IllegalArgumentException("use removePaddingCompact with pixelStride == 1");
        }

        ByteBuffer src = plane.buffer;
        int rowStride = plane.rowStride;
        dst.position(offset);
        for (int i = 0; i < plane.height; i++) {
            ByteBuffer row = clipBuffer(src, i * rowStride, plane.width);
            dst.put(row);
        }
    }

    private void removePaddingNotCompact(ImageWrapper image, ByteBuffer dst, int offset) {
        if (image.u.pixelStride != 2) {
            throw new IllegalArgumentException("use removePaddingNotCompact pixelStride == 2");
        }
        int width = image.u.width;
        int height = image.u.height;
        int rowStride = image.u.rowStride;
        dst.position(offset);
        for (int i = 0; i < height - 1; i++) {
            ByteBuffer row = clipBuffer(image.v.buffer, i * rowStride, width * 2);
            dst.put(row);
        }
        ByteBuffer row = clipBuffer(image.u.buffer, (height - 1) * rowStride - 1, width * 2);
        dst.put(row);
    }

    private ByteBuffer clipBuffer(ByteBuffer buffer, int start, int size) {
        ByteBuffer duplicate = buffer.duplicate();
        duplicate.position(start);
        duplicate.limit(start + size);
        return duplicate.slice();
    }

    private static class ImageWrapper {
        final int width;
        final int height;
        final PlaneWrapper y;
        final PlaneWrapper u;
        final PlaneWrapper v;

        ImageWrapper(Image image) {
            width = image.getWidth();
            height = image.getHeight();
            y = new PlaneWrapper(width, height, image.getPlanes()[0]);
            u = new PlaneWrapper(width / 2, height / 2, image.getPlanes()[1]);
            v = new PlaneWrapper(width / 2, height / 2, image.getPlanes()[2]);

            // Check this is a supported image format
            // https://developer.android.com/reference/android/graphics/ImageFormat#YUV_420_888
            if (y.pixelStride != 1) {
                throw new IllegalArgumentException("Pixel stride for Y plane must be 1 but got " + y.pixelStride + " instead.");
            }
            if (u.pixelStride != v.pixelStride || u.rowStride != v.rowStride) {
                throw new IllegalArgumentException("U and V planes must have the same pixel and row strides " +
                        "but got pixel=" + u.pixelStride + " row=" + u.rowStride + " for U " +
                        "and pixel=" + v.pixelStride + " and row=" + v.rowStride + " for V");
            }
            if (u.pixelStride != 1 && u.pixelStride != 2) {
                throw new IllegalArgumentException("Supported pixel strides for U and V planes are 1 and 2");
            }
        }
    }

    private static class PlaneWrapper {
        final int width;
        final int height;
        final ByteBuffer buffer;
        final int rowStride;
        final int pixelStride;

        PlaneWrapper(int width, int height, Image.Plane plane) {
            this.width = width;
            this.height = height;
            this.buffer = plane.getBuffer();
            this.rowStride = plane.getRowStride();
            this.pixelStride = plane.getPixelStride();
        }
    }
}
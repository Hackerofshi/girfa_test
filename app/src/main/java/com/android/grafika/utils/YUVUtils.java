package com.android.grafika.utils;

import android.media.Image;

import java.nio.ByteBuffer;

public class YUVUtils {
    static {
        System.loadLibrary("grafika-lib");
    }
    public native void test();

    public static native void NV21ToARGB(byte[] input, byte[] output, int width, int height);
    public static native void ARGBToNV21(byte[] argb, byte[] nv21, int width, int height);
    public static native void NV21ToI420(byte[] input, byte[] output, int width, int height);
    public static native void RotateI420(byte[] input, byte[] output, int width, int height, int rotation);
    public static native void NV21RotateAndConvertToNv12(byte[] input, byte[] output, int width, int height, int rotation);
    public static native void NV21RotateAndMirrorConvertToNv12(byte[] input, byte[] output, int width, int height, int rotation);


    //有点问题
    public static byte[] yuv420888ToNv12(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int ySize = width * height;
        int uvSize = width * height / 2;

        byte[] nv12 = new byte[ySize + uvSize];
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int yRowStride = image.getPlanes()[0].getRowStride();
        int uvRowStride = image.getPlanes()[1].getRowStride();
        int uvPixelStride = image.getPlanes()[1].getPixelStride();

        // Copy Y plane
        int pos = 0;
        for (int row = 0; row < height; row++) {
            yBuffer.position(row * yRowStride);
            yBuffer.get(nv12, pos, width);
            pos += width;
        }

        // Copy UV planes
        pos = ySize;
        for (int row = 0; row < height / 2; row++) {
            uBuffer.position(row * uvRowStride);
            vBuffer.position(row * uvRowStride);
            for (int col = 0; col < width / 2; col++) {
                nv12[pos++] = vBuffer.get(col * uvPixelStride);
                nv12[pos++] = uBuffer.get(col * uvPixelStride);
            }
        }

        return nv12;
    }
}

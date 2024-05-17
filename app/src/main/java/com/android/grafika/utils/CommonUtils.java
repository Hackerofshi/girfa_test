package com.android.grafika.utils;

import android.media.Image;
import android.util.Log;

import java.nio.ByteBuffer;

public class CommonUtils {
    /**
     * 从ImageReader中获取byte[]数据
     */
    public static byte[] getBytesFromImageReader(Image image) {
        try {
            final Image.Plane[] planes = image.getPlanes();
            int len = 0;
            for (Image.Plane plane : planes) {
                len += plane.getBuffer().remaining();
            }
            byte[] bytes = new byte[len];
            int off = 0;
            for (Image.Plane plane : planes) {
                ByteBuffer buffer = plane.getBuffer();
                int remain = buffer.remaining();
                buffer.get(bytes, off, remain);
                off += remain;
            }
            return bytes;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static ByteBuffer getuvBufferWithoutPaddingP(ByteBuffer uBuffer, ByteBuffer vBuffer, int width, int height, int rowStride, int pixelStride) {
        int pos = 0;
        byte[] byteArray = new byte[height * width / 2];
        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int vuPos = col * pixelStride + row * rowStride;
                byteArray[pos++] = vBuffer.get(vuPos);
                byteArray[pos++] = uBuffer.get(vuPos);
            }
        }
        ByteBuffer bufferWithoutPaddings = ByteBuffer.allocate(byteArray.length);
        // 数组放到buffer中
        bufferWithoutPaddings.put(byteArray);
        //重置 limit 和postion 值否则 buffer 读取数据不对
        bufferWithoutPaddings.flip();
        return bufferWithoutPaddings;
    }

    //Semi-Planar格式（SP）的处理和y通道的数据
    private static ByteBuffer getBufferWithoutPadding(ByteBuffer buffer, int width, int rowStride, int times, boolean isVbuffer) {
        if (width == rowStride) return buffer;  //没有buffer,不用处理。
        int bufferPos = buffer.position();
        int cap = buffer.capacity();
        byte[] byteArray = new byte[times * width];
        int pos = 0;
        //对于y平面，要逐行赋值的次数就是height次。对于uv交替的平面，赋值的次数是height/2次
        for (int i = 0; i < times; i++) {
            buffer.position(bufferPos);
            //part 1.1 对于u,v通道,会缺失最后一个像u值或者v值，因此需要特殊处理，否则会crash
            if (isVbuffer && i == times - 1) {
                width = width - 1;
            }
            buffer.get(byteArray, pos, width);
            bufferPos += rowStride;
            pos = pos + width;
        }

        //nv21数组转成buffer并返回
        ByteBuffer bufferWithoutPaddings = ByteBuffer.allocate(byteArray.length);
        // 数组放到buffer中
        bufferWithoutPaddings.put(byteArray);
        //重置 limit 和postion 值否则 buffer 读取数据不对
        bufferWithoutPaddings.flip();
        return bufferWithoutPaddings;
    }


    private byte[] toYUVData(Image image) {
        byte[] imageYUV = new byte[0];
        Image.Plane[] planes = image.getPlanes();
        if (planes.length >= 3) {
            ByteBuffer bufferY = planes[0].getBuffer();
            ByteBuffer bufferU = planes[1].getBuffer();
            ByteBuffer bufferV = planes[2].getBuffer();
            int lengthY = bufferY.remaining();
            int lengthU = bufferU.remaining();
            int lengthV = bufferV.remaining();
            byte[] dataYUV = new byte[lengthY + lengthU + lengthV];
            bufferY.get(dataYUV, 0, lengthY);
            bufferU.get(dataYUV, lengthY, lengthU);
            bufferV.get(dataYUV, lengthY + lengthU, lengthV);
            imageYUV = dataYUV;
        }
        Log.i("TAG", "toYUVData: " + imageYUV.length);
        return imageYUV;
    }

    public static byte[] YUV_420_888toNV21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        ByteBuffer yBuffer = getBufferWithoutPadding(image.getPlanes()[0].getBuffer(), image.getWidth(), image.getPlanes()[0].getRowStride(), image.getHeight(), false);
        ByteBuffer vBuffer;
        //part1 获得真正的消除padding的ybuffer和ubuffer。需要对P格式和SP格式做不同的处理。如果是P格式的话只能逐像素去做，性能会降低。
        if (image.getPlanes()[2].getPixelStride() == 1) { //如果为true，说明是P格式。
            vBuffer = getuvBufferWithoutPaddingP(image.getPlanes()[1].getBuffer(), image.getPlanes()[2].getBuffer(),
                    width, height, image.getPlanes()[1].getRowStride(), image.getPlanes()[1].getPixelStride());
        } else {
            vBuffer = getBufferWithoutPadding(image.getPlanes()[2].getBuffer(), image.getWidth(), image.getPlanes()[2].getRowStride(), image.getHeight() / 2, true);
        }

        //part2 将y数据和uv的交替数据（除去最后一个v值）赋值给nv21
        int ySize = yBuffer.remaining();
        int vSize = vBuffer.remaining();
        byte[] nv21;
        int byteSize = width * height * 3 / 2;
        nv21 = new byte[byteSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);

        //part3 最后一个像素值的u值是缺失的，因此需要从u平面取一下。
        ByteBuffer uPlane = image.getPlanes()[1].getBuffer();
        byte lastValue = uPlane.get(uPlane.capacity() - 1);
        nv21[byteSize - 1] = lastValue;
        return nv21;
    }


    public static void NV21ToNV12(byte[] nv21, byte[] nv12, int width, int height) {
        if (nv21 == null || nv12 == null) return;
        int framesize = width * height;
        int i = 0, j = 0;
        System.arraycopy(nv21, 0, nv12, 0, framesize);
        for (i = 0; i < framesize; i++) {
            nv12[i] = nv21[i];
        }
        for (j = 0; j < framesize / 2; j += 2) {
            nv12[framesize + j - 1] = nv21[j + framesize];
        }
        for (j = 0; j < framesize / 2; j += 2) {
            nv12[framesize + j] = nv21[j + framesize - 1];
        }
    }

    //YV12
    public byte[] toYUV(Image image) {
        byte[] yuv = new byte[0];
        ByteBuffer y = image.getPlanes()[0].getBuffer();
        ByteBuffer u = image.getPlanes()[1].getBuffer();
        ByteBuffer v = image.getPlanes()[2].getBuffer();
        if (yuv == null || yuv.length < (y.capacity() + u.capacity() + v.capacity())) {
            yuv = new byte[y.capacity() + u.capacity() + v.capacity()];
        }
        y.get(yuv, 0, y.capacity());
        u.get(yuv, y.capacity(), u.capacity());
        v.get(yuv, y.capacity() + u.capacity(), v.capacity());
        return yuv;
    }


}

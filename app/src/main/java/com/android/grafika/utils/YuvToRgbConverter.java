package com.android.grafika.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;

import java.nio.ByteBuffer;

public class YuvToRgbConverter {
    private RenderScript rs;
    private ScriptIntrinsicYuvToRGB scriptYuvToRgb;

    // Private variables
    private ByteBuffer yuvBits = null;
    private byte[] bytes = new byte[0];
    private Allocation inputAllocation = null;
    private Allocation outputAllocation = null;

    public YuvToRgbConverter(Context context) {
        rs = RenderScript.create(context);
        scriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs));
    }

    public synchronized void yuvToRgb(Image image, Bitmap output) {
        YuvByteBuffer yuvBuffer = new YuvByteBuffer(image, yuvBits);
        yuvBits = yuvBuffer.buffer;

        if (needCreateAllocations(image, yuvBuffer)) {
            Type.Builder yuvType = new Type.Builder(rs, Element.U8(rs))
                    .setX(image.getWidth())
                    .setY(image.getHeight())
                    .setYuvFormat(yuvBuffer.type);
            inputAllocation = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT);

            bytes = new byte[yuvBuffer.buffer.capacity()];

            Type.Builder rgbaType = new Type.Builder(rs, Element.RGBA_8888(rs))
                    .setX(image.getWidth())
                    .setY(image.getHeight());
            outputAllocation = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT);
        }

        yuvBuffer.buffer.get(bytes);
        inputAllocation.copyFrom(bytes);

        // Convert NV21 or YUV_420_888 format to RGB
        scriptYuvToRgb.setInput(inputAllocation);
        scriptYuvToRgb.forEach(outputAllocation);
        outputAllocation.copyTo(output);
    }

    private boolean needCreateAllocations(Image image, YuvByteBuffer yuvBuffer) {
        return (inputAllocation == null ||               // the very first call
                inputAllocation.getType().getX() != image.getWidth() ||   // image size changed
                inputAllocation.getType().getY() != image.getHeight() ||
                inputAllocation.getType().getYuv() != yuvBuffer.type || // image format changed
                bytes.length == yuvBuffer.buffer.capacity());
    }
}
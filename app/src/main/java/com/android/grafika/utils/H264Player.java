package com.android.grafika.utils;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import com.android.grafika.render.MyRender;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class H264Player implements Runnable {

    private static final String TAG = "H264Player";
    // 本地 h264 文件路径
    private String path;
    private Surface surface;
    private MediaCodec mediaCodec;
    private Context context;
    private Boolean isUsePpsAndSps = false;
    private ByteBuffer outputBuffer;
    private int outVideoWidth;
    private int outVideoHeight;

    private MyRender render;
    private int time;
    private Thread thread;

    public H264Player(Context context, String path, Surface surface) {

        this.context = context;
        this.path = path;
        this.surface = null;
        try {
            this.mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            // 视频宽高暂时写死
            MediaFormat mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 2040, 1080);
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            //获取h264中的pps及sps数据
            if (isUsePpsAndSps) {
                byte[] header_sps = {0, 0, 0, 1, 103, 66, 0, 42, (byte) 149, (byte) 168, 30, 0, (byte) 137, (byte) 249, 102, (byte) 224, 32, 32, 32, 64};
                byte[] header_pps = {0, 0, 0, 1, 104, (byte) 206, 60, (byte) 128, 0, 0, 0, 1, 6, (byte) 229, 1, (byte) 151, (byte) 128};
                mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(header_sps));
                mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(header_pps));
            }
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
            mediaCodec.configure(mediaFormat, surface, null, 0);
        } catch (IOException e) {
            // 解码芯片不支持，走软解
            e.printStackTrace();
        }
    }


    public void play() {
        mediaCodec.start();
        thread = new Thread(this);
        thread.start();
    }

    @Override
    public void run() {
        // 解码 h264
        decodeH264();
    }

    private byte[] mYuvBuffer;
    private int mOutputFormat = COLOR_FORMAT_NV21;
    private static final int INPUT_BUFFER_FULL_COUNT_MAX = 50;
    private int inputBufferFullCount = 0; // 输入缓冲区满了多少次

    private void decodeH264() {
        byte[] bytes = null;
        try {
            bytes = getBytes(path);
        } catch (IOException e) {
            e.printStackTrace();
        }
        // 获取队列
        ByteBuffer[] byteBuffers = mediaCodec.getInputBuffers();
        ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();


        int startIndex = 0;
        int nextFrameStart;
        int totalCount = bytes.length;

        while (true) {
            if (startIndex >= totalCount) {
                break;
            }
            nextFrameStart = findFrame(bytes, startIndex + 1, totalCount);
            Log.e("nextFrameStart", String.valueOf(nextFrameStart));
            if (nextFrameStart == -1) break;
            // 往 ByteBuffer 中塞入数据
            int index = mediaCodec.dequeueInputBuffer(10 * 1000);
            Log.e("index", String.valueOf(index));
            // 获取 dsp 成功
            if (index >= 0) {
                // 拿到可用的 ByteBuffer
                ByteBuffer byteBuffer = byteBuffers[index];
                byteBuffer.clear();
                int length = nextFrameStart - startIndex;
                Log.e("一帧的长度", String.valueOf(length));
                byteBuffer.put(bytes, startIndex, length);
                // 识别分隔符，找到分隔符对应的索引
                mediaCodec.queueInputBuffer(index, 0, length, time, 0);
                startIndex = nextFrameStart;
                time += 66;
            } else {
                inputBufferFullCount++;
                if (inputBufferFullCount > INPUT_BUFFER_FULL_COUNT_MAX) {
                    inputBufferFullCount = 0;
                    mediaCodec.flush(); // 在这里清除所有缓冲区
                }
                continue;
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            // 从 ByteBuffer 中获取解码好的数据
            int outIndex = mediaCodec.dequeueOutputBuffer(info, 10 * 1000);
            if (outIndex >= 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    outputBuffer = mediaCodec.getOutputBuffer(outIndex);
                } else {
                    outputBuffer = outputBuffers[outIndex];
                }
                playGL(outIndex);
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                mediaCodec.releaseOutputBuffer(outIndex, true);
            }
        }
    }

    private void playGL(int outIndex) {
        //mediaCodec 没有绑定surface时候才可以获取到Image
        Image outputImage = mediaCodec.getOutputImage(outIndex);
        Log.e("TAG", "====>format" + outputImage.getFormat());

        int width = mediaCodec.getOutputFormat().getInteger(MediaFormat.KEY_WIDTH);
        int height = mediaCodec.getOutputFormat().getInteger(MediaFormat.KEY_HEIGHT);
        Log.e("TAG", "====>width" + width);
        Log.e("TAG", "====>height" + height);
        int yuvLength = width * height * 3 / 2;
        if (mYuvBuffer == null || mYuvBuffer.length != yuvLength) {
            mYuvBuffer = new byte[yuvLength];
        }
        getDataFromImage(outputImage, mOutputFormat, width, height);

        /*saveJpeg(mYuvBuffer, width, height);*/

                /*ImageUtil.YUVData yuvData = ImageUtil.splitNV21ToYUV(mYuvBuffer, width, height);
                if (render != null) {
                    render.setYUVRenderData(width, height, yuvData.y, yuvData.u, yuvData.v);
                }*/
    }

    private void saveJpeg(byte[] mYuvBuffer, int width, int height) {
        YuvImage yuvImage = new YuvImage(mYuvBuffer, ImageFormat.NV21, width, height, null);
        File file = new File(context.getExternalCacheDir(), System.currentTimeMillis() + ".jpg");
        try {
            FileOutputStream o = new FileOutputStream(file);
            if (yuvImage.compressToJpeg(new Rect(0, 0, width, height), 80, o)) {
                o.flush();
                o.close();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public final static int COLOR_FORMAT_I420 = 1;
    public final static int COLOR_FORMAT_NV21 = 2;
    public final static int COLOR_FORMAT_NV12 = 3;

    private void getDataFromImage(Image image, int colorFormat, int width, int height) {
        Rect crop = image.getCropRect();
        int format = image.getFormat();
        Log.d(TAG, "crop width: " + crop.width() + ", height: " + crop.height());
        Image.Plane[] planes = image.getPlanes();

        byte[] rowData = new byte[planes[0].getRowStride()];

        int channelOffset = 0;
        int outputStride = 1;
        for (int i = 0; i < planes.length; i++) {
            switch (i) {
                case 0:
                    channelOffset = 0;
                    outputStride = 1;
                    break;
                case 1:
                    if (colorFormat == COLOR_FORMAT_I420) {
                        channelOffset = width * height;
                        outputStride = 1;
                    } else if (colorFormat == COLOR_FORMAT_NV21) {
                        channelOffset = width * height + 1;
                        outputStride = 2;
                    } else if (colorFormat == COLOR_FORMAT_NV12) {
                        channelOffset = width * height;
                        outputStride = 2;
                    }
                    break;
                case 2:
                    if (colorFormat == COLOR_FORMAT_I420) {
                        channelOffset = (int) (width * height * 1.25);
                        outputStride = 1;
                    } else if (colorFormat == COLOR_FORMAT_NV21) {
                        channelOffset = width * height;
                        outputStride = 2;
                    } else if (colorFormat == COLOR_FORMAT_NV12) {
                        channelOffset = width * height + 1;
                        outputStride = 2;
                    }
                    break;
                default:
            }
            ByteBuffer buffer = planes[i].getBuffer();
            int rowStride = planes[i].getRowStride();
            int pixelStride = planes[i].getPixelStride();

            int shift = (i == 0) ? 0 : 1;
            int w = width >> shift;
            int h = height >> shift;
            buffer.position(rowStride * (crop.top >> shift) + pixelStride * (crop.left >> shift));
            for (int row = 0; row < h; row++) {
                int length;
                if (pixelStride == 1 && outputStride == 1) {
                    length = w;
                    buffer.get(mYuvBuffer, channelOffset, length);
                    channelOffset += length;
                } else {
                    length = (w - 1) * pixelStride + 1;
                    buffer.get(rowData, 0, length);
                    for (int col = 0; col < w; col++) {
                        mYuvBuffer[channelOffset] = rowData[col * pixelStride];
                        channelOffset += outputStride;
                    }
                }
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length);
                }
            }
        }
    }

    private int findFrame(byte[] bytes, int startIndex, int totalSize) {
        for (int i = startIndex; i < totalSize - 4; i++) {
            if (bytes[i] == 0x00 && bytes[i + 1] == 0x00 && bytes[i + 2] == 0x00 && bytes[i + 3] == 0x01) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 一次性读取文件
     *
     * @param path
     * @return
     * @throws IOException
     */
    public byte[] getBytes(String path) throws IOException {
        InputStream is = new DataInputStream(new FileInputStream(new File(path)));
        int len;
        int size = 1024;
        byte[] buf;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        buf = new byte[size];
        while ((len = is.read(buf, 0, size)) != -1)
            bos.write(buf, 0, len);
        buf = bos.toByteArray();
        return buf;
    }


    public void setRender(MyRender render) {
        this.render = render;
    }


    public void releaseDecoder() {
        if (thread != null) {
            thread.interrupt();
        }
        mediaCodec.stop();
        mediaCodec.release();
        mediaCodec = null;
    }
}


package com.android.grafika.encoder;


import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Vector;

public class VideoThread extends Thread {

    private final MuxerThread muxerThread;
    private final int mWidth;
    private final int mHeigth;

    public static final int IMAGE_HEIGHT = 1080;
    public static final int IMAGE_WIDTH = 1920;

    // 编码相关参数
    private static final String MIME_TYPE = "video/avc"; // H.264 Advanced Video
    private static final int FRAME_RATE = 25; // 帧率
    private static final int IFRAME_INTERVAL = 10; // I帧间隔（GOP）
    private static final int TIMEOUT_USEC = 10000; // 编码超时时间

    private static final int COMPRESS_RATIO = 256;
    private static final int BIT_RATE = IMAGE_HEIGHT * IMAGE_WIDTH * 3 * 8 * FRAME_RATE / COMPRESS_RATIO; // bit rate CameraWrapper.
    private final Vector<byte[]> frameBytes;
    private final byte[] mFrameData;
    private MediaFormat mediaFormat;
    private MediaCodec mMediaCodec;
    private boolean isRun;
    private int videoTrack;


    public VideoThread(int width, int heigth, MuxerThread muxerThread) {
        Log.d("mmm", "VideoThread");
        this.muxerThread = muxerThread;
        this.mWidth = width;
        this.mHeigth = heigth;
        mFrameData = new byte[this.mWidth * this.mHeigth * 3 / 2];
        frameBytes = new Vector<byte[]>();
        preper();
    }

    private void preper() {
        mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeigth);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

        try {
            mMediaCodec = MediaCodec.createEncoderByType("video/avc");
            mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mMediaCodec.start();
            Log.d("mmm", "preper");
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public void add(byte[] data) {
        if (!isRun) return;
        if (frameBytes.size() > 10) {
            frameBytes.remove(0);
        }
        frameBytes.add(data);
    }


    @Override
    public void run() {
        isRun = true;
        while (isRun) {
            if (!frameBytes.isEmpty()) {
                byte[] bytes = this.frameBytes.remove(0);
                Log.e("ang-->", "解码视频数据:" + bytes.length);

                NV21toI420SemiPlanar(bytes, mFrameData, mWidth, mHeigth);
                ByteBuffer[] outputBuffers = mMediaCodec.getOutputBuffers();
                ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();

                int inputBufferindex = mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
                if (inputBufferindex > 0) {
                    ByteBuffer inputBuffer = inputBuffers[inputBufferindex];
                    inputBuffer.clear();
                    inputBuffer.put(mFrameData);
                    mMediaCodec.queueInputBuffer(inputBufferindex, 0, mFrameData.length, System.nanoTime() / 1000, 0);
                }

                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

                int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);

                do {
                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {

                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        outputBuffers = mMediaCodec.getOutputBuffers();
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.e("mmm", "videoINFO_OUTPUT_FORMAT_CHANGED");
                        MediaFormat newFormat = mMediaCodec.getOutputFormat();
                        muxerThread.addTrackIndex(MuxerThread.TRACK_VIDEO, newFormat);
                    } else if (outputBufferIndex < 0) {
                        Log.e("mmm", "outputBufferIndex < 0");
                    } else {
                        ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            Log.d("mmm", "ignoring BUFFER_FLAG_CODEC_CONFIG");
                            bufferInfo.size = 0;
                        }

                        if (bufferInfo.size != 0 && muxerThread.isStart()) {

                            muxerThread.addMuxerData(new MuxerThread.MuxerData(MuxerThread.TRACK_VIDEO, outputBuffer, bufferInfo));
                        }
                        mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                    }
                    outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);

                } while (outputBufferIndex > 0);
            }
        }

        mMediaCodec.stop();
        mMediaCodec.release();
        Log.d("mmm","videomMediaCodec");

    }

    public void stopvideo() {
        isRun = false;
    }

    private static void NV21toI420SemiPlanar(byte[] nv21bytes, byte[] i420bytes, int width, int height) {
        System.arraycopy(nv21bytes, 0, i420bytes, 0, width * height);
        for (int i = width * height; i < nv21bytes.length; i += 2) {
            i420bytes[i] = nv21bytes[i + 1];
            i420bytes[i + 1] = nv21bytes[i];
        }
    }
}

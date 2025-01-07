package com.android.grafika.utils;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoDecoder {

    private static final String TAG = "VideoDecoder";

    public final static int COLOR_FORMAT_I420 = 1;
    public final static int COLOR_FORMAT_NV21 = 2;
    public final static int COLOR_FORMAT_NV12 = 3;

    private static final int DECODE_COLOR_FORMAT = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
    private static final long DEFAULT_TIMEOUT_US = 10000;

    private int mOutputFormat = COLOR_FORMAT_NV12;
    private byte[] mYuvBuffer;
    private volatile boolean mStop = false;
    private YuvToRgbConverter converter;

    public void setOutputFormat(int outputFormat) {
        mOutputFormat = outputFormat;
    }

    public int getOutputFormat() {
        return mOutputFormat;
    }

    public void stop() {
        mStop = true;
    }

    public void decode(/*String videoFilePath,*/Context context, DecodeCallback decodeCallback) {
        converter = new YuvToRgbConverter(context);
        mStop = false;
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        try {
            // 获取 AssetManager
            File externalCacheDir = context.getExternalCacheDir();
            // 替换为你的视频文件名

            extractor = new MediaExtractor();
            String path = externalCacheDir.getAbsolutePath() + "/test.mp4";
            Log.e(TAG, "video path " + path);

            extractor.setDataSource(path);
            int trackIndex = selectVideoTrack(extractor);
            if (trackIndex < 0) {
                Log.e(TAG, "No video track found in ");
                return;
            }
            MediaFormat mediaFormat = extractor.getTrackFormat(trackIndex);
            String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            if (isColorFormatSupported(DECODE_COLOR_FORMAT, decoder.getCodecInfo().getCapabilitiesForType(mime))) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, DECODE_COLOR_FORMAT);
                Log.i(TAG, "set decode color format to type " + DECODE_COLOR_FORMAT);
            } else {
                Log.i(TAG, "unable to set decode color format, color format type " + DECODE_COLOR_FORMAT + " not " +
                        "supported");
            }
            int width = mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
            Log.d(TAG, "decode video width: " + width + ", height: " + height);
            int yuvLength = width * height * 3 / 2;
            if (mYuvBuffer == null || mYuvBuffer.length != yuvLength) {
                mYuvBuffer = new byte[yuvLength];
            }
            decoder.configure(mediaFormat, null, null, 0);
            decoder.start();
            decodeFramesToImage(decoder, extractor, width, height, decodeCallback);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
                decoder = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
        }
    }

    private static int selectVideoTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                extractor.selectTrack(i);
                return i;
            }
        }
        return -1;
    }

    private boolean isColorFormatSupported(int colorFormat, MediaCodecInfo.CodecCapabilities caps) {
        for (int c : caps.colorFormats) {
            if (c == colorFormat) {
                return true;
            }
        }
        return false;
    }

    private void decodeFramesToImage(MediaCodec decoder, MediaExtractor extractor, int width, int height,
                                     DecodeCallback decodeCallback) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        int outputFrameCount = 0;
        while (!mStop && !sawOutputEOS) {
            if (!sawInputEOS) {
                int inputBufferId = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_US);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEOS = true;
                        Log.i(TAG, "sawInputEOS is true");
                    } else {
                        decoder.queueInputBuffer(inputBufferId, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            int outputBufferId = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US);
            if (outputBufferId >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true;
                    Log.i(TAG, "sawOutputEOS is true");
                }
                if (info.size > 0) {
                    outputFrameCount++;
                    Image image = decoder.getOutputImage(outputBufferId);
                    Log.i("dfdd", "ffff");
                    //getDataFromImage(image, mOutputFormat, width, height);
                    Bitmap bitmap = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
                    converter.yuvToRgb(image, bitmap);
                    if (decodeCallback != null) {
                        decodeCallback.callBitmap(bitmap);
                    }
                    image.close();
                    decoder.releaseOutputBuffer(outputBufferId, false);
                    // callback
                    if (decodeCallback != null) {
                        decodeCallback.onDecode(mYuvBuffer, width, height, outputFrameCount, info.presentationTimeUs);
                    }
                }
            }
        }
        if (decodeCallback != null) {
            if (mStop) {
                decodeCallback.onStop();
            } else {
                decodeCallback.onFinish();
            }
        }
    }

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

    public interface DecodeCallback {
        // 返回的yuv数据格式由OUTPUT_COLOR_FORMAT指定
        void onDecode(byte[] yuv, int width, int height, int frameCount, long presentationTimeUs);

        // 解码完成
        void onFinish();

        // 异常中断
        void onStop();

        void callBitmap(Bitmap bitmap);
    }

}

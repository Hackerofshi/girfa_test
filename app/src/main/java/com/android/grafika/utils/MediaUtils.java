package com.android.grafika.utils;

import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;

import android.app.Activity;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.util.Log;
import android.util.Size;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;

public class MediaUtils {
    private static final String TAG = "MediaUtils";
    private final Activity activity;
    private BufferedOutputStream outputStream;
    private byte[] configbyte;

    public MediaUtils(Activity activity) {
        this.activity = activity;
    }

    private MediaCodec mediaCodec;
    private MediaMuxer mMuxer;
    private byte[] nv21;
    //private byte[] nv21_rotated;
    //private byte[] nv12;
    private byte[] yuvData;


    public void onPreview(Image image, byte[] y, byte[] u, byte[] v, Size previewSize, int stride) {
        //        Log.i(TAG, "width = " + mPreviewSize.getWidth());
        //        Log.i(TAG, "height = " + mPreviewSize.getHeight());
        //        Log.i(TAG, "y = " + y.length);
        //        Log.i(TAG, "u = " + u.length);
        //        Log.i(TAG, "v = " + v.length);

        if (nv21 == null) {
            nv21 = new byte[stride * previewSize.getHeight() * 3 / 2];
            //nv21_rotated = new byte[stride * previewSize.getHeight() * 3 / 2];
        }

        long startTime1 = System.currentTimeMillis();
        ImageUtil.yuvToNv21(y, u, v, nv21, stride, previewSize.getHeight());
        long endTime1 = System.currentTimeMillis();
        //Log.d(TAG, "转换为nv21耗时" + (endTime1 - startTime1));

        long startTime = System.currentTimeMillis();
        if (yuvData == null) {
            yuvData = new byte[nv21.length];
            Log.d(TAG, "image" + image.getHeight());
        }
        YUVUtils.NV21RotateAndConvertToNv12(nv21, yuvData, previewSize.getWidth(), previewSize.getHeight(), 90);
        /*ImageUtil.nv21_rotate_to_90(nv21, nv21_rotated, stride, previewSize.getHeight());
        final byte[] yuvData = ImageUtil.nv21toNV12(nv21_rotated, nv12);*/
        long endTime = System.currentTimeMillis();
        //Log.d(TAG, "转换为nv12耗时" + (endTime - startTime));

        //byte[] yuvData = YUVUtils.yuv420888ToNv12(image);

        /*int width = image.getWidth();
        int height = image.getHeight();

        int yuvLength = width * height * 3 / 2;

        if (mYuvBuffer == null) {
            mYuvBuffer = new byte[yuvLength];
        }
        */
        //放入队列中
        putYUVData(yuvData);

        //直接编码
        /*procHandler.post(new Runnable() {
            @Override
            public void run() {
                encode(temp);
            }
        });*/
    }

    private int mTrackIndex = -1;
    private boolean mMuxerStarted = false;

    public void initVideoCodec(Size previewSize) {

        Log.i(TAG, "initVideoCodec: getWidth" + previewSize.getWidth());
        Log.i(TAG, "initVideoCodec: getHeight" + previewSize.getHeight());

        //设置录制视频的宽高
        //这里面的长宽设置应该跟imagereader里面获得的image的长宽进行对齐
        MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc",
                previewSize.getHeight(), previewSize.getWidth());
        //颜色空间设置为yuv420sp
        //mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FormatYUV420Flexible);
        //比特率，也就是码率 ，值越高视频画面更清晰画质更高
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 1000_000);
        //帧率，一般设置为15帧就够了
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
        //关键帧间隔
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

        mediaFormat.setLong(MediaFormat.KEY_MAX_INPUT_SIZE, Long.MAX_VALUE);

        try {
            //初始化mediacodec
            mediaCodec = MediaCodec.createEncoderByType("video/avc");
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        //设置为编码模式和编码格式
        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mediaCodec.start();
        createFile(Environment.getExternalStorageDirectory() + File.separator + "test.h264");
        initMuxer();
    }


    private void createFile(String path) {
        File file = new File(path);
        if (file.exists()) {
            file.delete();
        }
        try {
            outputStream = new BufferedOutputStream(new FileOutputStream(file));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void initMuxer() {
        String outputPath = new File(Environment.getExternalStorageDirectory(),
                CommonUtils.formatTime(System.currentTimeMillis()) + ".mp4").toString();
        Log.d(TAG, "output file is " + outputPath);
        try {
            mMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException ioe) {
            throw new RuntimeException("MediaMuxer creation failed", ioe);
        }
    }

    private static final int TIMEOUT_USEC = 12000;
    private static final int yuvQueueSize = 10;

    ArrayBlockingQueue<byte[]> YUVQueue = new ArrayBlockingQueue<>(yuvQueueSize);

    public void putYUVData(byte[] buffer) {
        if (YUVQueue.size() >= 10) {
            YUVQueue.poll();
        }
        YUVQueue.add(buffer);
        //Log.i(TAG, "YUVQueue.size == : " + YUVQueue.size());
    }


    private volatile boolean isRunning;
    private long nanoTime;

    /**
     * 开始线程，开始编码
     */
    public void startEncode() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                isRunning = true;
                nanoTime = System.nanoTime();
                while (isRunning) {
                    if (YUVQueue.size() > 0) {
                        byte[] poll = YUVQueue.poll();
                        if (poll != null) {
                            encode(poll);
                        }
                    }
                }
            }
        });
        thread.start();
    }

    private long startTime = 0;

    void encode(byte[] input) {
        //Log.i(TAG, "encode: " + input.length);
        try {
            //编码器输出缓冲区
            int inputBufferIndex = mediaCodec.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                //pts = computePresentationTime(generateIndex);
                ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    //把转换后的YUV420格式的视频帧放到编码器输入缓冲区中
                    inputBuffer.put(input);
                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, input.length,
                            (System.nanoTime() - nanoTime) / 1000, 0);
                }
                //generateIndex += 1;
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);

            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                addMuxerFormat();
            }
            while (outputBufferIndex >= 0) {
                ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex);
                writeToH264(outputBuffer);

                /*if ((bufferInfo.flags & BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    bufferInfo.size = 0;
                }*/

                /*if (mMuxerStarted && mMuxer != null && outputBuffer != null) {
                    if (System.currentTimeMillis() - startTime > 10 * 60 * 1000) {
                        splitVideo();
                    } else {
                        if (bufferInfo.size != 0) {
                            // adjust the ByteBuffer values to match BufferInfo (not needed?)
                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                            mMuxer.writeSampleData(mTrackIndex, outputBuffer, bufferInfo);
                            Log.d(TAG, "sent " + bufferInfo.size + " bytes to muxer");
                        }
                    }
                }*/

                /*byte[] outData = new byte[bufferInfo.size];
                outputBuffer.get(outData);

                if (bufferInfo.flags == BUFFER_FLAG_CODEC_CONFIG) {
                    configbyte = new byte[bufferInfo.size];
                    configbyte = outData;
                } else if (bufferInfo.flags == BUFFER_FLAG_KEY_FRAME) {
                    byte[] keyframe = new byte[bufferInfo.size + configbyte.length];
                    System.arraycopy(configbyte, 0, keyframe, 0, configbyte.length);
                    //把编码后的视频帧从编码器输出缓冲区中拷贝出来
                    System.arraycopy(outData, 0, keyframe, configbyte.length, outData.length);
                    Log.i(TAG, "keyframe: " + keyframe.length);
                    outData = keyframe;
                    writeBytes(outData);
                } else {
                    writeBytes(outData);
                    Log.i(TAG, "encode: " + outData.length);
                }*/

                //send.sendH264Data(outData);
                //Thread.sleep(40);
                mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
                //Log.i(TAG, "outputBufferIndex: =" + outputBufferIndex);
            }

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }


    public boolean isH264(byte[] data) {
        // 检查数据长度是否足够
        if (data.length < 5) {
            return false;
        }

        // 检查是否有 H.264 的起始码 (0x00000001 or 0x000001)
        if ((data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x00 && data[3] == 0x01) ||
                (data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x01)) {

            // 获取 NALU 类型
            int nalUnitType = data[4] & 0x1F;

            // 检查 NALU 类型是否在 H.264 的有效范围内 (1-23, 7 为 SPS, 8 为 PPS)
            if (nalUnitType == 8) {
                return true; // 这是有效的 H.264 数据
            }
        }

        return false;
    }


    private void writeToH264(ByteBuffer outputBuffer) {
        byte[] ba = new byte[outputBuffer.remaining()];
        outputBuffer.get(ba);


        //if (isH264(ba)) {
        //    Log.i("mMediaCodec", "是pps");
        //} else {
        //    Log.i("mMediaCodec", "不pps");
        //}
        if (ba[0] == 0 && ba[1] == 0 && ba[2] == 0 && ba[3] == 1 && ba[4] == 0x67) {
            Log.i("mMediaCodec", "是pps");
            Log.i("mMediaCodec===>", Arrays.toString(ba));
            writeBytes(ba);
        } else {
            Log.i("mMediaCodec", "是视频帧");
            writeBytes(ba);
        }

    }

    public void writeBytes(byte[] array) {
        FileOutputStream writer = null;
        try {
            // 打开一个写文件器，构造函数中的第二个参数true表示以追加形式写文件
            writer = new FileOutputStream(Environment.getExternalStorageDirectory() + "/codec_1.h264", true);
            writer.write(array);
            writer.write('\n');
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void addMuxerFormat() {
        if (mMuxerStarted) {
            throw new RuntimeException("format changed twice");
        }
        MediaFormat newFormat = mediaCodec.getOutputFormat();
        // now that we have the Magic Goodies, start the muxer
        mTrackIndex = mMuxer.addTrack(newFormat);
        mMuxer.start();
        mMuxerStarted = true;
        Log.d(TAG, "encoder output format changed: " + newFormat + "===:" + mTrackIndex);

        startTime = System.currentTimeMillis();
    }

    public void splitVideo() {
        stopMuxer();
        restartMuxer();
    }

    //关闭mMuxer，将一段视频保存到文件夹中。
    public void stopMuxer() {
        if (mMuxer != null) {
            mMuxerStarted = false;
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
            mTrackIndex = -1;
        }
    }



    //重新开始录制新的视频
    private void restartMuxer() {
        initMuxer();
        addMuxerFormat();
    }


    public void release() {
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }
    }

    public void setRunning(boolean running) {
        isRunning = running;
    }
}

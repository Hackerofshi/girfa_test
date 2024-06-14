package com.android.grafika.utils;

import android.app.Activity;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public class PcmToAAC {
    private MediaCodec encoder;
    private MediaMuxer mediaMuxer;
    private boolean isRun;
    private int mAudioTrack;
    private long prevOutputPTSUs;
    private Queue queue;


    public void init(Activity activity) {

        queue = new Queue();
        queue.init(1024 * 100);
        try {
            MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 16000, 1);
            //设置比特率
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
            audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 16000);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            mediaMuxer = new MediaMuxer(activity.getExternalCacheDir() + File.separator + "pcm.aac", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);


            Log.d("mmm", "accinit");
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public void putData(byte[] buffer, int len) {
        queue.addAll(buffer);
    }

    public void start() {
        isRun = true;
        Log.d("mmm", "aacstart");
        new Thread(new Runnable() {
            @Override
            public void run() {
                ByteBuffer[] inputBuffers = encoder.getInputBuffers();
                ByteBuffer[] outputBuffers = encoder.getOutputBuffers();
                while (isRun) {
                    byte[] bytes = new byte[640];
                    int all = queue.getAll(bytes, 640);
                    if (all < 0) {
                        try {
                            Thread.sleep(50);
                            continue;
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    int inputBufferindex = encoder.dequeueInputBuffer(0);
                    if (inputBufferindex > 0) {
                        ByteBuffer inputBuffer = inputBuffers[inputBufferindex];
                        inputBuffer.clear();
                        inputBuffer.put(bytes);
                        encoder.queueInputBuffer(inputBufferindex, 0, bytes.length, getPTSUs(), 0);
                    }

                    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

                    int outputBufferindex = encoder.dequeueOutputBuffer(bufferInfo, 0);
                    if (outputBufferindex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d("mmm", "改变format");
                        mAudioTrack = mediaMuxer.addTrack(encoder.getOutputFormat());
                        if (mAudioTrack >= 0) {
                            mediaMuxer.start();
                            Log.d("mmm", "开始混合");
                        }
                    } else if (outputBufferindex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        Log.d("mmm", "try-later");
                    }
                    while (outputBufferindex > 0) {
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0;
                        }
                        bufferInfo.presentationTimeUs = getPTSUs();
                        ByteBuffer outputBuffer = outputBuffers[outputBufferindex];
//                            outputBuffer.position(bufferInfo.offset);
                        if (mAudioTrack >= 0) {
                            mediaMuxer.writeSampleData(mAudioTrack, outputBuffer, bufferInfo);
                            Log.d("mmm", "写入文件");
                        }
                        prevOutputPTSUs = bufferInfo.presentationTimeUs;
                        encoder.releaseOutputBuffer(outputBufferindex, false);
                        outputBufferindex = encoder.dequeueOutputBuffer(bufferInfo, 0);
                    }

                }
                Log.d("mmm", "aacstop");
                encoder.stop();
                encoder.release();
                mediaMuxer.stop();
                mediaMuxer.release();
            }
        }).start();
    }

    public void stop() {
        isRun = false;
    }


    private long getPTSUs() {
        long result = System.nanoTime() / 1000L;
        // presentationTimeUs should be monotonic
        // otherwise muxer fail to write
        if (result < prevOutputPTSUs)
            result = (prevOutputPTSUs - result) + result;
        return result;
    }

}

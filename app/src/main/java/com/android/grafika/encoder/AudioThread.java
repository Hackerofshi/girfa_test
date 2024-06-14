package com.android.grafika.encoder;


import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import com.android.grafika.utils.Queue;

import java.io.IOException;
import java.nio.ByteBuffer;

public class AudioThread extends Thread {

    private static final int TIMEOUT_USEC = 10000;
    private static final String MIME_TYPE = "audio/mp4a-latm";
    private static final int SAMPLE_RATE = 16000;
    private static final int BIT_RATE = 64000;

    private MediaFormat audioFormat;
    private MediaCodec mMediaCodec;
    private final Queue queue;
    private boolean isRun;
    private long prevOutputPTSUs;
    private MuxerThread muxerThread;
    private MediaMuxer mediaMuxer;
    private int audiotrack;

    public AudioThread(MuxerThread muxerThread) {
        Log.d("mmm", "AudioThread");
        this.muxerThread = muxerThread;
        queue = new Queue();
        queue.init(1024 * 100);
        preper();
    }

    private void preper() {
        audioFormat = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 1);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE);

        try {
            mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
            mMediaCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mMediaCodec.start();
            Log.d("mmm", "preper");

        } catch (IOException e) {
            e.printStackTrace();
        }

        isRun = true;
    }

    public void addAudioData(byte[] data) {
        if (!isRun) return;
        queue.addAll(data);
    }

    public void audioStop() {
        isRun = false;
    }


    @Override
    public void run() {
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

            ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
            ByteBuffer[] outputBuffers = mMediaCodec.getOutputBuffers();

            int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputBufferIndex > 0) {
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                inputBuffer.put(bytes);
                mMediaCodec.queueInputBuffer(inputBufferIndex, 0, bytes.length, getPTSUs(), 0);
            }


            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);

            do {
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = mMediaCodec.getOutputBuffers();
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.e("mmm", "audioINFO_OUTPUT_FORMAT_CHANGED");
                    MediaFormat format = mMediaCodec.getOutputFormat(); // API >= 16
                    if (muxerThread != null) {
                        Log.e("mmm", "添加音轨 INFO_OUTPUT_FORMAT_CHANGED " + format.toString());
                        muxerThread.addTrackIndex(MuxerThread.TRACK_AUDIO, format);
                    }
                } else if (outputBufferIndex < 0) {
                    Log.e("mmm", "encoderStatus < 0");
                } else {
                    final ByteBuffer encodedData = outputBuffers[outputBufferIndex];
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0;
                    }


                    if (bufferInfo.size != 0 && muxerThread != null && muxerThread.isStart()) {
                        bufferInfo.presentationTimeUs = getPTSUs();
                        muxerThread.addMuxerData(new MuxerThread.MuxerData(MuxerThread.TRACK_AUDIO, encodedData, bufferInfo));
                        prevOutputPTSUs = bufferInfo.presentationTimeUs;
                    }
                    mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                }
                outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);

            } while (outputBufferIndex >= 0);
        }

        mMediaCodec.stop();
        mMediaCodec.release();
        Log.d("mmm", "audiomMediaCodec");
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

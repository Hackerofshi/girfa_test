package com.android.grafika.encoder;


import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Vector;

public class MuxerThread extends Thread {

    public static final String TRACK_AUDIO = "TRACK_AUDIO";
    public static final String TRACK_VIDEO = "TRACK_VIDEO";
    private Vector<MuxerData> muxerDatas;
    private MediaMuxer mediaMuxer;
    private boolean isAddAudioTrack;
    private boolean isAddVideoTrack;
    private static MuxerThread muxerThread = new MuxerThread();
    private int videoTrack;
    private int audioTrack;
    private VideoThread videoThread;
    private AudioThread audioThread;
    private boolean isRun;


    private MuxerThread() {
        Log.d("mmm", "MuxerThread");
    }

    public static MuxerThread getInstance() {
        return muxerThread;
    }


    public synchronized void addTrackIndex(String track, MediaFormat format) {
        if (isAddAudioTrack && isAddVideoTrack) {
            return;
        }

        if (!isAddVideoTrack && track.equals(TRACK_VIDEO)) {
            Log.e("mmm", "添加视频轨");
            videoTrack = mediaMuxer.addTrack(format);
            if (videoTrack >= 0) {
                isAddVideoTrack = true;
                Log.e("mmm", "添加视频轨完成");
            }
        }

        if (!isAddAudioTrack && track.equals(TRACK_AUDIO)) {
            Log.e("mmm", "添加音频轨");
            audioTrack = mediaMuxer.addTrack(format);
            if (audioTrack >= 0) {
                isAddAudioTrack = true;
                Log.e("mmm", "添加音频轨完成");
            }
        }

        if (isStart()) {
            mediaMuxer.start();
        }
    }

    public boolean isStart() {
        return isAddAudioTrack && isAddVideoTrack;
    }


    public void addMuxerData(MuxerData muxerData) {
        muxerDatas.add(muxerData);
    }

    public void addVideoData(byte[] data) {
        if (!isRun) return;
        videoThread.add(data);
    }

    public void addAudioData(byte[] data) {
        if (!isRun || audioThread == null) return;
        audioThread.addAudioData(data);
    }

    public void startMuxer(int width, int height) {
        Log.d("mmm", "startMuxer");
        try {
            mediaMuxer = new MediaMuxer("sdcard/camer111.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxerDatas = new Vector<>();
        } catch (IOException e) {
            e.printStackTrace();
        }
        isRun = true;
        videoThread = new VideoThread(width, height, this);
        videoThread.start();
        audioThread = new AudioThread(this);
        audioThread.start();
        start();
    }

    public void exit() {
        isRun = false;
        videoThread.stopvideo();
        audioThread.audioStop();
    }

    @Override
    public void run() {
        while (isRun) {
            if (!muxerDatas.isEmpty() && isStart()) {
                MuxerData muxerData = muxerDatas.remove(0);
                if (muxerData.trackIndex.equals(TRACK_VIDEO) && videoTrack >= 0) {
                    Log.d("mmm", "写入视频" + muxerData.bufferInfo.size);
                    mediaMuxer.writeSampleData(videoTrack, muxerData.byteBuf, muxerData.bufferInfo);
                }

                if (muxerData.trackIndex.equals(TRACK_AUDIO) && audioTrack >= 0) {
                    Log.d("mmm", "写入音频" + muxerData.bufferInfo.size);
                    mediaMuxer.writeSampleData(audioTrack, muxerData.byteBuf, muxerData.bufferInfo);
                }
            }
        }

        mediaMuxer.stop();
        mediaMuxer.release();

        Log.d("mmm", "mediaMuxerstop");
    }
    public static class MuxerData {
        String trackIndex;
        ByteBuffer byteBuf;
        MediaCodec.BufferInfo bufferInfo;

        public MuxerData(String trackIndex, ByteBuffer byteBuf, MediaCodec.BufferInfo bufferInfo) {
            this.trackIndex = trackIndex;
            this.byteBuf = byteBuf;
            this.bufferInfo = bufferInfo;
        }
    }
}



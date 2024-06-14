package com.android.grafika;

import android.app.Activity;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.google.common.primitives.Bytes;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * udp 播放
 */
public class SurfaceViewUdpActivity extends Activity {
    private MediaCodec mediaCodec;
    private Surface surface = null;
    private SurfaceView surfaceView;
    private final int udpPort = 5000;// 替换为你的UDP端口
    private DatagramSocket datagramSocket;
    private static final int MAX_PACKET_SIZE = 1024 * 8;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_surface);

        surfaceView = findViewById(R.id.surfaceView);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surface = holder.getSurface();
                initializeDecoder();
                startUdpStream();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                releaseDecoder();
            }
        });
    }




    private void startUdpStream() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                {
                    try {
                        // 接收 UDP 数据并解码
                        datagramSocket = new DatagramSocket(udpPort);
                        byte[] buffer = new byte[MAX_PACKET_SIZE];
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        while (true) {
                            datagramSocket.receive(packet);
                            int dataSize = packet.getLength();
                            byte[] data = Arrays.copyOfRange(buffer, 0, dataSize);
                            processFrame(data);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }).start();
        checkNaluAndPlay();
    }

    private int startIndex = 0;
    private int nextFrameStart;
    public void checkNaluAndPlay() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                {
                    while (true) {
                        byte[] bytes = Bytes.toArray(BUFFER);
                        if (bytes.length == 0) {
                            continue;
                        }
                        Log.e("bytes", bytes.length + "");
                        if (startIndex >= bytes.length) {
                            continue;
                        }
                        nextFrameStart = findFrame(bytes, startIndex + 1, bytes.length);
                        Log.e("nextFrameStart", nextFrameStart + "");
                        if (nextFrameStart == -1) continue;
                        Log.e("一帧的开始", startIndex + "");
                        int length = nextFrameStart - startIndex;
                        byte[] batch = getBatch(length);
                        Log.e("一帧的长度", batch.length + "");
                        //startIndex = 0;
                        //往 ByteBuffer 中塞入数据
                        int index = mediaCodec.dequeueInputBuffer(10 * 1000);
                        Log.e("index", index + "");
                        // 获取 dsp 成功
                        if (index >= 0) {
                            // 拿到可用的 ByteBuffer
                            ByteBuffer byteBuffer = mediaCodec.getInputBuffers()[index];
                            byteBuffer.clear();
                            //int length = nextFrameStart - startIndex;
                            Log.e("一帧的长度", length + "");
                            byteBuffer.put(batch, startIndex, length);
                            // 识别分隔符，找到分隔符对应的索引
                            mediaCodec.queueInputBuffer(index, 0, length, 0, 0);
                            startIndex = 0;
                        } else {
                            return;
                        }
                        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                        // 从 ByteBuffer 中获取解码好的数据
                        int outIndex = mediaCodec.dequeueOutputBuffer(info, 10 * 1000);
                        while (outIndex >= 0) {
                            mediaCodec.releaseOutputBuffer(outIndex, true);
                            outIndex = mediaCodec.dequeueOutputBuffer(info, 0);
                        }
                    }
                }
            }
        }).start();
    }


    private void processFrame(byte[] data) {
        putBatch(data);
    }


    private static final int BUFFER_SIZE = 1024 * 1024; // 1MB
    private static final List<Byte> BUFFER = new ArrayList<>();
    private static final Lock LOCK = new ReentrantLock();
    private static final Condition NOT_FULL = LOCK.newCondition();
    private static final Condition NOT_EMPTY = LOCK.newCondition();


    private void putBatch(byte[] data) {
        LOCK.lock();
        try {
            while (BUFFER.size() + data.length > BUFFER_SIZE) {
                NOT_FULL.await();
            }
            BUFFER.addAll(Bytes.asList(data));
            NOT_EMPTY.signal();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 获取一帧的长度
     *
     * @param batchSize 一帧的长度
     * @return 一帧的数据
     */
    private byte[] getBatch(int batchSize) {
        LOCK.lock();
        try {
            while (BUFFER.size() < batchSize) {
                NOT_EMPTY.await();
            }
            List<Byte> batchData = new ArrayList<>(BUFFER.subList(0, batchSize));
            Log.i("TAG", "getBatch: " + BUFFER.size());
            BUFFER.subList(0, batchSize).clear();
            Log.i("TAG", "delete getBatch: " + BUFFER.size());
            NOT_FULL.signal();
            return Bytes.toArray(batchData);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            LOCK.unlock();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseDecoder();
    }

    private void releaseDecoder() {
        mediaCodec.stop();
        mediaCodec.release();
        mediaCodec = null;
    }

    private void initializeDecoder() {
        try {
            mediaCodec = MediaCodec.createDecoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1920, 1080); // 替换为你的视频宽度和高度
            mediaCodec.configure(format, surface, null, 0);
            // 配置SPS和PPS
            //val sps = byteArrayOf(
            //    0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xC0, 0x1E, 0xDA, 0x02, 0x80, 0x2D, 0xC8, 0x08, 0x08, 0x08, 0x08
            //)
            //val pps = byteArrayOf(
            //    0x00, 0x00, 0x00, 0x01, 0x68, 0xCE, 0x06, 0xE2
            //)
            //
            //format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            //format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            mediaCodec.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

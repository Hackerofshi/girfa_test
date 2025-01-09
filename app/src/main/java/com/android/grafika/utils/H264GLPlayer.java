package com.android.grafika.utils;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import com.android.grafika.render.MyRender;
import com.google.common.primitives.Bytes;

import java.io.File;
import java.io.FileOutputStream;
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

public class H264GLPlayer {
    private static final String TAG = "H264GLPlayer";
    private final int udpPort = 5000;// 替换为你的UDP端口
    private DatagramSocket datagramSocket;
    private static final int MAX_PACKET_SIZE = 1024 * 8;

    private Boolean isUsePpsAndSps = true;
    private MediaCodec mediaCodec;
    private final MyRender render;
    private final Context context;
    private volatile boolean isRun = false;
    private Thread receiverDataThread;
    private Thread decoderThread;
    private int inputBufferFullCount = 0; // 输入缓冲区满了多少次
    private static final int INPUT_BUFFER_FULL_COUNT_MAX = 50;


    public H264GLPlayer(Context context, MyRender render) {
        this.render = render;
        this.context = context;
        initializeDecoder();
    }

    public void startUdpStream() {
        isRun = true;
        mediaCodec.start();
        receiverData();
        checkNaluAndPlay();
    }

    private void receiverData() {
        receiverDataThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 接收 UDP 数据并解码
                    datagramSocket = new DatagramSocket(udpPort);
                    byte[] buffer = new byte[MAX_PACKET_SIZE];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    while (isRun) {
                        datagramSocket.receive(packet);
                        int dataSize = packet.getLength();
                        byte[] data = Arrays.copyOfRange(buffer, 0, dataSize);
                        processFrame(data);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        receiverDataThread.start();
    }

    private void initializeDecoder() {
        try {
            this.mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            // 视频宽高暂时写死
            MediaFormat mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1080, 1920);
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            //获取h264中的pps及sps数据
            if (isUsePpsAndSps) {
                // [0, 0, 0, 1, 103, 66, -128, 31, -38, 2, -48, 40, 105, 72, 40, 16, 16, 54, -123, 9, -88, 0, 0, 0, 1, 104, -50, 6, -30]
                byte[] header_sps = {0, 0, 0, 1, 103, 66, 0, 42, (byte) 149, (byte) 168, 30, 0, (byte) 137, (byte) 249, 102, (byte) 224, 32, 32, 32, 64};
                byte[] header_pps = {0, 0, 0, 1, 104, (byte) 206, 60, (byte) 128, 0, 0, 0, 1, 6, (byte) 229, 1, (byte) 151, (byte) 128};
                mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(header_sps));
                mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(header_pps));
            }
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
            mediaCodec.configure(mediaFormat, null, null, 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private int startIndex = 0;
    private int nextFrameStart;
    private byte[] mYuvBuffer;
    private final int mOutputFormat = COLOR_FORMAT_NV21;

    public void checkNaluAndPlay() {
        decoderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRun) {
                    if (BUFFER.size() == 0) {
                        continue;
                    }
                    if (startIndex >= BUFFER.size()) {
                        continue;
                    }
                    nextFrameStart = findFrame(startIndex + 1, BUFFER.size());
                    //Log.e("nextFrameStart", nextFrameStart + "");
                    if (nextFrameStart == -1) continue;
                    //Log.e("一帧的开始", startIndex + "");
                    int length = nextFrameStart - startIndex;
                    byte[] batch = getOneFrame(length);
                    Log.d("一帧的长度", String.valueOf(batch.length));
                    //startIndex = 0;
                    //往 ByteBuffer 中塞入数据
                    int index = mediaCodec.dequeueInputBuffer(10 * 1000);
                    Log.e("index", String.valueOf(index));
                    // 获取 dsp 成功
                    if (index >= 0) {
                        // 拿到可用的 ByteBuffer
                        ByteBuffer byteBuffer = mediaCodec.getInputBuffer(index);
                        if (byteBuffer != null) {
                            byteBuffer.clear();
                            //int length = nextFrameStart - startIndex;
                            Log.e("一帧的长度", length + "");
                            byteBuffer.put(batch, startIndex, length);
                            // 识别分隔符，找到分隔符对应的索引
                            mediaCodec.queueInputBuffer(index, 0, length, 0, 0);
                            startIndex = 0;
                        } else {
                            Log.e(TAG, "byteBuffer 为空");
                        }
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
                    while (outIndex >= 0) {
                        /*ByteBuffer outputBuffer;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            outputBuffer = mediaCodec.getOutputBuffer(outIndex);
                        } else {
                            outputBuffer = mediaCodec.getOutputBuffers()[outIndex];
                        }*/
                        //mediaCodec 没有绑定surface时候才可以获取到Image
                        Image outputImage = mediaCodec.getOutputImage(outIndex);
                        if (outputImage != null) {
                            //Log.e("TAG", "====>format" + outputImage.getFormat());
                            int width = mediaCodec.getOutputFormat().getInteger(MediaFormat.KEY_WIDTH);
                            int height = mediaCodec.getOutputFormat().getInteger(MediaFormat.KEY_HEIGHT);
                            //Log.e("TAG", "====>width" + width);
                            //Log.e("TAG", "====>height" + height);
                            int yuvLength = width * height * 3 / 2;
                            if (mYuvBuffer == null || mYuvBuffer.length != yuvLength) {
                                mYuvBuffer = new byte[yuvLength];
                            }
                            getDataFromImage(outputImage, mOutputFormat, width, height);
                            ImageUtil.YUVData yuvData = ImageUtil.splitNV21ToYUV(mYuvBuffer, width, height);
                            if (render != null) {
                                render.setYUVRenderData(width, height, yuvData.y, yuvData.u, yuvData.v);
                            }
                        }
                        mediaCodec.releaseOutputBuffer(outIndex, true);
                        outIndex = mediaCodec.dequeueOutputBuffer(info, 0);
                    }
                }
            }
        });
        decoderThread.start();
    }

    public final static int COLOR_FORMAT_I420 = 1;
    public final static int COLOR_FORMAT_NV21 = 2;
    public final static int COLOR_FORMAT_NV12 = 3;

    private void getDataFromImage(Image image, int colorFormat, int width, int height) {
        Rect crop = image.getCropRect();
        //int format = image.getFormat();
        Log.d(TAG, "crop width: " + crop.width() + ", height: " + crop.height());
        Image.Plane[] planes = image.getPlanes();

        byte[] rowData = new byte[planes[0].getRowStride()];

        int channelOffset = -1;
        int outputStride = -1;
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


    private void processFrame(byte[] data) {
        putBatch(data);
    }

    private final static int BUFFER_SIZE = 1024 * 1024; // 1MB
    private final List<Byte> BUFFER = new ArrayList<>();
    private final Lock LOCK = new ReentrantLock();
    private final Condition NOT_FULL = LOCK.newCondition();
    private final Condition NOT_EMPTY = LOCK.newCondition();


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
    private byte[] getOneFrame(int batchSize) {
        LOCK.lock();
        try {
            while (BUFFER.size() < batchSize) {
                NOT_EMPTY.await();
            }
            List<Byte> batchData = new ArrayList<>(BUFFER.subList(0, batchSize));
            Log.i("getOneFrame", "getBatch: " + BUFFER.size());
            BUFFER.subList(0, batchSize).clear();
            Log.i("getOneFrame", "剩余的长度： " + BUFFER.size());
            NOT_FULL.signal();
            //Log.i("getOneFrame", "getBatch: " + Arrays.toString(bytes).substring(0, 20));
            return Bytes.toArray(batchData);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            LOCK.unlock();
        }
    }


    private int findFrame(int startIndex, int totalSize) {
        for (int i = startIndex; i < totalSize - 4; i++) {
            if (BUFFER.get(i) == 0x00 && BUFFER.get(i + 1) == 0x00
                    && BUFFER.get(i + 2) == 0x00 && BUFFER.get(i + 3) == 0x01) {
                return i;
            }
        }
        return -1;
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

    public void releaseDecoder() {
        isRun = false;
        if (decoderThread != null) {
            decoderThread.interrupt();
        }
        if (receiverDataThread != null) {
            receiverDataThread.interrupt();
        }
        mediaCodec.stop();
        mediaCodec.release();
        mediaCodec = null;
        if (datagramSocket != null) {
            datagramSocket.disconnect();
        }
    }

}

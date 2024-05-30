package com.android.grafika;

import static android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
import static android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import com.android.grafika.utils.ImageUtil;
import com.android.grafika.utils.UdpSend;
import com.android.grafika.widget.AutoFitTextureView;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

public class TakeCaptureActivity extends Activity {

    private static final String TAG = "RHCamera2";

    private static final String TAG_PREVIEW = "预览";

    private static final SparseIntArray ORIENTATION = new SparseIntArray();

    private int m_width;
    private int m_height;

    static {
        ORIENTATION.append(Surface.ROTATION_0, 90);
        ORIENTATION.append(Surface.ROTATION_90, 0);
        ORIENTATION.append(Surface.ROTATION_180, 270);
        ORIENTATION.append(Surface.ROTATION_270, 180);
    }

    private String mCameraId;

    private Size mPreviewSize;

    private ImageReader mImageReader;

    private CameraDevice mCameraDevice;

    private CameraCaptureSession mCaptureSession;

    private CaptureRequest mPreviewRequest;

    private CaptureRequest.Builder mPreviewRequestBuilder;

    private AutoFitTextureView textureView;

    private Surface mPreviewSurface;
    private HandlerThread mBackgroundThread;
    private Handler mCameraBgHandler;
    private MediaCodec mediaCodec;
    private BufferedOutputStream outputStream;

    private UdpSend send = new UdpSend();

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mCameraBgHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mCameraBgHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // Surface状态回调
    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            m_width = width;
            m_height = height;
            setupCamera(width, height);
            configureTransform(width, height);
            openCamera();
            initVideoCodec();
            startEncode();
        }


        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    // 摄像头状态回调
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            mCameraDevice = camera;
            //开启预览
            startPreview();
        }


        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.i(TAG, "CameraDevice Disconnected");
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "CameraDevice Error");
        }
    };

    private CameraCaptureSession.CaptureCallback mPreviewCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {

        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {

        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_take_capture);
        textureView = findViewById(R.id.textureView);
        textureView.setSurfaceTextureListener(textureListener);

        findViewById(R.id.takePicture).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                captureContinuousPictures();
            }
        });

        initHandler();
        send.receiver();

    }

    private HandlerThread procThread;
    protected Handler procHandler;

    private void initHandler() {
        if (procThread == null) {
            procThread = new HandlerThread("视频处理线程");
            procThread.start();
            procHandler = new Handler(procThread.getLooper());
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        super.onPause();
    }

    private void setupCamera(int width, int height) {
        // 获取摄像头的管理者CameraManager
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        manager.registerAvailabilityCallback(new CameraManager.AvailabilityCallback() {
            @Override
            public void onCameraAvailable(@NonNull String cameraId) {
                super.onCameraAvailable(cameraId);
            }

            @Override
            public void onCameraUnavailable(@NonNull String cameraId) {
                super.onCameraUnavailable(cameraId);
            }
        }, mCameraBgHandler);

        try {
            // 遍历所有摄像头
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                // 默认打开后置摄像头 - 忽略前置摄像头
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
                    continue;
                // 获取StreamConfigurationMap，它是管理摄像头支持的所有输出格式和尺寸
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                mPreviewSize = getBestSupportedSize(new ArrayList<Size>(Arrays.asList(map.getOutputSizes(SurfaceTexture.class))));

                //                int orientation = getResources().getConfiguration().orientation;
                //                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                //                    textureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                //                } else {
                //                    textureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
                //                }


                mCameraId = cameraId;
                break;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    private void openCamera() {
        //获取摄像头的管理者CameraManager
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        //检查权限
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            startBackgroundThread();
            //打开相机，第一个参数指示打开哪个摄像头，第二个参数stateCallback为相机的状态回调接口，第三个参数用来确定Callback在哪个线程执行，为null的话就在当前线程执行
            manager.openCamera(mCameraId, stateCallback, mCameraBgHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        if (null != mCaptureSession) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (null != mCameraDevice) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (null != mImageReader) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == textureView || null == mPreviewSize) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    private void startPreview() {
        setupImageReader();
        SurfaceTexture mSurfaceTexture = textureView.getSurfaceTexture();
        //设置TextureView的缓冲区大小
        mSurfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        //获取Surface显示预览数据
        mPreviewSurface = new Surface(mSurfaceTexture);
        try {
            getPreviewRequestBuilder();
            //创建相机捕获会话，第一个参数是捕获数据的输出Surface列表，第二个参数是CameraCaptureSession的状态回调接口，当它创建好后会回调onConfigured方法，第三个参数用来确定Callback在哪个线程执行，为null的话就在当前线程执行
            mCameraDevice.createCaptureSession(Arrays.asList(mPreviewSurface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            mCaptureSession = session;
                            repeatPreview();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {

                        }
                    }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void repeatPreview() {
        mPreviewRequestBuilder.setTag(TAG_PREVIEW);
        mPreviewRequest = mPreviewRequestBuilder.build();
        //设置反复捕获数据的请求，这样预览界面就会一直有数据显示
        try {
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mPreviewCaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    boolean video = true;

    private void setupImageReader() {
        //前三个参数分别是需要的尺寸和格式，最后一个参数代表每次最多获取几帧数据
        mImageReader = ImageReader.newInstance(mPreviewSize.getWidth(), mPreviewSize.getHeight(), video ? ImageFormat.YUV_420_888 : ImageFormat.JPEG, 1);
        //监听ImageReader的事件，当有图像流数据可用时会回调onImageAvailable方法，它的参数就是预览帧数据，可以对这帧数据进行处理
        mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            private byte[] y;
            private byte[] u;
            private byte[] v;
            private final ReentrantLock lock = new ReentrantLock();

            @Override
            public void onImageAvailable(ImageReader reader) {
                final Image image = reader.acquireLatestImage();
                if (image == null) {
                    return;
                }
                try {
                    Log.i(TAG, "Image Available!");
                    Image.Plane[] planes = image.getPlanes();
                    int format = image.getFormat();
                    switch (format) {
                        case ImageFormat.JPEG:
                            Log.i(TAG, "format == JPEG");
                            break;
                        case ImageFormat.YUV_420_888:
                            Log.i(TAG, "format == YUV_420_888");
                            Log.i(TAG, "current Thread" + Thread.currentThread().getName());
                            Log.i(TAG, "getPixelStride = " + planes[0].getPixelStride());
                            Log.i(TAG, "getPixelStride = " + planes[1].getPixelStride());
                            Log.i(TAG, "getPixelStride = " + planes[2].getPixelStride());
                            procHandler.removeCallbacksAndMessages(null);
                            lock.lock();
                            if (y == null) {
                                y = new byte[planes[0].getBuffer().limit() - planes[0].getBuffer().position()];
                                u = new byte[planes[1].getBuffer().limit() - planes[1].getBuffer().position()];
                                v = new byte[planes[2].getBuffer().limit() - planes[2].getBuffer().position()];
                            }
                            if (image.getPlanes()[0].getBuffer().remaining() == y.length) {
                                planes[0].getBuffer().get(y);
                                planes[1].getBuffer().get(u);
                                planes[2].getBuffer().get(v);
                                onPreview(image, y, u, v, mPreviewSize, planes[0].getRowStride());
                            }
                            lock.unlock();
                            break;
                    }

                    Log.i(TAG, "Image Available!" + planes.length);
                } finally {
                    if (image != null) image.close();
                }


                // 开启线程异步保存图片
                // new Thread(new ImageSaver(image,getApplicationContext())).start();
            }
        }, mCameraBgHandler);
    }

    private byte[] nv21;
    private byte[] nv21_rotated;
    private byte[] nv12;

    public void onPreview(Image image, byte[] y, byte[] u, byte[] v, Size previewSize, int stride) {
        if (nv21 == null) {
            nv21 = new byte[stride * previewSize.getHeight() * 3 / 2];
            nv21_rotated = new byte[stride * previewSize.getHeight() * 3 / 2];
        }
        ImageUtil.yuvToNv21(y, u, v, nv21, stride, previewSize.getHeight());
        ImageUtil.nv21_rotate_to_90(nv21, nv21_rotated, stride, previewSize.getHeight());
        final byte[] temp = ImageUtil.nv21toNV12(nv21_rotated, nv12);

        //放入队列中
        putYUVData(temp);
        /*procHandler.post(new Runnable() {
            @Override
            public void run() {
                encode(temp);
            }
        });*/
    }

    private Point previewViewSize;

    private Size getBestSupportedSize(List<Size> sizes) {
        Point maxPreviewSize = new Point(1920, 1080);
        Point minPreviewSize = new Point(1280, 720);
        Size defaultSize = sizes.get(0);
        Size[] tempSizes = sizes.toArray(new Size[0]);
        Arrays.sort(tempSizes, new Comparator<Size>() {
            @Override
            public int compare(Size o1, Size o2) {
                if (o1.getWidth() > o2.getWidth()) {
                    return -1;
                } else if (o1.getWidth() == o2.getWidth()) {
                    return o1.getHeight() > o2.getHeight() ? -1 : 1;
                } else {
                    return 1;
                }
            }
        });
        sizes = new ArrayList<>(Arrays.asList(tempSizes));
        for (int i = sizes.size() - 1; i >= 0; i--) {
            if (maxPreviewSize != null) {
                if (sizes.get(i).getWidth() > maxPreviewSize.x || sizes.get(i).getHeight() > maxPreviewSize.y) {
                    sizes.remove(i);
                    continue;
                }
            }
            if (minPreviewSize != null) {
                if (sizes.get(i).getWidth() < minPreviewSize.x || sizes.get(i).getHeight() < minPreviewSize.y) {
                    sizes.remove(i);
                }
            }
        }
        if (sizes.size() == 0) {
            return defaultSize;
        }
        Size bestSize = sizes.get(0);
        float previewViewRatio;
        if (previewViewSize != null) {
            previewViewRatio = (float) previewViewSize.x / (float) previewViewSize.y;
        } else {
            previewViewRatio = (float) bestSize.getWidth() / (float) bestSize.getHeight();
        }

        if (previewViewRatio > 1) {
            previewViewRatio = 1 / previewViewRatio;
        }

        for (Size s : sizes) {
            if (Math.abs((s.getHeight() / (float) s.getWidth()) - previewViewRatio)
                    < Math.abs(bestSize.getHeight() / (float) bestSize.getWidth() - previewViewRatio)) {
                bestSize = s;
            }
        }
        return bestSize;
    }

    // 选择sizeMap中大于并且最接近width和height的size
    private Size getOptimalSize(Size[] sizeMap, int width, int height) {
        List<Size> sizeList = new ArrayList<>();
        for (Size option : sizeMap) {
            if (width > height) {
                if (option.getWidth() > width && option.getHeight() > height) {
                    sizeList.add(option);
                }
            } else {
                if (option.getWidth() > height && option.getHeight() > width) {
                    sizeList.add(option);
                }
            }
        }
        if (sizeList.size() > 0) {
            return Collections.min(sizeList, new Comparator<Size>() {
                @Override
                public int compare(Size lhs, Size rhs) {
                    return Long.signum(lhs.getWidth() * lhs.getHeight() - rhs.getWidth() * rhs.getHeight());
                }
            });
        }
        return sizeMap[0];
    }


    // 创建预览请求的Builder（TEMPLATE_PREVIEW表示预览请求）
    private void getPreviewRequestBuilder() {
        try {
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        //设置预览的显示界面
        mPreviewRequestBuilder.addTarget(mPreviewSurface);
        mPreviewRequestBuilder.addTarget(mImageReader.getSurface());
        MeteringRectangle[] meteringRectangles = mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AF_REGIONS);
        if (meteringRectangles != null && meteringRectangles.length > 0) {
            Log.d(TAG, "PreviewRequestBuilder: AF_REGIONS=" + meteringRectangles[0].getRect().toString());
        }
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
    }

    // 拍照
    private void capture() {
        try {
            //首先我们创建请求拍照的CaptureRequest
            final CaptureRequest.Builder mCaptureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            //获取屏幕方向
            int rotation = getWindowManager().getDefaultDisplay().getRotation();

            mCaptureBuilder.addTarget(mPreviewSurface);
            mCaptureBuilder.addTarget(mImageReader.getSurface());

            //设置拍照方向
            mCaptureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATION.get(rotation));

            //停止预览
            mCaptureSession.stopRepeating();

            mCaptureSession.capture(mCaptureBuilder.build(), captureCallback, mCameraBgHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    //开始拍照，然后回调上面的接口重启预览，因为mCaptureBuilder设置ImageReader作为target，所以会自动回调ImageReader的onImageAvailable()方法保存图片
    CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            repeatPreview();
        }
    };

    private void captureContinuousPictures() {
        try {
            //首先我们创建请求拍照的CaptureRequest
            //            final CaptureRequest.Builder mCaptureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            //            //获取屏幕方向
            //            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            //
            //            mCaptureBuilder.addTarget(mPreviewSurface);
            //            mCaptureBuilder.addTarget(mImageReader.getSurface());
            //
            //            //设置拍照方向
            //            mCaptureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATION.get(rotation));

            // 锁定AE调节，否则录像或视频画面在特定光线条件下会不停闪烁
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), captureCallback, mCameraBgHandler);
        } catch (Exception e) {
            Log.i("0000", "视频预览异常：" + e.getMessage());
        }
    }


    public static class ImageSaver implements Runnable {
        private Image mImage;
        private Context context;

        public ImageSaver(Image image, Context context) {
            mImage = image;
            this.context = context;
        }

        @Override
        public void run() {
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            File imageFile = new File(ContextCompat.getCodeCacheDir(context) + "/myPicture.jpg");
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(imageFile);
                fos.write(data, 0, data.length);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }


    void initVideoCodec() {

        Log.i(TAG, "initVideoCodec: getWidth" + mPreviewSize.getWidth());
        Log.i(TAG, "initVideoCodec: getHeight" + mPreviewSize.getHeight());

        MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", mPreviewSize.getHeight(), mPreviewSize.getWidth());
        //颜色空间设置为yuv420sp
        //mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FormatYUV420Flexible);
        //比特率，也就是码率 ，值越高视频画面更清晰画质更高
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 1000_000);
        //帧率，一般设置为30帧就够了
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
        createfile(getExternalCacheDir().getAbsolutePath() + File.separator + "test.h264");
    }


    long pts = 0;
    long generateIndex = 0;
    private byte[] configbyte;
    private static final int TIMEOUT_USEC = 12000;
    private static final int yuvqueuesize = 10;

    ArrayBlockingQueue<byte[]> YUVQueue = new ArrayBlockingQueue<byte[]>(yuvqueuesize);

    public void putYUVData(byte[] buffer) {
        if (YUVQueue.size() >= 10) {
            YUVQueue.poll();
        }
        YUVQueue.add(buffer);
        Log.i(TAG, "YUVQueue.size == : " + YUVQueue.size());
    }

    private volatile boolean isRunning;

    /**
     * 开始线程，开始编码
     */
    private void startEncode() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                isRunning = true;
                while (isRunning) {
                    if (YUVQueue.size() > 0) {
                        byte[] poll = YUVQueue.poll();
                        encode(poll);
                    }
                }
            }
        });
        thread.start();
    }


    void encode(byte[] input) {
        Log.i(TAG, "encode: " + input.length);
        try {
            long startMs = System.currentTimeMillis();
            //编码器输出缓冲区
            int inputBufferIndex = mediaCodec.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                pts = computePresentationTime(generateIndex);
                ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                inputBuffer.clear();
                //把转换后的YUV420格式的视频帧放到编码器输入缓冲区中
                inputBuffer.put(input);
                mediaCodec.queueInputBuffer(inputBufferIndex, 0, input.length, pts, 0);
                generateIndex += 1;
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
            while (outputBufferIndex >= 0) {
                ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex);
                byte[] outData = new byte[bufferInfo.size];
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
                    outputStream.write(keyframe, 0, keyframe.length);
                } else {
                    outputStream.write(outData, 0, outData.length);
                    Log.i(TAG, "encode: " + outData.length);
                }
                //send.sendH264Data(outData);
                //Thread.sleep(40);
                mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
                Log.i(TAG, "outputBufferIndex: =" + outputBufferIndex);
            }

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * 计算pts
     *
     * @param frameIndex
     * @return
     */
    private long computePresentationTime(long frameIndex) {
        return 132 + frameIndex * 1000_000 / 25;
    }


    private void createfile(String path) {
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


    @Override
    protected void onDestroy() {
        super.onDestroy();
        mCameraDevice.close();
        isRunning = false;
    }
}
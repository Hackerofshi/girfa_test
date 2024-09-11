package com.android.grafika.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
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
import java.util.concurrent.locks.ReentrantLock;

public class Camera2Utils {

    private final Activity activity;
    private final TextureView textureView;
    private final CameraManager manager;

    public Camera2Utils(Activity activity, TextureView textureView) {
        this.activity = activity;
        this.textureView = textureView;
            manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        initHandler();
    }

    private static final String TAG = "Camera2Utils";
    private HandlerThread mBackgroundThread;
    private Handler mCameraBgHandler;
    private String mCameraId;
    public Size mPreviewSize = new Size(1280, 720);
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CameraDevice mCameraDevice;
    private ImageReader mImageReader;
    private CameraCaptureSession mCaptureSession;
    private Surface mPreviewSurface;
    private static final String TAG_PREVIEW = "预览";
    private static final SparseIntArray ORIENTATION = new SparseIntArray();
    private HandlerThread procThread;
    protected Handler procHandler;
    private ImageResultCallback callback;

    static {
        ORIENTATION.append(Surface.ROTATION_0, 90);
        ORIENTATION.append(Surface.ROTATION_90, 0);
        ORIENTATION.append(Surface.ROTATION_180, 270);
        ORIENTATION.append(Surface.ROTATION_270, 180);
    }

    private void initHandler() {
        if (procThread == null) {
            procThread = new HandlerThread("视频处理线程");
            procThread.start();
            procHandler = new Handler(procThread.getLooper());
        }
    }

    public void setResultCallback(ImageResultCallback callback) {
        this.callback = callback;
    }

    // 摄像头状态回调
    public final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraDevice = camera;
            //开启预览
            startPreview(callback);
        }


        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.i(TAG, "CameraDevice Disconnected");
            closeCamera();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "CameraDevice Error");
        }
    };


    private final CameraCaptureSession.CaptureCallback mPreviewCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {

        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {

        }
    };

    private void startPreview(ImageResultCallback callback) {
        setupImageReader(callback);
        SurfaceTexture mSurfaceTexture = textureView.getSurfaceTexture();

        if (mSurfaceTexture == null) {
            Log.d(TAG, "mSurfaceTexture为null");
            return;
        }
        //设置TextureView的缓冲区大小
        mSurfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        //获取Surface显示预览数据
        mPreviewSurface = new Surface(mSurfaceTexture);
        try {
            getPreviewRequestBuilder();
            //创建相机捕获会话，第一个参数是捕获数据的输出Surface列表，第二个参数是CameraCaptureSession的状态回调接口，
            // 当它创建好后会回调onConfigured方法，第三个参数用来确定Callback在哪个线程执行，为null的话就在当前线程执行
            mCameraDevice.createCaptureSession(Arrays.asList(mPreviewSurface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mCaptureSession = session;
                            repeatPreview();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {

                        }
                    }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
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
        // 锁定AE调节，否则录像或视频画面在特定光线条件下会不停闪烁
        // mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);
        // mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

        // mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        // mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
    }


    public void openCamera() {
        //获取摄像头的管理者CameraManager
        //检查权限
        try {
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            startBackgroundThread();
            //打开相机，第一个参数指示打开哪个摄像头，第二个参数stateCallback为相机的状态回调接口，
            //第三个参数用来确定Callback在哪个线程执行，为null的话就在当前线程执行
            manager.openCamera(mCameraId, stateCallback, mCameraBgHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

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


    public void setupCamera(int width, int height) {
        Log.d(TAG, "setupCamera=" + width + height);
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

                mPreviewSize = new Size(1280, 720);

                if (map != null) {
                    Log.d(TAG, "setupCamera=" + map);

                    Size bestSupportedSize = getBestSupportedSize(new ArrayList<>(Arrays.asList(map.getOutputSizes(SurfaceTexture.class))));
                    Log.d(TAG, "bestSupportedSize=" + bestSupportedSize.getHeight());
                    Log.d(TAG, "bestSupportedSize=" + bestSupportedSize.getWidth());
                }

                //int orientation = getResources().getConfiguration().orientation;
                //if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                //    textureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                //} else {
                //    textureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
                //}
                mCameraId = cameraId;
                break;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        configureTransform(width, height);
    }


    public void configureTransform(int viewWidth, int viewHeight) {
        if (null == textureView || null == mPreviewSize) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
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


    private void repeatPreview() {
        mPreviewRequestBuilder.setTag(TAG_PREVIEW);
        CaptureRequest mPreviewRequest = mPreviewRequestBuilder.build();
        //设置反复捕获数据的请求，这样预览界面就会一直有数据显示
        try {
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mPreviewCaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    boolean video = true;
    private void setupImageReader(final ImageResultCallback callback) {
        //前三个参数分别是需要的尺寸和格式，最后一个参数代表每次最多获取几帧数据
        mImageReader = ImageReader.newInstance(mPreviewSize.getWidth(), mPreviewSize.getHeight(),
                video ? ImageFormat.YUV_420_888 : ImageFormat.JPEG, 3);
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
                    //Log.i(TAG, "Image Available!");
                    Image.Plane[] planes = image.getPlanes();
                    int format = image.getFormat();
                    switch (format) {
                        case ImageFormat.JPEG:
                            Log.i(TAG, "format == JPEG");
                            break;
                        case ImageFormat.YUV_420_888:
                            /*Log.i(TAG, "format == YUV_420_888");
                            Log.i(TAG, "current Thread" + Thread.currentThread().getName());
                            Log.i(TAG, "getPixelStride = " + planes[0].getPixelStride());

                            Log.i(TAG, "getPixelStride = " + planes[1].getPixelStride());
                            Log.i(TAG, "getPixelStride = " + planes[2].getPixelStride());*/
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
                                callback.callBack(image, y, u, v, mPreviewSize, planes[0].getRowStride());
                            }
                            lock.unlock();
                            break;
                    }
                } finally {
                    image.close();
                }
                // 开启线程异步保存图片
                // new Thread(new ImageSaver(image,getApplicationContext())).start();
            }
        }, mCameraBgHandler);
    }


    private void captureContinuousPictures() {
        try {
            //首先我们创建请求拍照的CaptureRequest
            //final CaptureRequest.Builder mCaptureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            ////获取屏幕方向
            //int rotation = getWindowManager().getDefaultDisplay().getRotation();
            //
            //mCaptureBuilder.addTarget(mPreviewSurface);
            //mCaptureBuilder.addTarget(mImageReader.getSurface());
            //
            ////设置拍照方向
            //mCaptureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATION.get(rotation));

            // 锁定AE调节，否则录像或视频画面在特定光线条件下会不停闪烁
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), captureCallback, mCameraBgHandler);
        } catch (Exception e) {
            Log.i("0000", "视频预览异常：" + e.getMessage());
        }
    }


    // 拍照
    private void capture() {
        try {
            //首先我们创建请求拍照的CaptureRequest
            final CaptureRequest.Builder mCaptureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            //获取屏幕方向
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();

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
            if (sizes.get(i).getWidth() > maxPreviewSize.x || sizes.get(i).getHeight() > maxPreviewSize.y) {
                sizes.remove(i);
                continue;
            }
            if (sizes.get(i).getWidth() < minPreviewSize.x || sizes.get(i).getHeight() < minPreviewSize.y) {
                sizes.remove(i);
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
                    return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
                }
            });
        }
        return sizeMap[0];
    }


    public static class ImageSaver implements Runnable {
        private final Image mImage;
        private final Context context;

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


    public void closeCamera() {
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

        stopBackgroundThread();
    }



    public interface ImageResultCallback {
        void callBack(Image image, byte[] y, byte[] u, byte[] v, Size previewSize, int stride);
    }


    private void createFile(String path) {
        File file = new File(path);
        if (file.exists()) {
            file.delete();
        }
        try {
            BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(file));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public final static int COLOR_FORMAT_I420 = 1;
    public final static int COLOR_FORMAT_NV21 = 2;
    public final static int COLOR_FORMAT_NV12 = 3;

    private byte[] mYuvBuffer;

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


    /**
     * 计算pts
     *
     * @param frameIndex 多少帧
     * @return 返回值
     */
    private long computePresentationTime(long frameIndex) {
        return 132 + frameIndex * 1000_000 / 25;
    }
}

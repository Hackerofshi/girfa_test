package com.android.grafika;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;



public class RecordH264Activity extends Activity implements View.OnClickListener {

    private static final String TAG = "Camera2Test";
    private static final int REQUEST_CAMERA_PERMISSION = 80;
    private TextureView mTextureView;
    private Button mBtnStart;
    private Button mBtnStop;
    private CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    private String mCameraId = "0";
    private Size previewSize; // 用于设置预览的宽高

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.e(TAG, "onCreate！");
        setContentView(R.layout.activity_record_h264);
        initData();
        intiView();
        initEvent();
    }

    private void intiView() {
        mTextureView = (TextureView) findViewById(R.id.textureView);
        mBtnStart = (Button) findViewById(R.id.btnStart);
        mBtnStop = (Button) findViewById(R.id.btnStop);
    }

    private void initData() {
        mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (mCameraManager == null) {
            Toast.makeText(this, "获取不到CameraService对象！", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void initEvent() {
        mBtnStart.setOnClickListener(this);
        mBtnStop.setOnClickListener(this);

        // 对预览View的状态监听
        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                Log.i(TAG, "onSurfaceTextureAvailable width = " + width + ",height = " + height);
                //1、当SurefaceTexture可用的时候，设置相机参数并打开相机
                initCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                Log.i(TAG, "onSurfaceTextureSizeChanged width = " + width + ",height = " + height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                Log.i(TAG, "onSurfaceTextureDestroyed！");
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                //正常预览的时候，会一直打印
                //Log.i(TAG, "onSurfaceTextureUpdated！");
            }
        });
    }


    @RequiresApi(api = Build.VERSION_CODES.M)
    private void initCamera() {
        Log.i(TAG, "initCamera");
        // 2.配置前置相机，获取尺寸及id
        getCameraIdAndPreviewSizeByFacing(CameraCharacteristics.LENS_FACING_FRONT); // 0为前置摄像头,Camera api1里面定义0为后置
        // 3.打开相机
        openCamera();
    }


    /*获取cameraId及相机预览的最佳尺寸*/
    private void getCameraIdAndPreviewSizeByFacing(int lensFacingFront) {
        Log.i(TAG, "getCameraIdAndPreviewSizeByFacing");
        try {
            String[] cameraIdList = mCameraManager.getCameraIdList(); //如果设备节点不可用，会阻塞在这里
            Log.i(TAG, "getCameraIdAndPreviewSizeByFacing cameraIdList = " + Arrays.toString(cameraIdList));
            for (String cameraId : cameraIdList) {

                CameraCharacteristics cameraCharacteristics = mCameraManager.getCameraCharacteristics(cameraId);
                int deviceLevel = cameraCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL); //硬件与api2的契合度，0-4
                Log.i(TAG, "deviceLevel = " + deviceLevel);
                int facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != lensFacingFront) {
                    continue;
                }
                StreamConfigurationMap streamConfigurationMap = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size[] outputSizes = streamConfigurationMap.getOutputSizes(SurfaceTexture.class);
                mCameraId = cameraId;
                previewSize = setOptimalPreviewSize(outputSizes, mTextureView.getMeasuredWidth(), mTextureView.getMeasuredHeight());
                Log.i(TAG, "最佳预览尺寸（w-h）：" + previewSize.getWidth() + "-" + previewSize.getHeight() + ",相机id：" + mCameraId);

            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "getCameraIdAndPreviewSizeByFacing error = " + e.getMessage());
        }
    }

    /**
     * 打开相机，预览是在回调里面执行的。
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    private void openCamera() {
        try {
            // 4.权限检查
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                requestCameraPermission();
                return;
            }

            // 5.真正打开相机
            Log.i(TAG, "openCamera");
            mCameraManager.openCamera(mCameraId, mStateCallback, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "openCamera error = " + e.getMessage());
        }

    }

    //请求相机权限
    @RequiresApi(api = Build.VERSION_CODES.M)
    private void requestCameraPermission() {
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
    }

    /**
     * 根据相机可用的预览尺寸和用户给定的TextureView的显示尺寸选择最接近的预览尺寸
     */
    private Size setOptimalPreviewSize(Size[] sizes, int previewViewWidth, int previewViewHeight) {
        List<Size> bigEnoughSizes = new ArrayList<>();
        List<Size> notBigEnoughSizes = new ArrayList<>();
        for (Size size : sizes) {
            if (size.getWidth() >= previewViewWidth && size.getHeight() >= previewViewHeight) {
                bigEnoughSizes.add(size);
            } else {
                notBigEnoughSizes.add(size);
            }
        }
        if (bigEnoughSizes.size() > 0) {
            return Collections.min(bigEnoughSizes, new Comparator<Size>() {
                @Override
                public int compare(Size lhs, Size rhs) {
                    return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                            (long) rhs.getWidth() * rhs.getHeight());
                }
            });
        } else if (notBigEnoughSizes.size() > 0) {
            return Collections.max(notBigEnoughSizes, new Comparator<Size>() {
                @Override
                public int compare(Size lhs, Size rhs) {
                    return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                            (long) rhs.getWidth() * rhs.getHeight());
                }
            });
        } else {
            Log.e(TAG, "未找到合适的预览尺寸");
            return sizes[0];
        }
    }

    //关闭相机，释放对象
    private void releaseCamera() {
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }

    /**
     * 相机状态监听对象
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            Log.i(TAG, "StateCallback！ onOpened");
            mCameraDevice = camera; // 打开成功，保存代表相机的CameraDevice实例
            SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(mTextureView.getWidth(), mTextureView.getHeight());
            final Surface surface = new Surface(surfaceTexture);
            ArrayList<Surface> previewList = new ArrayList<>();
            previewList.add(surface);
            try {
                // 6.将TextureView的surface传递给CameraDevice
                mCameraDevice.createCaptureSession(previewList, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        try {
                            CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            builder.addTarget(surface); // 必须设置才能正常预览
                            CaptureRequest captureRequest = builder.build();

                            // 7.CameraCaptureSession与CaptureRequest绑定（这是最后一步，已可显示相机预览）
                            session.setRepeatingRequest(captureRequest, mSessionCaptureCallback, null);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "createCaptureRequest error = " + e.getMessage());
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        Log.e(TAG, "onConfigureFailed");
                    }
                }, null);
            } catch (CameraAccessException e) {
                Log.e(TAG, "createCaptureSession error = " + e.getMessage());
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.e(TAG, "StateCallback！ onDisconnected camera.getId() = " + camera.getId());
            releaseCamera();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "StateCallback camera.getId() = " + camera.getId() + " , error = " + error);
            releaseCamera();
        }
    };

    //预览情况回调
    private final CameraCaptureSession.CaptureCallback mSessionCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            super.onCaptureStarted(session, request, timestamp, frameNumber);
            // 正常预览会一直刷新
            //Log.i(TAG, "mSessionCaptureCallback onCaptureStarted frameNumber =" + frameNumber);
        }

        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partialResult) {
            super.onCaptureProgressed(session, request, partialResult);
            Log.i(TAG, "mSessionCaptureCallback onCaptureProgressed request =" + request);
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
            Log.e(TAG, "mSessionCaptureCallback onCaptureFailed request =" + request);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            // 正常预览时会一直刷新
            //Log.i(TAG, "mSessionCaptureCallback onCaptureCompleted request =" + request);
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btnStart:
                Log.i(TAG, "btnStart！");
                openCamera();
                break;
            case R.id.btnStop:
                Log.i(TAG, "btnStop！");
                releaseCamera();
                break;
            default:
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseCamera();
    }
}

package com.android.grafika;

import android.Manifest;
import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Size;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;

import com.android.grafika.utils.Camera2Utils;
import com.android.grafika.utils.MediaUtils;
import com.android.grafika.widget.AutoFitTextureView;
import com.blankj.utilcode.util.LogUtils;


public class TakeCaptureActivity extends Activity {

    //private final UdpSend send = new UdpSend();
    private Camera2Utils camera2Utils;
    private MediaUtils mediaUtils;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_take_capture);
        AutoFitTextureView textureView = findViewById(R.id.textureView);
        textureView.setSurfaceTextureListener(textureListener);

        findViewById(R.id.takePicture).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //captureContinuousPictures();
                mediaUtils.splitVideo();
            }
        });
        mediaUtils = new MediaUtils(this);
        camera2Utils = new Camera2Utils(this, textureView);
        camera2Utils.setResultCallback(new Camera2Utils.ImageResultCallback() {
            @Override
            public void callBack(Image image, byte[] y, byte[] u, byte[] v, Size previewSize, int stride) {
                mediaUtils.onPreview(image, y, u, v, previewSize, stride);
            }
        });
        //send.receiver();
        requestPermission();
    }


    // Surface状态回调
    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            camera2Utils.setupCamera(width, height);
            camera2Utils.openCamera();
            mediaUtils.initVideoCodec(camera2Utils.mPreviewSize);
            mediaUtils.startEncode();
        }


        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            camera2Utils.configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

        }
    };

    @Override
    protected void onPause() {
        super.onPause();

    }

    public void requestPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
    }

    @Override
    protected void onStop() {
        super.onStop();
        LogUtils.i("onStop");
    }

    @Override
    protected void onDestroy() {
        LogUtils.i("onDestroy");
        super.onDestroy();
        mediaUtils.stopMuxer();
        mediaUtils.setRunning(false);
        mediaUtils.release();
        //mCameraDevice.close();
        //stopBackgroundThread();
        camera2Utils.closeCamera();
    }
}
package com.android.grafika.splicingui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Surface;
import android.widget.ImageView;



import com.android.grafika.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

// MainActivity.java
public class M2Activity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CAMERA = 1;
    private GLSurfaceView glSurfaceView;
    private CameraRenderer renderer;
    private CameraManager cameraManager;
    private List<String> cameraIds;
    private List<CameraDevice> cameras = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA},
                        PERMISSION_REQUEST_CAMERA);
            } else {
                initializeCamera();
            }
        }
    }

    private void initializeCamera() {
        setContentView(R.layout.activity_m2);

        glSurfaceView = findViewById(R.id.gls);
        final ImageView imageView = findViewById(R.id.image);
        renderer = new CameraRenderer(this, new CameraRenderer.BitmapCallBack() {
            @Override
            public void callback(final Bitmap bitmap) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        imageView.setImageBitmap(bitmap);
                    }
                });
            }
        });
        glSurfaceView.setEGLContextClientVersion(2);
        glSurfaceView.setRenderer(renderer);

        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            cameraIds = Arrays.asList(cameraManager.getCameraIdList());
            for (int i = 0; i < Math.min(4, cameraIds.size()); i++) {
                openCamera(cameraIds.get(i), i);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void openCamera(String cameraId, final int index) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED) {
                    cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(@NonNull CameraDevice camera) {
                            cameras.add(camera);
                            createCameraPreviewSession(camera, index);
                        }

                        @Override
                        public void onDisconnected(@NonNull CameraDevice camera) {
                            camera.close();
                            cameras.remove(camera);
                        }

                        @Override
                        public void onError(@NonNull CameraDevice camera, int error) {
                            camera.close();
                            cameras.remove(camera);
                        }
                    }, null);
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void createCameraPreviewSession(CameraDevice camera, int index) {
        try {
            SurfaceTexture surfaceTexture = renderer.getSurfaceTexture(index);
            surfaceTexture.setDefaultBufferSize(1280, 720);
            Surface surface = new Surface(surfaceTexture);

            final CaptureRequest.Builder previewRequestBuilder =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            camera.createCaptureSession(Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                session.setRepeatingRequest(previewRequestBuilder.build(),
                                        null, null);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        }
                    }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void processFrameData(byte[] data, int width, int height) {
        // 在这里处理接收到的摄像头数据
        // 示例：将数据发送到其他处理模块
        sendToProcessor(data);
    }

    private void sendToProcessor(byte[] data) {
        // 示例：发送数据到处理模块
        // processorManager.processData(data);
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (CameraDevice camera : cameras) {
            camera.close();
        }
        cameras.clear();
        glSurfaceView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        glSurfaceView.onResume();
        if (cameras.isEmpty()) {
            initializeCamera();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}

// CameraGLSurfaceView.java


// CameraRenderer.java
class CameraRenderer implements GLSurfaceView.Renderer {
    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
                    "attribute vec4 aTextureCoord;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = aPosition;\n" +
                    "    vTextureCoord = aTextureCoord.xy;\n" +
                    "}";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                    "}";

    private final Context context;
    private int program;
    private final int[] textureIds = new int[4];
    private final SurfaceTexture[] surfaceTextures = new SurfaceTexture[4];
    private FloatBuffer vertexBuffer;
    private FloatBuffer textureBuffer;
    private boolean[] frameAvailable = new boolean[4];
    private final float[] transformMatrix = new float[16];

    // 顶点坐标
    private static final float[] VERTEX_DATA = {
            // 左上
            -1.0f, 1.0f, 0.0f,
            -1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f,
            // 右上
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f,
            1.0f, 1.0f, 0.0f,
            1.0f, 0.0f, 0.0f,
            // 左下
            -1.0f, 0.0f, 0.0f,
            -1.0f, -1.0f, 0.0f,
            0.0f, 0.0f, 0.0f,
            0.0f, -1.0f, 0.0f,
            // 右下
            0.0f, 0.0f, 0.0f,
            0.0f, -1.0f, 0.0f,
            1.0f, 0.0f, 0.0f,
            1.0f, -1.0f, 0.0f
    };

    // 纹理坐标
    private static final float[] TEXTURE_DATA = {
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            // 重复四次
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
    };
    // 用于读取像素的缓冲区
    private ByteBuffer pixelBuffer;

    private HandlerThread sendThread;
    private Handler sendHandler;
    private BitmapCallBack callBack;

    public CameraRenderer(Context context, BitmapCallBack callBack) {
        this.context = context;
        setupBuffers();


        if (sendThread == null) sendThread = new HandlerThread("保存图片线程");
        if (sendHandler == null) {
            sendThread.start();
            sendHandler = new Handler(sendThread.getLooper());
        }
        this.callBack = callBack;
    }

    private void setupBuffers() {
        vertexBuffer = ByteBuffer
                .allocateDirect(VERTEX_DATA.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vertexBuffer.put(VERTEX_DATA).position(0);

        textureBuffer = ByteBuffer
                .allocateDirect(TEXTURE_DATA.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        textureBuffer.put(TEXTURE_DATA).position(0);
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        program = createProgram();

        // 生成纹理
        GLES20.glGenTextures(4, textureIds, 0);
        for (int i = 0; i < 4; i++) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[i]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            surfaceTextures[i] = new SurfaceTexture(textureIds[i]);
            surfaceTextures[i].setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                    for (int j = 0; j < 4; j++) {
                        if (surfaceTexture == surfaceTextures[j]) {
                            frameAvailable[j] = true;
                            break;
                        }
                    }
                }
            });
        }
    }

    private int viewWidth;
    private int viewHeight;

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        viewWidth = width;
        viewHeight = height;

        // 分配像素读取的缓冲区
        // 4 通道 RGBA，每个通道 1 字节
        pixelBuffer = ByteBuffer.allocateDirect(width * height * 4);
        pixelBuffer.order(ByteOrder.nativeOrder());
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        for (int i = 0; i < 4; i++) {
            if (frameAvailable[i]) {
                surfaceTextures[i].updateTexImage();
                surfaceTextures[i].getTransformMatrix(transformMatrix);
                frameAvailable[i] = false;
            }

            drawTexture(i);
        }
        readPixelsAndCallback();
    }

    private byte[] data;

    /**
     * 从默认帧缓冲区读取屏幕像素
     */
    private void readPixelsAndCallback() {
        if (pixelBuffer == null) return;

        pixelBuffer.position(0);
        GLES20.glReadPixels(
                0,
                0,
                viewWidth,
                viewHeight,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                pixelBuffer
        );
        // 转为 byte[]
        if (data == null) {
            data = new byte[viewWidth * viewHeight * 4];
        }
        pixelBuffer.get(data);

        Log.i("TAG", "readPixelsAndCallback: " + data.length);

        //saveFile(data);
    }

    private void saveFile(final byte[] data) {
        sendHandler.post(new Runnable() {
            @Override
            public void run() {
                Bitmap outputBitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888);
                // 创建 Bitmap 来保存像素数据
                // 将读取的像素数据填充到 Bitmap
                byte[] pixelData = data;
                for (int y = 0; y < viewHeight; y++) {
                    for (int x = 0; x < viewWidth; x++) {
                        int pixelIndex = (y * viewWidth + x) * 4;

                        // 由于 OpenGL ES 是从左下角读取数据，这里需要翻转每一行的顺序
                        int flippedY = viewHeight - 1 - y;
                        int flippedPixelIndex = (flippedY * viewWidth + x) * 4;

                        // 获取 RGBA 数据（OpenGL ES 会按 RGBA 的顺序存储）
                        int r = pixelData[pixelIndex] & 0xFF;
                        int g = pixelData[pixelIndex + 1] & 0xFF;
                        int b = pixelData[pixelIndex + 2] & 0xFF;
                        int a = pixelData[pixelIndex + 3] & 0xFF;

                        // 设置到 Bitmap
                        outputBitmap.setPixel(x, flippedY, (a << 24) | (r << 16) | (g << 8) | b);
                    }
                }

                callBack.callback(outputBitmap);
            }
        });
    }


    private void drawTexture(int index) {
        GLES20.glUseProgram(program);

        int positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        int textureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
        int textureHandle = GLES20.glGetUniformLocation(program, "sTexture");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[index]);
        GLES20.glUniform1i(textureHandle, 0);

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glEnableVertexAttribArray(textureCoordHandle);

        vertexBuffer.position(index * 12);
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT,
                false, 0, vertexBuffer);

        textureBuffer.position(index * 8);
        GLES20.glVertexAttribPointer(textureCoordHandle, 2, GLES20.GL_FLOAT,
                false, 0, textureBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(textureCoordHandle);
    }

    private int createProgram() {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);

        return program;
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }

    public SurfaceTexture getSurfaceTexture(int index) {
        if (index >= 0 && index < surfaceTextures.length) {
            return surfaceTextures[index];
        }
        return null;
    }

    interface BitmapCallBack {
        void callback(Bitmap bitmap);
    }
}

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
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.view.Surface;
import android.widget.ImageView;



import com.android.grafika.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// CameraProcessor.java
class CameraProcessor1 {
    private static final String TAG = "CameraProcessor";
    private static final int WIDTH = 1920;    // 最终输出宽度
    private static final int HEIGHT = 1080;   // 最终输出高度
    private static final int CAMERA_WIDTH = 1280;  // 相机预览宽度
    private static final int CAMERA_HEIGHT = 720;  // 相机预览高度

    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;
    private EGLConfig eglConfig;

    private final Context context;
    private CameraManager cameraManager;
    private final List<String> cameraIds = new ArrayList<>();
    private final List<CameraDevice> cameras = new ArrayList<>();
    private final SurfaceTexture[] surfaceTextures = new SurfaceTexture[4];
    private final int[] textureIds = new int[4];

    private int program;
    private int[] frameBuffer = new int[1];
    private int[] renderBuffer = new int[1];
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    private ByteBuffer outputBuffer;
    private boolean isProcessing = false;
    private OnFrameDataListener frameDataListener;

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
                    "attribute vec2 aTexCoord;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = aPosition;\n" +
                    "    vTexCoord = aTexCoord;\n" +
                    "}";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
                    "}";

    private static final float[] VERTEX_DATA = {
            // 左上
            -1.0f,  1.0f, 0.0f,    // 左上角
            -1.0f,  0.0f, 0.0f,    // 左下角
            0.0f,  1.0f, 0.0f,    // 右上角
            0.0f,  0.0f, 0.0f,    // 右下角

            // 右上
            0.0f,  1.0f, 0.0f,    // 左上角
            0.0f,  0.0f, 0.0f,    // 左下角
            1.0f,  1.0f, 0.0f,    // 右上角
            1.0f,  0.0f, 0.0f,    // 右下角

            // 左下
            -1.0f,  0.0f, 0.0f,    // 左上角
            -1.0f, -1.0f, 0.0f,    // 左下角
            0.0f,  0.0f, 0.0f,    // 右上角
            0.0f, -1.0f, 0.0f,    // 右下角

            // 右下
            0.0f,  0.0f, 0.0f,    // 左上角
            0.0f, -1.0f, 0.0f,    // 左下角
            1.0f,  0.0f, 0.0f,    // 右上角
            1.0f, -1.0f, 0.0f     // 右下角
    };

    private static final float[] TEXTURE_COORDS = {
            // 左上
            0.0f, 0.0f,     // 左上角
            0.0f, 1.0f,     // 左下角
            1.0f, 0.0f,     // 右上角
            1.0f, 1.0f,     // 右下角

            // 右上
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,

            // 左下
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,

            // 右下
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
    };

    public interface OnFrameDataListener {
        void onFrameData(byte[] data, int width, int height);
    }

    public CameraProcessor1(Context context) {
        this.context = context;
        initBuffers();
    }

    private void initBuffers() {
        vertexBuffer = ByteBuffer
                .allocateDirect(VERTEX_DATA.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(VERTEX_DATA);
        vertexBuffer.position(0);

        texCoordBuffer = ByteBuffer
                .allocateDirect(TEXTURE_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(TEXTURE_COORDS);
        texCoordBuffer.position(0);

        outputBuffer = ByteBuffer
                .allocateDirect(WIDTH * HEIGHT * 4)
                .order(ByteOrder.nativeOrder());
    }

    private HandlerThread sendThread;
    private Handler sendHandler;

    public void initialize() {
        initEGL();
        initGL();
        initCameras();

        if (sendThread == null) sendThread = new HandlerThread("保存图片线程");
        if (sendHandler == null) {
            sendThread.start();
            sendHandler = new Handler(sendThread.getLooper());
        }
    }

    private void initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("Unable to get EGL14 display");
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("Unable to initialize EGL14");
        }

        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1,
                numConfigs, 0)) {
            throw new RuntimeException("Unable to find a suitable EGLConfig");
        }
        eglConfig = configs[0];

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig,
                EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("Unable to create EGL context");
        }

        int[] surfaceAttribs = {
                EGL14.EGL_WIDTH, WIDTH,
                EGL14.EGL_HEIGHT, HEIGHT,
                EGL14.EGL_NONE
        };
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig,
                surfaceAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("Unable to create EGL surface");
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new RuntimeException("Unable to make EGL current");
        }
    }

    private void initGL() {
        program = createProgram();
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
            surfaceTextures[i].setDefaultBufferSize(CAMERA_WIDTH, CAMERA_HEIGHT);
        }

        // 创建FBO和RBO
        GLES20.glGenFramebuffers(1, frameBuffer, 0);
        GLES20.glGenRenderbuffers(1, renderBuffer, 0);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer[0]);
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, renderBuffer[0]);
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_RGBA4, WIDTH, HEIGHT);

        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_RENDERBUFFER, renderBuffer[0]);

        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Framebuffer is not complete: " + status);
        }
    }

    private void initCameras() {
        try {
            cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String[] ids = cameraManager.getCameraIdList();
            for (int i = 0; i < Math.min(4, ids.length); i++) {
                cameraIds.add(ids[i]);
            }
            for (int i = 0; i < cameraIds.size(); i++) {
                openCamera(cameraIds.get(i), i);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void openCamera(String cameraId, final int index) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (context.checkSelfPermission(Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED) {
                    cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(@NonNull CameraDevice camera) {
                            cameras.add(camera);
                            createCaptureSession(camera, index);
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

    private void createCaptureSession(CameraDevice camera, int index) {
        try {
            Surface surface = new Surface(surfaceTextures[index]);
            final CaptureRequest.Builder builder =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);

            camera.createCaptureSession(Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                session.setRepeatingRequest(builder.build(), null, null);
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

    private int createProgram() {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            String error = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new RuntimeException("Program linking failed: " + error);
        }

        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);

        return program;
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compilation failed: " + error);
        }
        return shader;
    }
    private CameraRenderer.BitmapCallBack callBack;

    public void setFrameDataListener(OnFrameDataListener listener, CameraRenderer.BitmapCallBack callBack) {
        this.frameDataListener = listener;
        this.callBack = callBack;
    }

    public void startProcessing() {
        isProcessing = true;
    }

    public void stopProcessing() {
        isProcessing = false;
    }

    public void processFrame() {
        if (!isProcessing || frameDataListener == null) return;

        // 绑定FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer[0]);
        GLES20.glViewport(0, 0, WIDTH, HEIGHT);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 使用着色器程序
        GLES20.glUseProgram(program);

        // 获取着色器变量位置
        int positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        int texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord");
        int textureHandle = GLES20.glGetUniformLocation(program, "sTexture");

        // 启用顶点属性数组
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glEnableVertexAttribArray(texCoordHandle);

        // 渲染四个摄像头画面
        for (int i = 0; i < 4; i++) {
            // 更新纹理
            surfaceTextures[i].updateTexImage();

            // 设置纹理
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[i]);
            GLES20.glUniform1i(textureHandle, 0);

            // 设置顶点和纹理坐标
            vertexBuffer.position(i * 12);  // 每个四边形12个顶点数据 (4个点 * xyz坐标)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer);

            texCoordBuffer.position(i * 8);  // 每个四边形8个纹理坐标 (4个点 * uv坐标)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

            // 绘制
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        // 禁用顶点属性数组
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texCoordHandle);

        // 读取像素数据
        outputBuffer.position(0);
        GLES20.glReadPixels(0, 0, WIDTH, HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, outputBuffer);

        // 转为 byte[]
        if (data == null) {
            data = new byte[WIDTH * HEIGHT * 4];
        }
        outputBuffer.position(0);
        outputBuffer.get(data);

        // 转换为YUV格式并回调
        //byte[] yuvData = convertRGBAToYUV(data);
        //frameDataListener.onFrameData(yuvData, WIDTH, HEIGHT);
        saveFile(data);

        // 解绑FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }
    private byte[] data;
    private void saveFile(final byte[] data) {
        sendHandler.post(new Runnable() {
            @Override
            public void run() {
                Bitmap outputBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
                // 创建 Bitmap 来保存像素数据
                // 将读取的像素数据填充到 Bitmap
                for (int y = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                        int pixelIndex = (y * WIDTH + x) * 4;

                        // 由于 OpenGL ES 是从左下角读取数据，这里需要翻转每一行的顺序
                        int flippedY = HEIGHT - 1 - y;
                        int flippedPixelIndex = (flippedY * WIDTH + x) * 4;

                        // 获取 RGBA 数据（OpenGL ES 会按 RGBA 的顺序存储）
                        int r = data[pixelIndex] & 0xFF;
                        int g = data[pixelIndex + 1] & 0xFF;
                        int b = data[pixelIndex + 2] & 0xFF;
                        int a = data[pixelIndex + 3] & 0xFF;

                        // 设置到 Bitmap
                        outputBitmap.setPixel(x, flippedY, (a << 24) | (r << 16) | (g << 8) | b);
                    }
                }

                callBack.callback(outputBitmap);
            }
        });
    }



    private byte[] convertRGBAToYUV(byte[] rgbaData) {
        byte[] yuvData = new byte[WIDTH * HEIGHT * 3 / 2]; // YUV420P格式

        for (int i = 0; i < HEIGHT; i++) {
            for (int j = 0; j < WIDTH; j++) {
                int index = (i * WIDTH + j) * 4;
                int r = rgbaData[index] & 0xff;
                int g = rgbaData[index + 1] & 0xff;
                int b = rgbaData[index + 2] & 0xff;

                // RGB to Y
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yuvData[i * WIDTH + j] = (byte) (y > 255 ? 255 : (y < 0 ? 0 : y));

                // RGB to U and V (每2x2像素采样一次)
                if (i % 2 == 0 && j % 2 == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;

                    yuvData[WIDTH * HEIGHT + (i / 2) * (WIDTH / 2) + j / 2] =
                            (byte) (u > 255 ? 255 : (u < 0 ? 0 : u));
                    yuvData[WIDTH * HEIGHT + WIDTH * HEIGHT / 4 + (i / 2) * (WIDTH / 2) + j / 2] =
                            (byte) (v > 255 ? 255 : (v < 0 ? 0 : v));
                }
            }
        }

        return yuvData;
    }

    public void release() {
        stopProcessing();

        // 释放相机资源
        for (CameraDevice camera : cameras) {
            camera.close();
        }
        cameras.clear();

        // 释放纹理和SurfaceTexture
        for (int i = 0; i < 4; i++) {
            if (surfaceTextures[i] != null) {
                surfaceTextures[i].release();
            }
        }
        GLES20.glDeleteTextures(4, textureIds, 0);

        // 释放FBO和RBO
        GLES20.glDeleteFramebuffers(1, frameBuffer, 0);
        GLES20.glDeleteRenderbuffers(1, renderBuffer, 0);

        // 释放着色器程序
        GLES20.glDeleteProgram(program);

        // 释放EGL资源
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(eglDisplay, eglSurface);
        EGL14.eglDestroyContext(eglDisplay, eglContext);
        EGL14.eglTerminate(eglDisplay);
    }
}

// 使用示例：MainActivity.java
public class M4Activity extends AppCompatActivity {
    private CameraProcessor1 cameraProcessor;
    private Handler processHandler;
    private HandlerThread processThread;
    private static final int PROCESS_INTERVAL = 33; // 约30fps
    private ImageView imageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_m2);
        imageView = findViewById(R.id.image);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
                return;
            }
        }

        initProcessor();
    }

    private void initProcessor() {
        // 创建后台线程
        processThread = new HandlerThread("ProcessThread");
        processThread.start();
        processHandler = new Handler(processThread.getLooper());

        // 初始化处理器
        processHandler.post(new Runnable() {
            @Override
            public void run() {
                cameraProcessor = new CameraProcessor1(M4Activity.this);
                cameraProcessor.initialize();
                cameraProcessor.setFrameDataListener(new CameraProcessor1.OnFrameDataListener() {
                    @Override
                    public void onFrameData(byte[] data, int width, int height) {
                        processYUVData(data, width, height);
                    }
                }, new CameraRenderer.BitmapCallBack() {
                    @Override
                    public void callback(Bitmap bitmap) {
                        imageView.setImageBitmap(bitmap);

                    }
                });
                startProcessing();
            }
        });
    }


    private void startProcessing() {
        cameraProcessor.startProcessing();
        processHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (cameraProcessor != null) {
                    cameraProcessor.processFrame();
                    processHandler.postDelayed(this, PROCESS_INTERVAL);
                }
            }
        }, PROCESS_INTERVAL);
    }

    private void processYUVData(byte[] data, int width, int height) {
        // 在这里处理YUV数据
        // 例如: 进行图像处理、发送到其他模块等
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProcessor != null) {
            cameraProcessor.release();
            cameraProcessor = null;
        }
        if (processThread != null) {
            processThread.quitSafely();
            try {
                processThread.join();
                processThread = null;
                processHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
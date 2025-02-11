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
import android.opengl.GLES30;
import android.opengl.Matrix;
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


// CameraProcessor.java
class CameraProcessor {
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
    private final float[] MVPMatrix = new float[16];
    private boolean isProcessing = false;
    private OnFrameDataListener frameDataListener;

    private static final String VERTEX_SHADER =
            "#version 300 es\n" +
                    "layout(location = 0) in vec4 aPosition;\n" +
                    "layout(location = 1) in vec2 aTexCoord;\n" +
                    "out vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = aPosition;\n" +
                    "    vTexCoord = aTexCoord;\n" +
                    "}";

    private static final String FRAGMENT_SHADER =
            "#version 300 es\n" +
                    "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                    "precision mediump float;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "in vec2 vTexCoord;\n" +
                    "out vec4 fragColor;\n" +
                    "void main() {\n" +
                    "    fragColor = texture(sTexture, vTexCoord);\n" +
                    "}";

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
    public interface OnFrameDataListener {
        void onFrameData(byte[] data, int width, int height);
    }

    public CameraProcessor(Context context) {
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
                .allocateDirect(TEXTURE_DATA.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(TEXTURE_DATA);
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
                EGL14.EGL_RENDERABLE_TYPE, 0x0040,
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0);
        eglConfig = configs[0];

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                contextAttribs, 0);

        int[] surfaceAttribs = {
                EGL14.EGL_WIDTH, WIDTH,
                EGL14.EGL_HEIGHT, HEIGHT,
                EGL14.EGL_NONE
        };
        //eglCreateWindowSurface
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0);

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new RuntimeException("Unable to make EGL14 current");
        }
    }

    private void initGL() {
        program = createProgram();
        GLES30.glGenTextures(4, textureIds, 0);
        for (int i = 0; i < 4; i++) {
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[i]);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);

            surfaceTextures[i] = new SurfaceTexture(textureIds[i]);
            surfaceTextures[i].setDefaultBufferSize(CAMERA_WIDTH, CAMERA_HEIGHT);
        }

        // 创建FBO和RBO
        GLES30.glGenFramebuffers(1, frameBuffer, 0);
        GLES30.glGenRenderbuffers(1, renderBuffer, 0);

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frameBuffer[0]);
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, renderBuffer[0]);
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8,
                WIDTH, HEIGHT);
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_RENDERBUFFER, renderBuffer[0]);

        int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Framebuffer is not complete: " + status);
        }

        // 设置正交投影矩阵
        Matrix.setIdentityM(MVPMatrix, 0);
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
        int vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        int program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, vertexShader);
        GLES30.glAttachShader(program, fragmentShader);
        GLES30.glLinkProgram(program);

        GLES30.glDeleteShader(vertexShader);
        GLES30.glDeleteShader(fragmentShader);

        return program;
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, shaderCode);
        GLES30.glCompileShader(shader);
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
    private byte[] data;

    // CameraProcessor.java 继续前面的代码
    public void processFrame() {
        if (!isProcessing || frameDataListener == null) return;

        // 绑定FBO
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frameBuffer[0]);
        GLES30.glViewport(0, 0, WIDTH, HEIGHT);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);


        // 渲染四个摄像头画面
        for (int i = 0; i < 4; i++) {
            // 使用着色器程序
            GLES30.glUseProgram(program);


            // 获取着色器变量位置
            //int MVPMatrixHandle = GLES30.glGetUniformLocation(program, "uMVPMatrix");
            int textureHandle = GLES30.glGetUniformLocation(program, "sTexture");


            // 启用顶点属性数组
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glEnableVertexAttribArray(1);


            // 更新纹理
            surfaceTextures[i].updateTexImage();
            surfaceTextures[i].getTransformMatrix(MVPMatrix);

            // 设置MVP矩阵
            //GLES30.glUniformMatrix4fv(MVPMatrixHandle, 1, false, MVPMatrix, 0);

            // 设置纹理
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[i]);
            GLES30.glUniform1i(textureHandle, 0);

            // 计算当前四边形的顶点偏移
            int vertexOffset = i * 12; // 每个四边形12个顶点坐标 (4个点 * 3坐标)
            int texOffset = i * 8;     // 每个四边形8个纹理坐标 (4个点 * 2坐标)

            // 设置顶点坐标
            vertexBuffer.position(vertexOffset);
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, vertexBuffer);

            // 设置纹理坐标
            texCoordBuffer.position(texOffset);
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, texCoordBuffer);

            // 绘制当前四边形
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);


            // 禁用顶点属性数组
            GLES30.glDisableVertexAttribArray(0);
            GLES30.glDisableVertexAttribArray(1);
        }



        // 读取像素数据
        outputBuffer.position(0);
        GLES30.glReadPixels(0, 0, WIDTH, HEIGHT, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, outputBuffer);

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
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }


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


    interface BitmapCallBack {
        void callback(Bitmap bitmap);
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

                // RGB to U and V
                if (i % 2 == 0 && j % 2 == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;

                    yuvData[WIDTH * HEIGHT + (i / 2) * (WIDTH / 2) + j / 2] =
                            (byte) (u > 255 ? 255 : (u < 0 ? 0 : u));
                    yuvData[WIDTH * HEIGHT + WIDTH * HEIGHT / 4 +
                            (i / 2) * (WIDTH / 2) + j / 2] =
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
        GLES30.glDeleteTextures(4, textureIds, 0);

        // 释放FBO和RBO
        GLES30.glDeleteFramebuffers(1, frameBuffer, 0);
        GLES30.glDeleteRenderbuffers(1, renderBuffer, 0);

        // 释放着色器程序
        GLES30.glDeleteProgram(program);

        // 释放EGL资源
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(eglDisplay, eglSurface);
        EGL14.eglDestroyContext(eglDisplay, eglContext);
        EGL14.eglTerminate(eglDisplay);
    }
}

// 使用示例:
public class M3Activity extends AppCompatActivity {
    private CameraProcessor cameraProcessor;
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

        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.i(this.getClass().getSimpleName(), "run: ");
            }
        }).start();
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
                cameraProcessor = new CameraProcessor(M3Activity.this);
                cameraProcessor.initialize();
                cameraProcessor.setFrameDataListener(new CameraProcessor.OnFrameDataListener() {
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

        Log.d("TAG", data.length + "");
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

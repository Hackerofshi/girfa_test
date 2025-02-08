package com.android.grafika.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 一、FBO 的作用
 * 在 OpenGL ES 中，默认情况下渲染的结果会输出到系统提供的帧缓冲（通常就是屏幕显示区域）。而使用 FBO，你可以将渲染结果输出到自定义的离屏缓冲上，其主要用途包括：
 * <p>
 * 离屏渲染（Offscreen Rendering）
 * 将场景先渲染到 FBO 附加的纹理中，再利用该纹理进行后续处理或作为其他物体的贴图。
 * <p>
 * 后期处理（Post-Processing）
 * 利用 FBO 渲染出场景图像，然后对该图像进行模糊、边缘检测、颜色校正等滤镜处理，最后将处理结果显示到屏幕上。
 * <p>
 * 阴影映射、反射等效果
 * 先从特殊视角渲染场景（如光源视角获取深度图），存储到 FBO 中，再在最终渲染中使用该结果计算阴影或反射效果。
 * <p>
 * 二、示例思路
 * 在本示例中，我们采用两个渲染阶段：
 * <p>
 * 离屏渲染阶段（FBO 渲染）
 * <p>
 * 目标：将一个加载自资源的纹理图片通过一个简单的着色器绘制到一个全屏四边形上。
 * 过程：先创建 FBO，并附加一个颜色纹理作为渲染目标；在 FBO 上渲染时使用“场景着色器程序”，该程序采样我们加载的原始纹理图片，将它绘制到四边形上，结果写入 FBO 附加的纹理。
 * 屏幕渲染阶段
 * <p>
 * 目标：将 FBO 中的纹理作为输入，通过另一个着色器绘制到默认帧缓冲（屏幕）上。
 * 过程：解绑 FBO 后使用“屏幕着色器程序”绘制一个全屏四边形，采样 FBO 纹理显示离屏渲染结果。
 * 通过这种方式，可以实现“渲染到纹理”的效果，为后续的图像处理或特殊渲染效果打下基础。
 * <p>
 * <p>
 * FBO 与附件的创建
 * <p>
 * 在 onSurfaceChanged 中，根据视口尺寸创建 FBO，并生成一个颜色纹理（mFboTexture）用于存储离屏渲染结果。
 * 同时创建一个渲染缓冲对象（RBO），附加深度/模板数据，保证渲染过程中深度测试（如需要）。
 * 加载源纹理
 * <p>
 * 在 onSurfaceCreated 中调用 loadTexture(...) 方法，通过 BitmapFactory 解码资源文件，利用 GLUtils.texImage2D 将图片数据加载到 OpenGL 纹理中，作为后续渲染的输入。
 * 离屏渲染阶段
 * <p>
 * 在 onDrawFrame 中，首先绑定 FBO，并使用“场景着色器程序”（mSceneProgram）将源纹理绘制到一个全屏四边形上，结果写入 FBO 附加的颜色纹理。
 * 屏幕渲染阶段
 * <p>
 * 离屏渲染完成后解绑 FBO，绑定默认帧缓冲。使用“屏幕着色器程序”（mScreenProgram）将 FBO 中的纹理绘制到屏幕上，从而展示离屏渲染的效果。
 * 着色器程序
 * <p>
 * 场景着色器：接收顶点坐标和纹理坐标，采样输入纹理（uSourceTexture），将纹理图像直接输出。
 * 屏幕着色器：接收顶点与纹理坐标，采样 FBO 附加的纹理（uFboTexture），将离屏渲染结果显示到屏幕。
 */
public class FBORender implements GLSurfaceView.Renderer {
    private static final String TAG = "FboRenderer";

    private Context mContext;
    private int mImageResId; // 资源 ID，用于加载要渲染的纹理图片

    // FBO 相关变量
    private int mFbo;
    private int mFboTexture;
    private int mRbo; // 渲染缓冲对象，用于深度/模板测试

    // 两个着色器程序：一个用于离屏渲染（将源纹理绘制到 FBO 上），另一个用于将 FBO 的纹理绘制到屏幕上
    private int mSceneProgram;
    private int mScreenProgram;

    // 用于绘制全屏四边形的顶点数据：每个顶点包含二维坐标 (x, y) 和纹理坐标 (s, t)
    private FloatBuffer mQuadBuffer;
    private final float[] quadVertices = {
            //  x,     y,    s,   t
            -1.0f, 1.0f, 0.0f, 1.0f,
            -1.0f, -1.0f, 0.0f, 0.0f,
            1.0f, -1.0f, 1.0f, 0.0f,

            -1.0f, 1.0f, 0.0f, 1.0f,
            1.0f, -1.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 1.0f
    };

    // 渲染区域尺寸
    private int mWidth, mHeight;

    // 加载自资源的源纹理
    private int mSourceTexture;

    public FBORender(Context context, int imageResId) {
        mContext = context;
        mImageResId = imageResId;
    }

    @Override
    public void onSurfaceCreated(GL10 glUnused, EGLConfig config) {
        // 设置清除颜色
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        // 开启混合（如需要透明效果）
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);

        // 创建两个着色器程序
        mSceneProgram = createSceneProgram();
        mScreenProgram = createScreenProgram();

        // 初始化全屏四边形顶点缓冲
        mQuadBuffer = ByteBuffer.allocateDirect(quadVertices.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mQuadBuffer.put(quadVertices);
        mQuadBuffer.position(0);

        // 加载源纹理图片（如 PNG 或 JPG），用作离屏渲染的输入
        mSourceTexture = loadTexture(mContext, mImageResId);
    }

    @Override
    public void onSurfaceChanged(GL10 glUnused, int width, int height) {
        mWidth = width;
        mHeight = height;
        GLES30.glViewport(0, 0, width, height);

        // 创建 FBO 并附加一个颜色纹理和一个渲染缓冲对象（用于深度/模板测试）
        int[] fboIds = new int[1];
        GLES30.glGenFramebuffers(1, fboIds, 0);
        mFbo = fboIds[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mFbo);

        // 创建 FBO 附加的颜色纹理（用于存储离屏渲染结果）
        int[] textureIds = new int[1];
        GLES30.glGenTextures(1, textureIds, 0);
        mFboTexture = textureIds[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mFboTexture);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height,
                0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, mFboTexture, 0);

        // 创建渲染缓冲对象，用于深度/模板测试
        int[] rboIds = new int[1];
        GLES30.glGenRenderbuffers(1, rboIds, 0);
        mRbo = rboIds[0];
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, mRbo);
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH24_STENCIL8, width, height);
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_STENCIL_ATTACHMENT,
                GLES30.GL_RENDERBUFFER, mRbo);

        // 检查 FBO 是否完整
        int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO is not complete: " + status);
        }
        // 解绑 FBO，后续直接渲染到屏幕
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    @Override
    public void onDrawFrame(GL10 glUnused) {
        // ────────── 第一步：离屏渲染，将源纹理绘制到 FBO 上 ──────────

        // 绑定 FBO 并设置视口
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mFbo);
        GLES30.glViewport(0, 0, mWidth, mHeight);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);

        // 使用场景着色器程序，将源纹理绘制到全屏四边形上
        GLES30.glUseProgram(mSceneProgram);
        // 设置顶点属性：位置（location = 0）和纹理坐标（location = 1）
        mQuadBuffer.position(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, mQuadBuffer);
        GLES30.glEnableVertexAttribArray(0);
        mQuadBuffer.position(2);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, mQuadBuffer);
        GLES30.glEnableVertexAttribArray(1);

        // 将加载的源纹理绑定到纹理单元 0，并传递给着色器
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mSourceTexture);
        int loc = GLES30.glGetUniformLocation(mSceneProgram, "uSourceTexture");
        GLES30.glUniform1i(loc, 0);

        // 绘制全屏四边形，结果写入 FBO 附加的纹理中
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6);

        // ────────── 第二步：屏幕渲染，将 FBO 纹理绘制到默认帧缓冲（屏幕） ──────────

        // 解绑 FBO（即绑定默认帧缓冲），并重新设置视口
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, mWidth, mHeight);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);

        // 使用屏幕着色器程序，将 FBO 中的纹理显示到屏幕上
        GLES30.glUseProgram(mScreenProgram);
        mQuadBuffer.position(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, mQuadBuffer);
        GLES30.glEnableVertexAttribArray(0);
        mQuadBuffer.position(2);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, mQuadBuffer);
        GLES30.glEnableVertexAttribArray(1);

        // 将 FBO 附加的纹理绑定到纹理单元 0，并传递给屏幕着色器
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mFboTexture);
        int screenLoc = GLES30.glGetUniformLocation(mScreenProgram, "uFboTexture");
        GLES30.glUniform1i(screenLoc, 0);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6);
    }

    // ────────── 以下是着色器程序的创建和辅助函数 ──────────

    // 用于离屏渲染的场景着色器程序：将源纹理绘制到全屏四边形上
    private int createSceneProgram() {
        String vertexShaderCode =
                "#version 300 es\n" +
                        "layout(location = 0) in vec2 aPosition;\n" +
                        "layout(location = 1) in vec2 aTexCoord;\n" +
                        "out vec2 vTexCoord;\n" +
                        "void main() {\n" +
                        "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
                        "  vTexCoord = aTexCoord;\n" +
                        "}\n";

        String fragmentShaderCode =
                "#version 300 es\n" +
                        "precision mediump float;\n" +
                        "in vec2 vTexCoord;\n" +
                        "uniform sampler2D uSourceTexture;\n" +
                        "out vec4 fragColor;\n" +
                        "void main() {\n" +
                        "  fragColor = texture(uSourceTexture, vTexCoord);\n" +
                        "}\n";

        int vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode);
        int program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, vertexShader);
        GLES30.glAttachShader(program, fragmentShader);
        GLES30.glLinkProgram(program);
        return program;
    }

    // 用于屏幕渲染的着色器程序：将 FBO 纹理绘制到屏幕上
    private int createScreenProgram() {
        String vertexShaderCode =
                "#version 300 es\n" +
                        "layout(location = 0) in vec2 aPosition;\n" +
                        "layout(location = 1) in vec2 aTexCoord;\n" +
                        "out vec2 vTexCoord;\n" +
                        "void main() {\n" +
                        "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
                        "  vTexCoord = aTexCoord;\n" +
                        "}\n";

        String fragmentShaderCode =
                "#version 300 es\n" +
                        "precision mediump float;\n" +
                        "in vec2 vTexCoord;\n" +
                        "uniform sampler2D uFboTexture;\n" +
                        "out vec4 fragColor;\n" +
                        "void main() {\n" +
                        "  fragColor = texture(uFboTexture, vTexCoord);\n" +
                        "}\n";

        int vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode);
        int program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, vertexShader);
        GLES30.glAttachShader(program, fragmentShader);
        GLES30.glLinkProgram(program);
        return program;
    }

    // 辅助函数：编译着色器
    private int loadShader(int type, String shaderCode) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, shaderCode);
        GLES30.glCompileShader(shader);

        // 检查编译状态
        int[] compileStatus = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            Log.e(TAG, "Error compiling shader: " + GLES30.glGetShaderInfoLog(shader));
            GLES30.glDeleteShader(shader);
            shader = 0;
        }
        return shader;
    }

    // 辅助函数：从资源中加载纹理图片
    private int loadTexture(Context context, int resourceId) {
        final int[] textureIds = new int[1];
        GLES30.glGenTextures(1, textureIds, 0);
        if (textureIds[0] == 0) {
            Log.e(TAG, "Failed to generate texture");
            return 0;
        }
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;  // 不进行预缩放
        final Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), resourceId, options);
        if (bitmap == null) {
            Log.e(TAG, "Resource ID " + resourceId + " could not be decoded.");
            GLES30.glDeleteTextures(1, textureIds, 0);
            return 0;
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureIds[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
        return textureIds[0];
    }
}
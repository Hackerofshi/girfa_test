package com.android.grafika.render;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;

import com.android.grafika.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MyRender implements GLSurfaceView.Renderer {
    private int program;
    GLSurfaceView surfaceView;

    private int screenWidth = -1;
    private int screenHeight =   -1;

    private int width = -1;
    private int height = -1;
    private ByteBuffer y;
    private ByteBuffer u;
    private ByteBuffer v;
    private final float[] verCoords = {
//            1.0f, -1.0f,
//            -1.0f, -1.0f,
//            1.0f, 1.0f,
//            -1.0f, 1.0f
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
    };
    private final float[] textureCoords = {
//            1.0f, 0.0f,
//            0.0f, 0.0f,
//            1.0f, 1.0f,
//            0.0f, 1.0f
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
    };
    private final int BYTES_PER_FLOAT = 4;

    private int aPositionLocation;
    private int aTextureCoordLocation;
    private int mVertexMatrixHandler;
    private int samplerYLocation;
    private int samplerULocation;
    private int samplerVLocation;

    private FloatBuffer verCoorFB;
    private FloatBuffer textureCoorFB;

    private int[] textureIds;
    private Context context;

    public MyRender(Context context) {
        this.context = context;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        String vertexShader = OpenGLUtils.readRawTextFile(context, R.raw.vertex_shader);
        String fragShader = OpenGLUtils.readRawTextFile(context, R.raw.frag_shader);
        program = OpenGLUtils.loadProgram(vertexShader, fragShader);

        aPositionLocation = GLES20.glGetAttribLocation(program, "aPosition");
        mVertexMatrixHandler = GLES20.glGetUniformLocation(program, "uMatrix");
        aTextureCoordLocation = GLES20.glGetAttribLocation(program, "aTextureCoord");
        samplerYLocation = GLES20.glGetUniformLocation(program, "samplerY");
        samplerULocation = GLES20.glGetUniformLocation(program, "samplerU");
        samplerVLocation = GLES20.glGetUniformLocation(program, "samplerV");

        verCoorFB = ByteBuffer.allocateDirect(verCoords.length * BYTES_PER_FLOAT)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(verCoords);
        verCoorFB.position(0);

        textureCoorFB = ByteBuffer.allocateDirect(textureCoords.length * BYTES_PER_FLOAT)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(textureCoords);
        textureCoorFB.position(0);

        //对应Y U V 三个纹理
        textureIds = new int[3];
        GLES20.glGenTextures(3, textureIds, 0);

        for (int i = 0; i < 3; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[i]);

            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        }
    }


    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        screenWidth = width;
        screenHeight = height;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        Log.i("MyRender", "onDrawFrame: width=" + width + " height=" + height);
        if (width > 0 && height > 0 && y != null && u != null && v != null) {

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);

            initDefMatrix();

            GLES20.glEnableVertexAttribArray(aPositionLocation);
            GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 2 * BYTES_PER_FLOAT, verCoorFB);

            GLES20.glUniformMatrix4fv(mVertexMatrixHandler, 1, false, mMatrix, 0);

            GLES20.glEnableVertexAttribArray(aTextureCoordLocation);
            GLES20.glVertexAttribPointer(aTextureCoordLocation, 2, GLES20.GL_FLOAT, false, 2 * BYTES_PER_FLOAT, textureCoorFB);

            //激活纹理
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_LUMINANCE,
                    width,
                    height,
                    0,
                    GLES20.GL_LUMINANCE,
                    GLES20.GL_UNSIGNED_BYTE,
                    y
            );

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[1]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_LUMINANCE,
                    width / 2,
                    height / 2,
                    0,
                    GLES20.GL_LUMINANCE,
                    GLES20.GL_UNSIGNED_BYTE,
                    u
            );


            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[2]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_LUMINANCE,
                    width / 2,
                    height / 2,
                    0,
                    GLES20.GL_LUMINANCE,
                    GLES20.GL_UNSIGNED_BYTE,
                    v
            );

            GLES20.glUniform1i(samplerYLocation, 0);
            GLES20.glUniform1i(samplerULocation, 1);
            GLES20.glUniform1i(samplerVLocation, 2);

            y.clear();
            y = null;
            u.clear();
            u = null;
            v.clear();
            v = null;

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(aPositionLocation);
            GLES20.glDisableVertexAttribArray(aTextureCoordLocation);
        }
    }

    private float[] mMatrix = null;

    private float mWidthRatio = 1f;
    private float mHeightRatio = 1f;

    private void initDefMatrix() {
        if (mMatrix != null) return;
        if (width != -1 && height != -1 &&
                screenWidth != -1 && screenHeight != -1) {
            mMatrix = new float[16];
            float[] prjMatrix = new float[16];
            float originRatio = (float) width / height;
            float worldRatio = (float) screenWidth / screenHeight;
            if (screenWidth > screenHeight) {
                if (originRatio > worldRatio) {
                    mHeightRatio = originRatio / worldRatio;
                    Matrix.orthoM(
                            prjMatrix, 0,
                            -mWidthRatio, mWidthRatio,
                            -mHeightRatio, mHeightRatio,
                            3f, 5f
                    );
                } else {// 原始比例小于窗口比例，缩放高度度会导致高度超出，因此，高度以窗口为准，缩放宽度
                    mWidthRatio = worldRatio / originRatio;
                    Matrix.orthoM(
                            prjMatrix, 0,
                            -mWidthRatio, mWidthRatio,
                            -mHeightRatio, mHeightRatio,
                            3f, 5f
                    );
                }
            } else {
                if (originRatio > worldRatio) {
                    mHeightRatio = originRatio / worldRatio;
                    Matrix.orthoM(
                            prjMatrix, 0,
                            -mWidthRatio, mWidthRatio,
                            -mHeightRatio, mHeightRatio,
                            3f, 5f
                    );
                } else {// 原始比例小于窗口比例，缩放高度会导致高度超出，因此，高度以窗口为准，缩放宽度
                    mWidthRatio = worldRatio / originRatio;
                    Matrix.orthoM(
                            prjMatrix, 0,
                            -mWidthRatio, mWidthRatio,
                            -mHeightRatio, mHeightRatio,
                            3f, 5f
                    );
                }
            }

            //设置相机位置
            float[] viewMatrix = new float[16];
            Matrix.setLookAtM(
                    viewMatrix, 0,
                    0f, 0f, 5.0f,
                    0f, 0f, 0f,
                    0f, 1.0f, 0f
            );

            //计算变换矩阵
            Matrix.multiplyMM(mMatrix, 0, prjMatrix, 0, viewMatrix, 0);
        }
    }


    public void setSurfaceView(GLSurfaceView surfaceView) {
        this.surfaceView = surfaceView;
    }


    public void setYUVRenderData(int width, int height, byte[] y, byte[] u, byte[] v) {
        this.width = width;
        this.height = height;
        this.y = ByteBuffer.wrap(y);
        this.u = ByteBuffer.wrap(u);
        this.v = ByteBuffer.wrap(v);

        surfaceView.requestRender();
    }
}



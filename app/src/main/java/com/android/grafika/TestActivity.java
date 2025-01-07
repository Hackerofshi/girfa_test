package com.android.grafika;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.View;
import android.widget.ImageView;

import com.android.grafika.utils.VideoDecoder;

public class TestActivity extends Activity {

    private Bitmap thisBitMap;
    private ImageView viewById;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test);
        viewById = findViewById(R.id.image);
        final VideoDecoder videoDecoder = new VideoDecoder();
        videoDecoder.setOutputFormat(VideoDecoder.COLOR_FORMAT_NV12); // 设置输出nv12的数据

        new Thread(new Runnable() {
            @Override
            public void run() {
                videoDecoder.decode(getApplicationContext(), new VideoDecoder.DecodeCallback() {
                    @Override
                    public void onDecode(byte[] yuv, int width, int height, int frameCount, long presentationTimeUs) {

                    }

                    @Override
                    public void onFinish() {

                    }

                    @Override
                    public void onStop() {

                    }

                    @Override
                    public void callBitmap(Bitmap bitmap) {
                        thisBitMap = bitmap;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                viewById.setImageBitmap(thisBitMap);
                            }
                        });
                    }
                });
            }
        }).start();
        viewById.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                viewById.setImageBitmap(thisBitMap);
            }
        });

    }
}

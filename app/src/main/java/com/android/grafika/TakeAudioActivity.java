package com.android.grafika;

import android.app.Activity;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.View;

import com.android.grafika.utils.AudioUtil;

public class TakeAudioActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_take_audio);
        findViewById(R.id.take_audio).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!PermissionHelper.hasRecordPermission(TakeAudioActivity.this)) {
                    PermissionHelper.requestRecordPermission(TakeAudioActivity.this, false);
                    return;
                }
                AudioUtil.getInstance().startAudioRecord("test", TakeAudioActivity.this);
            }
        });
    }
}

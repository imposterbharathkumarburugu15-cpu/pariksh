package com.doomedams.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.doomedams.app.bridge.ExamNativeBridge;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ExamNativeBridge.class);
        super.onCreate(savedInstanceState);
    }
}

package com.example.app;

import android.app.Activity;
import android.os.Bundle;
import com.example.core.Greeter;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String greeting = Greeter.hi();
        if (greeting == null) {
            throw new RuntimeException("greeting missing");
        }
    }
}

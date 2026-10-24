package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/** The "Paisajes" screensaver started by Home itself: Back twice on the video wall, or the wall left idle. Any key closes it. */
public class LandscapeActivity extends Activity {

    private Landscapes.Scene scene;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scene = new Landscapes.Scene(this);
        setContentView(scene.root);
        getWindow().setBackgroundDrawable(null);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) c.hide(WindowInsets.Type.systemBars());
        scene.root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    @Override
    protected void onResume() {
        super.onResume();
        scene.start();
    }

    @Override
    protected void onPause() {
        scene.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        scene.release();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Volume keeps working; every other key wakes back to Home.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) return super.onKeyDown(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_HOME) return super.onKeyDown(keyCode, event);
        finish();
        overridePendingTransition(0, android.R.anim.fade_out);
        return true;
    }
}

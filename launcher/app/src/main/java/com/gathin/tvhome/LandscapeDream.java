package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * "Paisajes" as the system screensaver: `settings put secure screensaver_components
 * com.gathin.tvhome/.LandscapeDream`. Same scene as LandscapeActivity.
 */
public class LandscapeDream extends DreamService {

    private Landscapes.Scene scene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        scene = new Landscapes.Scene(this);
        setContentView(scene.root);
        getWindow().setBackgroundDrawable(null);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
    }

    @Override
    public void onDreamingStopped() {
        scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.release();
        super.onDetachedFromWindow();
    }
}

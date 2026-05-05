package com.mohan.pensieve;

import android.app.Application;
import com.google.android.material.color.DynamicColors;

/**
 * Application entry point.
 * DynamicColors.applyToActivitiesIfAvailable() hooks into every Activity's
 * onCreate() on Android 12+ and re-themes the app with the user's wallpaper
 * colour palette (Monet / Material You). On Android 11 and below it does
 * nothing, so the seed colour (#6750A4) is used as the fallback.
 */
public class PensieveApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}

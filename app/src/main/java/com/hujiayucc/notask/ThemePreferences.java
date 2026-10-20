package com.hujiayucc.notask;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;

/** Local-only appearance preferences; independent of Xposed/module activation. */
final class ThemePreferences {
    static final int SYSTEM = 0;
    static final int LIGHT = 1;
    static final int DARK = 2;
    private static final String FILE = "appearance";
    private static final String KEY = "theme_mode";

    private ThemePreferences() {}

    static int normalize(int value) {
        return value == LIGHT || value == DARK ? value : SYSTEM;
    }

    static int read(SharedPreferences preferences) {
        try {
            return normalize(preferences.getInt(KEY, SYSTEM));
        } catch (RuntimeException error) {
            return SYSTEM;
        }
    }

    static int read(Context context) {
        return read(context.getSharedPreferences(FILE, Context.MODE_PRIVATE));
    }

    static int nightMode(int mode) {
        if (mode == LIGHT) return AppCompatDelegate.MODE_NIGHT_NO;
        if (mode == DARK) return AppCompatDelegate.MODE_NIGHT_YES;
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    static void apply(Context context) {
        AppCompatDelegate.setDefaultNightMode(nightMode(read(context)));
    }

    static void select(Context context, int mode) {
        int normalized = normalize(mode);
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY, normalized).apply();
        AppCompatDelegate.setDefaultNightMode(nightMode(normalized));
    }
}

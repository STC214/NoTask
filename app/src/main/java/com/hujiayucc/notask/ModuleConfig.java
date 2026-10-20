package com.hujiayucc.notask;

import android.content.SharedPreferences;

public final class ModuleConfig {
    public static final String PREFERENCES_NAME = "config";
    private static final String ENABLED_KEY_PREFIX = "hide_enabled.";
    public static final int MODE_BACK = 1;
    public static final int MODE_BACKGROUND = 2;
    public static final int MODE_DIRECT = 3;

    // A new namespace preserves 1.4 users' direct-hide behavior despite obsolete 1.2 keys.
    public static String modeKey(String packageName) { return "hide_mode_v2." + packageName; }

    public static int normalizeMode(int mode) {
        return mode == MODE_BACK || mode == MODE_BACKGROUND ? mode : MODE_DIRECT;
    }

    public static int readMode(SharedPreferences preferences, String packageName) {
        if (preferences == null) return MODE_DIRECT;
        try { return normalizeMode(preferences.getInt(modeKey(packageName), MODE_DIRECT)); }
        catch (RuntimeException error) { return MODE_DIRECT; }
    }

    private ModuleConfig() {}

    public static String enabledKey(String packageName) {
        return ENABLED_KEY_PREFIX + packageName;
    }

    /** Existing scoped apps migrate from the old timing modes to always hidden. */
    public static boolean readEnabled(SharedPreferences preferences, String packageName) {
        if (preferences == null) {
            return true;
        }
        try {
            return preferences.getBoolean(enabledKey(packageName), true);
        } catch (RuntimeException ignored) {
            return true;
        }
    }
}

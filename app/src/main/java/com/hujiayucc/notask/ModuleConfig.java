package com.hujiayucc.notask;

import android.content.SharedPreferences;

public final class ModuleConfig {
    public static final String PREFERENCES_NAME = "config";
    private static final String ENABLED_KEY_PREFIX = "hide_enabled.";

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

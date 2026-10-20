package com.hujiayucc.notask;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import org.junit.Test;

public class ModuleConfigTest {
    @Test public void threeModesAreExclusiveAndUnknownValuesDefaultToDirect() {
        assertEquals(1, ModuleConfig.normalizeMode(ModuleConfig.MODE_BACK));
        assertEquals(2, ModuleConfig.normalizeMode(ModuleConfig.MODE_BACKGROUND));
        assertEquals(3, ModuleConfig.normalizeMode(ModuleConfig.MODE_DIRECT));
        assertEquals(3, ModuleConfig.normalizeMode(-1));
        assertEquals(3, ModuleConfig.normalizeMode(999));
        assertEquals(3, ModuleConfig.readMode(null, "example.app"));
    }
    @Test public void modeKeyDoesNotReuseObsoleteTimingPreferences() {
        assertEquals("hide_mode_v2.example.app", ModuleConfig.modeKey("example.app"));
    }
    @Test public void modeSelectionReadsPerPackageInteger() {
        for (int mode : new int[] {1, 2, 3}) {
            SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
                    (proxy, method, args) -> {
                        assertEquals("getInt", method.getName());
                        assertEquals("hide_mode_v2.example.app", args[0]);
                        assertEquals(3, args[1]);
                        return mode;
                    });
            assertEquals(mode, ModuleConfig.readMode(preferences, "example.app"));
        }
    }
    @Test public void enabledKeyUsesPackageNamespace() {
        assertEquals("hide_enabled.com.example.app", ModuleConfig.enabledKey("com.example.app"));
    }
    @Test public void oldScopesDefaultToAlwaysHidden() {
        assertTrue(ModuleConfig.readEnabled(null, "com.example.app"));
    }
    @Test public void explicitSwitchStateIsRespected() {
        for (boolean enabled : new boolean[] {false, true}) {
            SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
                    (proxy, method, args) -> {
                        assertEquals("getBoolean", method.getName());
                        assertEquals("hide_enabled.com.example.app", args[0]);
                        assertEquals(true, args[1]);
                        return enabled;
                    });
            assertEquals(enabled, ModuleConfig.readEnabled(preferences, "com.example.app"));
        }
    }
    @Test public void corruptPreferenceKeepsLegacyScopeHidden() {
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
                (proxy, method, args) -> { throw new ClassCastException("bad value"); });
        assertTrue(ModuleConfig.readEnabled(preferences, "com.example.app"));
    }
}

package com.hujiayucc.notask;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import org.junit.Test;

public class ModuleConfigTest {
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

package com.hujiayucc.notask;

import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ThemePreferencesTest {
    @Test public void unknownModesFollowSystem() {
        assertEquals(ThemePreferences.SYSTEM, ThemePreferences.normalize(999));
        assertEquals(ThemePreferences.SYSTEM, ThemePreferences.normalize(-1));
    }
    @Test public void explicitModesMapToAppCompat() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemePreferences.nightMode(ThemePreferences.LIGHT));
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemePreferences.nightMode(ThemePreferences.DARK));
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemePreferences.nightMode(ThemePreferences.SYSTEM));
    }
    @Test public void savedAppearanceIsReadWithoutXposed() {
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(preferences.getInt(eq("theme_mode"), eq(0))).thenReturn(ThemePreferences.DARK);
        assertEquals(ThemePreferences.DARK, ThemePreferences.read(preferences));
    }
    @Test public void malformedAppearanceFallsBackToSystem() {
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(preferences.getInt(anyString(), anyInt())).thenThrow(new ClassCastException());
        assertEquals(ThemePreferences.SYSTEM, ThemePreferences.read(preferences));
    }
}

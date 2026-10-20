package com.hujiayucc.notask;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class InstalledAppsTest {
    @Test public void disappearingPackageDoesNotDiscardOtherApplications() {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        when(context.getPackageManager()).thenReturn(pm);
        when(context.getPackageName()).thenReturn("com.hujiayucc.notask");
        ResolveInfo broken = launcher("broken.app"), good = launcher("good.app");
        when(broken.loadLabel(pm)).thenThrow(new IllegalStateException("package removed"));
        when(good.loadLabel(pm)).thenReturn("Good");
        when(pm.queryIntentActivities(any(Intent.class), anyInt())).thenReturn(Arrays.asList(broken, good));
        try (org.mockito.MockedConstruction<Intent> ignored = mockConstruction(Intent.class,
                (intent, construction) -> when(intent.addCategory(anyString())).thenReturn(intent))) {
            List<AppEntry> entries = InstalledApps.load(context);
            assertEquals(1, entries.size()); assertEquals("good.app", entries.get(0).packageName);
        }
    }

    private ResolveInfo launcher(String name) {
        ResolveInfo info = mock(ResolveInfo.class);
        info.activityInfo = mock(ActivityInfo.class);
        info.activityInfo.applicationInfo = mock(ApplicationInfo.class);
        info.activityInfo.packageName = name;
        return info;
    }
}

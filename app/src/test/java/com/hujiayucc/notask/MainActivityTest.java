package com.hujiayucc.notask;

import android.content.SharedPreferences;
import android.os.Handler;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import io.github.libxposed.service.XposedService;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MainActivityTest {
    @Test public void firstEnablePersistsBeforeRequestAndSurvivesDestroyedActivity() throws Exception {
        // Do not run Android constructors; exercise the real request and completion methods.
        MainActivity activity = mock(MainActivity.class, CALLS_REAL_METHODS);
        XposedService service = mock(XposedService.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class, RETURNS_SELF);
        when(preferences.edit()).thenReturn(editor);
        when(editor.commit()).thenReturn(true);
        set(activity, "service", service);
        set(activity, "preferences", preferences);
        set(activity, "scopedPackages", new HashSet<String>());
        set(activity, "adapter", mock(AppListAdapter.class));
        Handler handler = mock(Handler.class);
        when(handler.post(any(Runnable.class))).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return true;
        });
        set(activity, "mainHandler", handler);
        doReturn(true).when(activity).isDestroyed();
        activity.onScopeChanged(new AppEntry("Example", "example.app", null), true);
        ArgumentCaptor<XposedService.OnScopeEventListener> callback =
                ArgumentCaptor.forClass(XposedService.OnScopeEventListener.class);
        InOrder order = inOrder(editor, service);
        order.verify(editor).putBoolean(ModuleConfig.enabledKey("example.app"), true);
        order.verify(editor).commit();
        order.verify(service).requestScope(eq(Collections.singletonList("example.app")), callback.capture());
        callback.getValue().onScopeRequestApproved(Collections.singletonList("example.app"));
        // Late approval must not overwrite a newer switch state or depend on a live Activity.
        verify(editor, times(1)).commit();
        verify(editor, never()).putBoolean(anyString(), eq(false));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

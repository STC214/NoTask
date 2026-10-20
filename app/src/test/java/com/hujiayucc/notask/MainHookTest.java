package com.hujiayucc.notask;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MainHook/config code with mocked Android task service and framework transport. */
public class MainHookTest {
    private MainHook hook;
    private XposedInterface framework;
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final List<Runnable> queue = new ArrayList<>();
    private SharedPreferences.OnSharedPreferenceChangeListener listener;
    private SharedPreferences preferences;
    private ActivityManager manager;
    private ActivityManager.AppTask first;
    private ActivityManager.AppTask second;

    @Before public void setUp() throws Exception {
        preferences = mock(SharedPreferences.class);
        when(preferences.getBoolean(eq(ModuleConfig.enabledKey("example.app")), anyBoolean()))
                .thenAnswer(invocation -> enabled.get());
        framework = mock(XposedInterface.class);
        when(framework.getRemotePreferences(ModuleConfig.PREFERENCES_NAME)).thenReturn(preferences);
        hook = new MainHook();
        hook.attachFramework(framework, () -> {});
        XposedModuleInterface.ModuleLoadedParam param = mock(XposedModuleInterface.ModuleLoadedParam.class);
        when(param.getProcessName()).thenReturn("example.app");
        hook.onModuleLoaded(param);
        ArgumentCaptor<SharedPreferences.OnSharedPreferenceChangeListener> captor =
                ArgumentCaptor.forClass(SharedPreferences.OnSharedPreferenceChangeListener.class);
        verify(preferences).registerOnSharedPreferenceChangeListener(captor.capture());
        listener = captor.getValue();
        set("targetPackageName", "example.app");
        Handler handler = mock(Handler.class);
        when(handler.post(any(Runnable.class))).thenAnswer(invocation -> {
            queue.add(invocation.getArgument(0));
            return true;
        });
        set("mainHandler", handler);
        manager = mock(ActivityManager.class);
        first = mock(ActivityManager.AppTask.class);
        second = mock(ActivityManager.AppTask.class);
        when(manager.getAppTasks()).thenReturn(Arrays.asList(first, second));
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSystemService(ActivityManager.class)).thenReturn(manager);
        call("rememberContext", new Class<?>[] {Context.class}, context);
    }

    @Test public void enableImmediatelyHidesEveryExistingTaskWithoutLifecycleEvent() {
        change(true);
        drain();
        verify(first).setExcludeFromRecents(true);
        verify(second).setExcludeFromRecents(true);
    }

    @Test public void disableImmediatelyRestoresEveryTask() {
        change(true); drain();
        change(false); drain();
        verify(first).setExcludeFromRecents(false);
        verify(second).setExcludeFromRecents(false);
    }

    @Test public void rapidChangesApplyLatestStateNotStaleQueuedState() {
        change(true);
        change(false);
        drain();
        verify(first, never()).setExcludeFromRecents(true);
        verify(first, times(2)).setExcludeFromRecents(false);
    }

    @Test public void foregroundReentryKeepsTaskHidden() throws Exception {
        change(true); drain();
        call("applyVisibility", new Class<?>[] {String.class}, "onStart");
        call("applyVisibility", new Class<?>[] {String.class}, "onResume");
        verify(first, times(3)).setExcludeFromRecents(true);
        verify(first, never()).setExcludeFromRecents(false);
    }

    @Test public void removedTaskDoesNotPreventOtherTasksFromUpdating() {
        doThrow(new IllegalArgumentException("task removed")).when(first).setExcludeFromRecents(true);
        change(true); drain();
        verify(second).setExcludeFromRecents(true);
    }

    @Test public void unrelatedPackageChangeDoesNotUpdateTasks() {
        listener.onSharedPreferenceChanged(preferences, ModuleConfig.enabledKey("other.app"));
        drain();
        verifyNoInteractions(first, second);
    }

    @Test public void clearedPreferencesNotificationUsesCurrentState() {
        enabled.set(true);
        listener.onSharedPreferenceChanged(preferences, null);
        drain();
        verify(first).setExcludeFromRecents(true);
    }

    @Test public void noTasksYetIsAppliedWhenFirstActivityStarts() throws Exception {
        when(manager.getAppTasks()).thenReturn(new ArrayList<>());
        change(true); drain();
        when(manager.getAppTasks()).thenReturn(Arrays.asList(first));
        call("applyVisibility", new Class<?>[] {String.class}, "onStart");
        verify(first).setExcludeFromRecents(true);
    }

    @Test public void lifecycleHookRefreshesStateAndPreservesOriginalResult() throws Throwable {
        enabled.set(true); // Deliberately do not send a preference notification.
        XposedInterface.Hooker interceptor = lifecycleInterceptor();
        Application app = mock(Application.class);
        when(app.getApplicationContext()).thenReturn(app);
        when(app.getSystemService(ActivityManager.class)).thenReturn(manager);
        XposedInterface.Chain chain = mock(XposedInterface.Chain.class);
        when(chain.getThisObject()).thenReturn(app);
        Object originalResult = new Object();
        when(chain.proceed()).thenReturn(originalResult);
        org.junit.Assert.assertSame(originalResult, interceptor.intercept(chain));
        org.mockito.InOrder order = inOrder(chain, first);
        order.verify(chain).proceed();
        order.verify(first).setExcludeFromRecents(true);
    }

    @Test public void moduleContextFailureDoesNotBreakOriginalLifecycle() throws Throwable {
        XposedInterface.Hooker interceptor = lifecycleInterceptor();
        Application app = mock(Application.class);
        when(app.getApplicationContext()).thenThrow(new IllegalStateException("context unavailable"));
        XposedInterface.Chain chain = mock(XposedInterface.Chain.class);
        when(chain.getThisObject()).thenReturn(app);
        interceptor.intercept(chain);
        verify(chain).proceed();
    }

    @Test public void originalAppExceptionIsNotSwallowed() throws Throwable {
        XposedInterface.Hooker interceptor = lifecycleInterceptor();
        XposedInterface.Chain chain = mock(XposedInterface.Chain.class);
        RuntimeException original = new IllegalStateException("app error");
        when(chain.proceed()).thenThrow(original);
        try {
            interceptor.intercept(chain);
            org.junit.Assert.fail("Expected original exception");
        } catch (RuntimeException actual) {
            org.junit.Assert.assertSame(original, actual);
        }
        verifyNoInteractions(first, second);
    }

    private XposedInterface.Hooker lifecycleInterceptor() throws Exception {
        XposedInterface.HookBuilder builder = mock(XposedInterface.HookBuilder.class, RETURNS_SELF);
        when(framework.hook(any(Executable.class))).thenReturn(builder);
        call("registerVisibilityHook", new Class<?>[] {Class.class, String.class}, Application.class, "onCreate");
        ArgumentCaptor<XposedInterface.Hooker> captor = ArgumentCaptor.forClass(XposedInterface.Hooker.class);
        verify(builder).intercept(captor.capture());
        return captor.getValue();
    }

    private void change(boolean value) {
        enabled.set(value);
        listener.onSharedPreferenceChanged(preferences, ModuleConfig.enabledKey("example.app"));
    }

    private void drain() {
        List<Runnable> pending = new ArrayList<>(queue);
        queue.clear();
        pending.forEach(Runnable::run);
    }

    private void set(String name, Object value) throws Exception {
        Field field = MainHook.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(hook, value);
    }

    private void call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = MainHook.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(hook, args);
    }
}

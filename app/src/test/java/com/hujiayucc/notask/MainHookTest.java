package com.hujiayucc.notask;

import android.app.ActivityManager;
import android.app.Application;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.view.KeyEvent;
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
    private final java.util.concurrent.atomic.AtomicInteger mode =
            new java.util.concurrent.atomic.AtomicInteger(ModuleConfig.MODE_DIRECT);
    private final List<Runnable> delayed = new ArrayList<>();

    @Before public void setUp() throws Exception {
        preferences = mock(SharedPreferences.class);
        when(preferences.getBoolean(eq(ModuleConfig.enabledKey("example.app")), anyBoolean()))
                .thenAnswer(invocation -> enabled.get());
        when(preferences.getInt(eq(ModuleConfig.modeKey("example.app")), anyInt()))
                .thenAnswer(invocation -> mode.get());
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
        when(handler.postDelayed(any(Runnable.class), anyLong())).thenAnswer(invocation -> {
            delayed.add(invocation.getArgument(0)); return true;
        });
        doAnswer(invocation -> { delayed.remove(invocation.getArgument(0)); return null; })
                .when(handler).removeCallbacks(any(Runnable.class));
        manager = mock(ActivityManager.class);
        first = mock(ActivityManager.AppTask.class);
        second = mock(ActivityManager.AppTask.class);
        ActivityManager.RecentTaskInfo firstInfo = mock(ActivityManager.RecentTaskInfo.class);
        firstInfo.taskId = 17; firstInfo.persistentId = 17;
        ActivityManager.RecentTaskInfo secondInfo = mock(ActivityManager.RecentTaskInfo.class);
        secondInfo.taskId = 18; secondInfo.persistentId = 18;
        when(first.getTaskInfo()).thenReturn(firstInfo);
        when(second.getTaskInfo()).thenReturn(secondInfo);
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

    @Test public void modeNotificationImmediatelyReconcilesExistingTasks() throws Exception {
        change(true); drain(); clearInvocations(first, second);
        Activity activity = mock(Activity.class); when(activity.getTaskId()).thenReturn(17);
        call("handleLifecycle", new Class<?>[] {Activity.class, String.class}, activity, "onResume");
        mode.set(ModuleConfig.MODE_BACK);
        listener.onSharedPreferenceChanged(preferences, ModuleConfig.modeKey("example.app")); drain();
        verify(first).setExcludeFromRecents(false);
        mode.set(ModuleConfig.MODE_DIRECT);
        listener.onSharedPreferenceChanged(preferences, ModuleConfig.modeKey("example.app")); drain();
        verify(first).setExcludeFromRecents(true);
    }

    @Test public void rootBackHidesOnlyInBackMode() throws Exception {
        Activity activity = mock(Activity.class); when(activity.isTaskRoot()).thenReturn(true);
        when(activity.getTaskId()).thenReturn(17);
        mode.set(ModuleConfig.MODE_BACK); change(true); drain(); clearInvocations(first, second);
        call("handleBack", new Class<?>[] {Activity.class}, activity);
        verify(first).setExcludeFromRecents(true);
    }

    @Test public void backgroundTimerAndForegroundCancellationReachRealTaskService() throws Exception {
        Activity activity = mock(Activity.class);
        when(activity.getTaskId()).thenReturn(17);
        mode.set(ModuleConfig.MODE_BACKGROUND); change(true); drain();
        call("handleLifecycle", new Class<?>[] {Activity.class, String.class}, activity, "onResume");
        call("handleLifecycle", new Class<?>[] {Activity.class, String.class}, activity, "onPause");
        org.junit.Assert.assertTrue(delayed.isEmpty());
        call("handleLifecycle", new Class<?>[] {Activity.class, String.class}, activity, "onStop");
        org.junit.Assert.assertEquals(1, delayed.size());
        Runnable pending = delayed.get(0); pending.run();
        verify(first).setExcludeFromRecents(true);
        clearInvocations(first, second);
        call("handleLifecycle", new Class<?>[] {Activity.class, String.class}, activity, "onResume");
        call("applyVisibility", new Class<?>[] {String.class}, "resumed");
        verify(first).setExcludeFromRecents(false);
        clearInvocations(first, second);
        pending.run();
        verify(first, never()).setExcludeFromRecents(true);
    }

    @Test public void configurationChangeDoesNotScheduleBackgroundHide() throws Exception {
        Activity activity = mock(Activity.class); when(activity.isChangingConfigurations()).thenReturn(true);
        mode.set(ModuleConfig.MODE_BACKGROUND); change(true); drain();
        call("handleLifecycle", new Class<?>[] {Activity.class, String.class}, activity, "onResume");
        call("handleLifecycle", new Class<?>[] {Activity.class, String.class}, activity, "onPause");
        call("handleLifecycle", new Class<?>[] {Activity.class, String.class}, activity, "onStop");
        call("handleLifecycle", new Class<?>[] {Activity.class, String.class}, activity, "onDestroy");
        org.junit.Assert.assertTrue(delayed.isEmpty());
    }

    @Test public void startingReplacementActivityCancelsPendingBackgroundHide() throws Exception {
        Activity a = mock(Activity.class), b = mock(Activity.class);
        when(a.getTaskId()).thenReturn(17); when(b.getTaskId()).thenReturn(17);
        mode.set(ModuleConfig.MODE_BACKGROUND); change(true); drain();
        call("handleLifecycle", new Class<?>[] {Activity.class, String.class}, a, "onResume");
        call("handleLifecycle", new Class<?>[] {Activity.class, String.class}, a, "onStop");
        org.junit.Assert.assertEquals(1, delayed.size());
        call("handleLifecycle", new Class<?>[] {Activity.class, String.class}, b, "onStart");
        org.junit.Assert.assertTrue(delayed.isEmpty());
        call("applyVisibility", new Class<?>[] {String.class}, "replacement_started");
        verify(first, never()).setExcludeFromRecents(true);
    }

    @Test public void switchingModesCancelsPendingBackgroundHide() throws Exception {
        Activity activity = mock(Activity.class); when(activity.getTaskId()).thenReturn(17);
        mode.set(ModuleConfig.MODE_BACKGROUND); change(true); drain();
        call("handleLifecycle", new Class<?>[] {Activity.class, String.class}, activity, "onResume");
        call("handleLifecycle", new Class<?>[] {Activity.class, String.class}, activity, "onStop");
        org.junit.Assert.assertEquals(1, delayed.size());
        mode.set(ModuleConfig.MODE_BACK);
        listener.onSharedPreferenceChanged(preferences, ModuleConfig.modeKey("example.app")); drain();
        org.junit.Assert.assertTrue(delayed.isEmpty());
        verify(first, never()).setExcludeFromRecents(true);
        verify(first).setExcludeFromRecents(false);
    }

    @Test public void keyBackHookHidesBeforeOriginalAndDoesNotConsumeIt() throws Throwable {
        mode.set(ModuleConfig.MODE_BACK); change(true); drain(); clearInvocations(first, second);
        XposedInterface.HookBuilder builder = mock(XposedInterface.HookBuilder.class, RETURNS_SELF);
        when(framework.hook(any(Executable.class))).thenReturn(builder);
        call("registerBackHook", new Class<?>[] {String.class, Class[].class},
                "onKeyDown", new Class<?>[] {int.class, KeyEvent.class});
        ArgumentCaptor<XposedInterface.Hooker> captor = ArgumentCaptor.forClass(XposedInterface.Hooker.class);
        verify(builder).intercept(captor.capture());
        Activity activity = mock(Activity.class); when(activity.isTaskRoot()).thenReturn(true);
        when(activity.getTaskId()).thenReturn(17);
        KeyEvent event = mock(KeyEvent.class); when(event.getKeyCode()).thenReturn(KeyEvent.KEYCODE_BACK);
        when(event.getAction()).thenReturn(KeyEvent.ACTION_DOWN);
        XposedInterface.Chain chain = mock(XposedInterface.Chain.class);
        when(chain.getThisObject()).thenReturn(activity); when(chain.getArg(1)).thenReturn(event);
        when(chain.proceed()).thenReturn(Boolean.FALSE);
        org.junit.Assert.assertEquals(Boolean.FALSE, captor.getValue().intercept(chain));
        org.mockito.InOrder order = inOrder(first, chain);
        order.verify(first).setExcludeFromRecents(true); order.verify(chain).proceed();
    }

    @Test public void childActivityBackDoesNotHideTasks() throws Exception {
        mode.set(ModuleConfig.MODE_BACK); change(true); drain(); clearInvocations(first, second);
        Activity child = mock(Activity.class); when(child.isTaskRoot()).thenReturn(false);
        when(child.getTaskId()).thenReturn(17);
        call("handleBack", new Class<?>[] {Activity.class}, child);
        verify(first, never()).setExcludeFromRecents(true);
        verify(first).setExcludeFromRecents(false);
    }

    @Test public void serviceOnlyProcessDoesNotUnhideOtherProcessTimedTasks() {
        enabled.set(true);
        for (int timingMode : new int[] {ModuleConfig.MODE_BACK, ModuleConfig.MODE_BACKGROUND}) {
            mode.set(timingMode);
            listener.onSharedPreferenceChanged(preferences, ModuleConfig.modeKey("example.app")); drain();
        }
        verify(first, never()).setExcludeFromRecents(anyBoolean());
        verify(second, never()).setExcludeFromRecents(anyBoolean());
    }

    @Test public void disablingTimedModeRestoresAllTasksEvenWithoutLocalActivity() {
        mode.set(ModuleConfig.MODE_BACK); change(true); drain();
        change(false); drain();
        verify(first).setExcludeFromRecents(false);
        verify(second).setExcludeFromRecents(false);
    }

    @Test public void timedBackOnlyUpdatesTasksObservedInThisProcess() throws Exception {
        mode.set(ModuleConfig.MODE_BACK); change(true); drain(); clearInvocations(first, second);
        Activity activity = mock(Activity.class);
        when(activity.getTaskId()).thenReturn(17); when(activity.isTaskRoot()).thenReturn(true);
        call("handleBack", new Class<?>[] {Activity.class}, activity);
        verify(first).setExcludeFromRecents(true);
        verify(second, never()).setExcludeFromRecents(anyBoolean());
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

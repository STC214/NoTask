package com.hujiayucc.notask;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class MainHook extends XposedModule {
    private static final String TAG = "NoTask";
    private static final String MODULE_PACKAGE = "com.hujiayucc.notask";
    private final AtomicBoolean hooksInstalled = new AtomicBoolean(false);
    private volatile String processName = "";
    private volatile String targetPackageName = "";
    private volatile boolean systemServer;
    private final HidePolicy policy = new HidePolicy();
    private final Set<Integer> managedTaskIds = new HashSet<>();
    private Runnable pendingBackgroundHide;
    private SharedPreferences preferences;
    // Keep a strong reference: the remote preferences implementation may hold listeners weakly.
    private SharedPreferences.OnSharedPreferenceChangeListener preferencesListener;
    private Handler mainHandler;
    private Context applicationContext;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        systemServer = param.isSystemServer();
        try {
            preferences = getRemotePreferences(ModuleConfig.PREFERENCES_NAME);
            preferencesListener = (sharedPreferences, key) -> {
                String packageName = targetPackageName;
                if (!packageName.isEmpty()
                        && (key == null || ModuleConfig.enabledKey(packageName).equals(key)
                        || ModuleConfig.modeKey(packageName).equals(key))) {
                    refreshEnabled("preferences_changed");
                }
            };
            preferences.registerOnSharedPreferenceChangeListener(preferencesListener);
        } catch (RuntimeException error) {
            log(Log.WARN, TAG, "event=config_init result=fallback process=" + processName, error);
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String packageName = param.getPackageName();
        if (systemServer || !param.isFirstPackage() || MODULE_PACKAGE.equals(packageName)
                || !(processName.equals(packageName) || processName.startsWith(packageName + ":"))) {
            return;
        }
        if (!hooksInstalled.compareAndSet(false, true)) {
            return;
        }
        targetPackageName = packageName;
        mainHandler = new Handler(Looper.getMainLooper());
        refreshEnabled("package_ready");
        // Application context lets config changes update tasks even after all Activities are gone.
        int count = registerVisibilityHook(Application.class, "onCreate")
                + registerVisibilityHook(Activity.class, "onStart")
                + registerVisibilityHook(Activity.class, "onResume")
                + registerVisibilityHook(Activity.class, "onPause")
                + registerVisibilityHook(Activity.class, "onStop")
                + registerVisibilityHook(Activity.class, "onDestroy")
                + registerBackHook("onKeyDown", int.class, KeyEvent.class)
                + registerBackHook("dispatchKeyEvent", KeyEvent.class)
                + registerBackHook("onBackPressed");
        log(Log.INFO, TAG, "event=install_finished package=" + packageName + " hooks=" + count + "/9");
    }

    private void refreshEnabled(String reason) {
        if (targetPackageName.isEmpty()) {
            return;
        }
        Handler handler = mainHandler;
        if (handler != null) {
            // Serialize both reads and task updates: Binder callbacks may race across threads.
            handler.post(() -> {
                readConfiguration();
                applyVisibility(reason);
            });
        }
    }

    private int registerVisibilityHook(Class<?> type, String name) {
        try {
            Method method = type.getDeclaredMethod(name);
            hook(method)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Object receiver = chain.getThisObject();
                        Object result = chain.proceed();
                        if (receiver instanceof Context) {
                            try {
                                rememberContext((Context) receiver);
                                // Also refresh here in case a preference notification was delayed.
                                readConfiguration();
                                if (receiver instanceof Activity) {
                                    handleLifecycle((Activity) receiver, name);
                                }
                                applyVisibility(name);
                            } catch (RuntimeException error) {
                                log(Log.WARN, TAG, "event=lifecycle_visibility result=skip", error);
                            }
                        }
                        return result;
                    });
            return 1;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "event=hook_register_failed target=" + type.getName() + "." + name, error);
            return 0;
        }
    }

    private void readConfiguration() {
        if (policy.configure(ModuleConfig.readEnabled(preferences, targetPackageName),
                ModuleConfig.readMode(preferences, targetPackageName))) {
            cancelBackgroundHide();
        }
    }

    private void handleLifecycle(Activity activity, String event) {
        rememberTask(activity);
        switch (event) {
            case "onStart": policy.onStart(activity); cancelBackgroundHide(); break;
            case "onResume": policy.onResume(activity); cancelBackgroundHide(); break;
            case "onPause": policy.onPause(activity); break;
            case "onStop": policy.onStop(activity); break;
            case "onDestroy": policy.onDestroy(activity); break;
            default: break;
        }
        if ((event.equals("onPause") || event.equals("onStop") || event.equals("onDestroy"))
                && policy.backgroundCandidate() && !activity.isChangingConfigurations()) {
            scheduleBackgroundHide();
        }
    }

    private void scheduleBackgroundHide() {
        Handler handler = mainHandler;
        if (handler == null) return;
        cancelBackgroundHide();
        pendingBackgroundHide = () -> {
            pendingBackgroundHide = null;
            readConfiguration();
            policy.confirmBackground();
            applyVisibility("background_confirmed");
        };
        handler.postDelayed(pendingBackgroundHide, 180L);
    }

    private void cancelBackgroundHide() {
        if (mainHandler != null && pendingBackgroundHide != null) {
            mainHandler.removeCallbacks(pendingBackgroundHide);
        }
        pendingBackgroundHide = null;
    }

    private int registerBackHook(String name, Class<?>... parameters) {
        try {
            Method method = Activity.class.getMethod(name, parameters);
            hook(method).setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        try {
                            Object receiver = chain.getThisObject();
                            boolean back = parameters.length == 0;
                            if (parameters.length > 0) {
                                Object arg = chain.getArg(parameters.length - 1);
                                if (arg instanceof KeyEvent) {
                                    KeyEvent event = (KeyEvent) arg;
                                    back = event.getKeyCode() == KeyEvent.KEYCODE_BACK
                                            && event.getAction() == KeyEvent.ACTION_DOWN;
                                }
                            }
                            if (back && receiver instanceof Activity) handleBack((Activity) receiver);
                        } catch (RuntimeException error) {
                            log(Log.WARN, TAG, "event=back_visibility result=skip", error);
                        }
                        return chain.proceed(); // Never consume back or finish the Activity ourselves.
                    });
            return 1;
        } catch (Throwable error) {
            log(Log.WARN, TAG, "event=back_hook_failed target=" + name, error);
            return 0;
        }
    }

    private void handleBack(Activity activity) {
        rememberContext(activity);
        rememberTask(activity);
        readConfiguration();
        policy.onBack(activity.isTaskRoot());
        applyVisibility("back_pressed");
    }

    private void rememberTask(Activity activity) {
        int taskId = activity.getTaskId();
        if (taskId >= 0) managedTaskIds.add(taskId);
    }

    private void rememberContext(Context context) {
        Context app = context.getApplicationContext();
        if (app != null) {
            applicationContext = app;
        } else if (context instanceof Application) {
            applicationContext = context;
        }
    }

    @SuppressWarnings("deprecation")
    private void applyVisibility(String reason) {
        Context context = applicationContext;
        if (context == null) {
            return; // No app context yet; onCreate/onStart will apply the latest state.
        }
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null) {
                log(Log.WARN, TAG, "event=recents_visibility result=skip reason=no_activity_manager");
                return;
            }
            boolean exclude = policy.shouldHide();
            int count = 0;
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                try {
                    // Service-only processes must not undo timed-mode decisions made by UI processes.
                    if (!policy.appliesToAllTasks()) {
                        ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                        if (info == null) continue;
                        int id = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? info.taskId : info.persistentId;
                        if (!managedTaskIds.contains(id)) continue;
                    }
                    task.setExcludeFromRecents(exclude);
                    count++;
                } catch (RuntimeException error) {
                    // A removed task must not prevent the remaining tasks from being updated.
                    log(Log.WARN, TAG, "event=task_update result=skip package=" + targetPackageName, error);
                }
            }
            log(Log.INFO, TAG, "event=recents_visibility result=ok package=" + targetPackageName
                    + " state=" + (exclude ? "hidden" : "visible")
                    + " reason=" + reason + " tasks=" + count);
        } catch (RuntimeException error) {
            log(Log.ERROR, TAG, "event=recents_visibility result=failed package=" + targetPackageName, error);
        }
    }
}

package com.hujiayucc.notask;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private volatile boolean hideEnabled = true;
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
                        && (key == null || ModuleConfig.enabledKey(packageName).equals(key))) {
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
                + registerVisibilityHook(Activity.class, "onResume");
        log(Log.INFO, TAG, "event=install_finished package=" + packageName + " hooks=" + count + "/3");
    }

    private void refreshEnabled(String reason) {
        if (targetPackageName.isEmpty()) {
            return;
        }
        Handler handler = mainHandler;
        if (handler != null) {
            // Serialize both reads and task updates: Binder callbacks may race across threads.
            handler.post(() -> {
                hideEnabled = ModuleConfig.readEnabled(preferences, targetPackageName);
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
                                hideEnabled = ModuleConfig.readEnabled(preferences, targetPackageName);
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

    private void rememberContext(Context context) {
        Context app = context.getApplicationContext();
        if (app != null) {
            applicationContext = app;
        } else if (context instanceof Application) {
            applicationContext = context;
        }
    }

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
            boolean exclude = hideEnabled;
            int count = 0;
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                try {
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

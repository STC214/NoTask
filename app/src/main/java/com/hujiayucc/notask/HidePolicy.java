package com.hujiayucc.notask;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Main-thread state machine. No Android dependencies; visibility is separate from injection. */
final class HidePolicy {
    // Paused Activities can remain visible (dialogs, split screen, translucent windows).
    private final Set<Object> started = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean enabled = true;
    private int mode = ModuleConfig.MODE_DIRECT;
    private boolean observed;
    private boolean backHidden;
    private boolean backgroundHidden;

    boolean configure(boolean nextEnabled, int nextMode) {
        nextMode = ModuleConfig.normalizeMode(nextMode);
        if (enabled == nextEnabled && mode == nextMode) return false;
        enabled = nextEnabled;
        mode = nextMode;
        backHidden = false;
        backgroundHidden = observed && started.isEmpty();
        return true;
    }

    void onStart(Object activity) {
        observed = true;
        started.add(activity);
        backHidden = false;
        backgroundHidden = false;
    }

    void onResume(Object activity) {
        observed = true;
        started.add(activity);
        backHidden = false;
        backgroundHidden = false;
    }

    void onPause(Object activity) { /* Still visible until onStop. */ }
    void onStop(Object activity) { started.remove(activity); }
    void onDestroy(Object activity) { onStop(activity); }

    boolean backgroundCandidate() {
        return enabled && mode == ModuleConfig.MODE_BACKGROUND && observed && started.isEmpty();
    }

    void confirmBackground() {
        if (backgroundCandidate()) backgroundHidden = true;
    }

    void onBack(boolean taskRoot) {
        if (enabled && mode == ModuleConfig.MODE_BACK && taskRoot) backHidden = true;
    }

    boolean shouldHide() {
        if (!enabled) return false;
        if (mode == ModuleConfig.MODE_DIRECT) return true;
        return mode == ModuleConfig.MODE_BACK ? backHidden : backgroundHidden;
    }

    boolean appliesToAllTasks() { return !enabled || mode == ModuleConfig.MODE_DIRECT; }
}

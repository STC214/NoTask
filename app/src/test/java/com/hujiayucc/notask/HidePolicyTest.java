package com.hujiayucc.notask;

import org.junit.Test;
import static org.junit.Assert.*;

public class HidePolicyTest {
    @Test public void directIsDefaultAndStaysHiddenInForeground() {
        HidePolicy policy = new HidePolicy(); Object activity = new Object();
        assertTrue(policy.shouldHide());
        policy.onStart(activity); policy.onResume(activity);
        assertTrue(policy.shouldHide());
    }

    @Test public void backModeHidesOnlyAfterRootBackAndRestoresOnResume() {
        HidePolicy p = new HidePolicy(); Object a = new Object();
        p.configure(true, ModuleConfig.MODE_BACK); p.onStart(a); p.onResume(a);
        assertFalse(p.shouldHide()); p.onBack(false); assertFalse(p.shouldHide());
        p.onPause(a); p.onStop(a); p.confirmBackground(); assertFalse(p.shouldHide());
        p.onBack(true); assertTrue(p.shouldHide());
        p.onStart(a); p.onResume(a); assertFalse(p.shouldHide());
    }

    @Test public void backgroundModeDebouncesThenRestoresOnResume() {
        HidePolicy p = new HidePolicy(); Object a = new Object();
        p.configure(true, ModuleConfig.MODE_BACKGROUND); p.onStart(a); p.onResume(a);
        p.onBack(true); assertFalse(p.shouldHide());
        p.onPause(a); p.onStop(a); assertTrue(p.backgroundCandidate()); assertFalse(p.shouldHide());
        p.confirmBackground(); assertTrue(p.shouldHide());
        p.onResume(a); assertFalse(p.shouldHide());
    }

    @Test public void internalPageTransitionDoesNotHideAfterResume() {
        HidePolicy p = new HidePolicy(); Object a = new Object(), b = new Object();
        p.configure(true, ModuleConfig.MODE_BACKGROUND); p.onStart(a); p.onResume(a);
        p.onPause(a); p.onStart(b); p.onResume(b); p.onStop(a); p.confirmBackground();
        assertFalse(p.shouldHide());
    }

    @Test public void anotherResumedActivityPreventsBackgroundHide() {
        HidePolicy p = new HidePolicy(); Object a = new Object(), b = new Object();
        p.configure(true, ModuleConfig.MODE_BACKGROUND); p.onResume(a); p.onResume(b);
        p.onPause(a); p.confirmBackground(); assertFalse(p.shouldHide());
        p.onPause(b); p.onStop(a); p.onStop(b); p.confirmBackground(); assertTrue(p.shouldHide());
    }

    @Test public void switchingFromDirectToTimedModesRestoresForegroundVisibility() {
        HidePolicy p = new HidePolicy(); Object a = new Object(); p.onResume(a);
        p.configure(true, ModuleConfig.MODE_BACK); assertFalse(p.shouldHide());
        p.configure(true, ModuleConfig.MODE_DIRECT); assertTrue(p.shouldHide());
        p.configure(true, ModuleConfig.MODE_BACKGROUND); assertFalse(p.shouldHide());
    }

    @Test public void choosingBackgroundWhileAlreadyBackgroundHidesImmediately() {
        HidePolicy p = new HidePolicy(); Object a = new Object();
        p.configure(true, ModuleConfig.MODE_BACK); p.onResume(a); p.onPause(a); p.onStop(a);
        p.configure(true, ModuleConfig.MODE_BACKGROUND); assertTrue(p.shouldHide());
    }

    @Test public void disablingOverridesEveryModeAndStaleCallbacks() {
        for (int mode : new int[] {1, 2, 3}) {
            HidePolicy p = new HidePolicy(); Object a = new Object();
            p.configure(true, mode); p.onResume(a); p.onBack(true); p.onPause(a); p.confirmBackground();
            p.configure(false, mode); p.onBack(true); p.confirmBackground(); assertFalse(p.shouldHide());
        }
    }

    @Test public void switchingToBackClearsPreviouslyConfirmedBackgroundHide() {
        HidePolicy p = new HidePolicy(); Object a = new Object();
        p.configure(true, ModuleConfig.MODE_BACKGROUND); p.onResume(a); p.onPause(a); p.onStop(a); p.confirmBackground();
        assertTrue(p.shouldHide()); p.configure(true, ModuleConfig.MODE_BACK); assertFalse(p.shouldHide());
        p.confirmBackground(); assertFalse(p.shouldHide());
    }

    @Test public void repeatedConfigReadDoesNotClearBackTrigger() {
        HidePolicy p = new HidePolicy(); p.configure(true, ModuleConfig.MODE_BACK); p.onBack(true);
        p.configure(true, ModuleConfig.MODE_BACK); assertTrue(p.shouldHide());
    }

    @Test public void duplicateResumesAndDestroyedActivityAreTrackedByIdentity() {
        HidePolicy p = new HidePolicy(); Object a = new Object();
        p.configure(true, ModuleConfig.MODE_BACKGROUND); p.onResume(a); p.onResume(a);
        p.onDestroy(a); p.confirmBackground(); assertTrue(p.shouldHide());
    }

    @Test public void pausedButVisibleActivityDoesNotCountAsBackground() {
        HidePolicy p = new HidePolicy(); Object a = new Object();
        p.configure(true, ModuleConfig.MODE_BACKGROUND); p.onStart(a); p.onResume(a);
        p.onPause(a); // Permission dialog or a translucent Activity covers the app.
        assertFalse(p.backgroundCandidate()); p.confirmBackground(); assertFalse(p.shouldHide());
    }

    @Test public void selectingBackgroundWhilePausedButVisibleDoesNotHide() {
        HidePolicy p = new HidePolicy(); Object a = new Object();
        p.onStart(a); p.onResume(a); p.onPause(a);
        p.configure(true, ModuleConfig.MODE_BACKGROUND); assertFalse(p.shouldHide());
    }

    @Test public void otherStartedButNotResumedActivityKeepsAppVisible() {
        HidePolicy p = new HidePolicy(); Object a = new Object(), b = new Object();
        p.configure(true, ModuleConfig.MODE_BACKGROUND); p.onStart(a); p.onResume(a);
        p.onStart(b); p.onPause(a); p.onStop(a); p.confirmBackground();
        assertFalse(p.shouldHide()); p.onStop(b); p.confirmBackground(); assertTrue(p.shouldHide());
    }
}

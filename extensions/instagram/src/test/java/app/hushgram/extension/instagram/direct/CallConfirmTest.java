/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** Ask before a call: off, nothing changes; on, the call waits for the question, and only Call starts it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CallConfirmTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Object starter = new Object();
    private final Object thread = new Object();
    private final Object entry = new Object();
    private final Object coWatch = new Object();
    private final List<List<Object>> started = new ArrayList<>();
    private ActivityController<Activity> controller;
    private Object screen;

    @Before
    public void setUp() {
        HookStatus.clear();
        CallConfirm.resetForTests();
        ShadowAlertDialog.reset();
        controller = Robolectric.buildActivity(Activity.class).setup();
        screen = new ContextWrapper(controller.get());
        CallConfirm.access = new CallConfirm.Starter() {
            @Override
            public void start(Object starter, Object thread, Object entry, Object coWatch, boolean video) {
                started.add(Arrays.asList(starter, thread, entry, coWatch, video));
            }

            @Override
            public Object context(Object of) {
                return of == starter ? screen : null;
            }
        };
    }

    @After
    public void tearDown() {
        CallConfirm.resetForTests();
        HookStatus.clear();
        controller.close();
    }

    private boolean hold(boolean video, boolean on) {
        return CallConfirm.hold(starter, thread, entry, coWatch, video, () -> on, SystemClock.uptimeMillis());
    }

    private AlertDialog asked() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("the question is showing", dialog != null && dialog.isShowing());
        return dialog;
    }

    @Test
    public void offTheCallGoesAhead() {
        assertFalse(hold(false, false));
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertTrue(started.isEmpty());
    }

    @Test
    public void onTheCallWaitsAndCancelStartsNothing() {
        assertTrue(hold(false, true));
        AlertDialog dialog = asked();
        assertEquals("Start a voice call?", org.robolectric.Shadows.shadowOf(dialog).getTitle().toString());

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertTrue(started.isEmpty());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(CallConfirm.ASKED + " 1"));
    }

    @Test
    public void callStartsItWithTheArgumentsItWasGiven() {
        assertTrue(hold(true, true));
        AlertDialog dialog = asked();
        assertEquals("Start a video call?", org.robolectric.Shadows.shadowOf(dialog).getTitle().toString());

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(1, started.size());
        assertEquals(Arrays.asList(starter, thread, entry, coWatch, true), started.get(0));
    }

    /** After Call, Instagram coming back to its own start goes through, and the question returns once the pass is over. */
    @Test
    public void theStartersOwnRepeatGoesThroughForAWhile() {
        assertTrue(hold(false, true));
        asked().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        long now = SystemClock.uptimeMillis();
        assertFalse(CallConfirm.hold(starter, thread, entry, coWatch, false, () -> true, now + 1_000));
        assertTrue(CallConfirm.hold(starter, thread, entry, coWatch, false, () -> true, now + CallConfirm.PASS_MILLIS));
    }

    /** With no screen to ask on, or a failure, the call starts as it always did. */
    @Test
    public void withoutAScreenItFailsOpen() {
        screen = null;
        assertFalse(hold(false, true));
        screen = new Object();
        assertFalse(hold(false, true));
        ActivityController<Activity> gone = Robolectric.buildActivity(Activity.class).setup();
        gone.pause().stop().destroy();
        screen = gone.get();
        assertFalse(hold(false, true));
        assertTrue(started.isEmpty());

        CallConfirm.access = new CallConfirm.Starter() {
            @Override
            public void start(Object starter, Object thread, Object entry, Object coWatch, boolean video) {
            }

            @Override
            public Object context(Object of) {
                throw new IllegalStateException("no screen");
            }
        };
        assertFalse(hold(false, true));
        assertFalse(HookStatus.missing(FamilyNames.ASK_BEFORE_CALL).isEmpty());
    }

    @Test
    public void aMissingStarterGoesAhead() {
        assertFalse(CallConfirm.hold(null, thread, entry, coWatch, false, () -> true, SystemClock.uptimeMillis()));
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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

/** Ask before a call: off, nothing changes; on, the call waits for the question, only Call starts it, and only that call's one repeat skips it. */
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
        CallConfirm.access = recorder();
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

    /** After Call, Instagram coming back to that start goes through once, and the question returns once the pass is over. */
    @Test
    public void theStartersOwnRepeatGoesThroughForAWhile() {
        assertTrue(hold(false, true));
        asked().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        long now = SystemClock.uptimeMillis();
        assertTrue(CallConfirm.hold(starter, thread, entry, coWatch, false, () -> true, now + CallConfirm.PASS_MILLIS));
        CallConfirm.resetForTests();
        CallConfirm.access = recorder();
        assertTrue(hold(false, true));
        asked().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(CallConfirm.hold(starter, thread, entry, coWatch, false, () -> true, SystemClock.uptimeMillis() + 1_000));
    }

    /** The pass is that one call's: its second repeat, another chat or the other kind of call is asked about. */
    @Test
    public void thePassIsUsedOnceForThatCallOnly() {
        assertTrue(hold(false, true));
        asked().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        long now = SystemClock.uptimeMillis();

        assertTrue("another chat", CallConfirm.hold(starter, new Object(), entry, coWatch, false, () -> true, now));
        dismiss();
        assertTrue("a video call", CallConfirm.hold(starter, thread, entry, coWatch, true, () -> true, now));
        dismiss();
        assertFalse("its repeat", CallConfirm.hold(starter, thread, entry, coWatch, false, () -> true, now));
        assertTrue("its second repeat", CallConfirm.hold(starter, thread, entry, coWatch, false, () -> true, now));
        assertEquals(1, started.size());
    }

    /** Call's own start goes through the hook again on its way in, and that doesn't use up the pass. */
    @Test
    public void callsOwnStartGoesThroughTheHook() {
        List<Boolean> inner = new ArrayList<>();
        CallConfirm.access = new CallConfirm.Starter() {
            @Override
            public void start(Object starter, Object thread, Object entry, Object coWatch, boolean video) {
                inner.add(CallConfirm.hold(starter, thread, entry, coWatch, video, () -> true, SystemClock.uptimeMillis()));
                started.add(Arrays.asList(starter, thread, entry, coWatch, video));
            }

            @Override
            public Object context(Object of) {
                return of == starter ? screen : null;
            }
        };
        assertTrue(hold(false, true));
        asked().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(Arrays.asList(false), inner);
        assertEquals(1, started.size());
        assertFalse(hold(false, true));
    }

    /** A second tap while the question is up waits for it: no second question, and nothing starts. */
    @Test
    public void oneQuestionAtATime() {
        assertTrue(hold(false, true));
        AlertDialog first = asked();
        assertTrue(hold(false, true));
        assertTrue(hold(true, true));
        assertSame(first, ShadowAlertDialog.getLatestAlertDialog());
        assertTrue(started.isEmpty());

        first.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(hold(false, true));
        assertNotSame(first, asked());
    }

    /**
     * A question left showing by a screen that went away (a dark mode switch or a window resize
     * Instagram doesn't handle itself) holds nothing: the next call start is asked about again on
     * the screen it came from.
     */
    @Test
    public void aQuestionWhoseScreenWentAwayHoldsNothing() {
        assertTrue(hold(false, true));
        AlertDialog left = asked();
        controller.pause().stop().destroy();
        assertTrue("the dialog still says it's showing", left.isShowing());
        assertFalse(CallConfirm.up(left));

        ActivityController<Activity> recreated = Robolectric.buildActivity(Activity.class).setup();
        try {
            screen = new ContextWrapper(recreated.get());
            assertTrue(hold(false, true));
            AlertDialog again = asked();
            assertNotSame(left, again);
            assertTrue(CallConfirm.up(again));
            again.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals(1, started.size());
        } finally {
            recreated.close();
        }
    }

    /**
     * A question up on one screen holds only that screen's taps. A tap on a screen opened over it
     * (a chat from a notification) closes the old one, starting nothing, and is asked about there,
     * and that new question holds its own screen's next tap.
     */
    @Test
    public void aQuestionHoldsOnlyItsOwnScreen() {
        assertTrue(hold(false, true));
        AlertDialog under = asked();

        ActivityController<Activity> over = Robolectric.buildActivity(Activity.class).setup();
        try {
            screen = new ContextWrapper(over.get());
            assertTrue(hold(true, true));
            ShadowLooper.idleMainLooper();
            assertFalse("the question under it went", under.isShowing());
            AlertDialog here = asked();
            assertNotSame(under, here);
            assertEquals("Start a video call?", org.robolectric.Shadows.shadowOf(here).getTitle().toString());
            assertTrue(hold(true, true));
            assertSame(here, ShadowAlertDialog.getLatestAlertDialog());
            assertTrue(started.isEmpty());

            here.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals(1, started.size());
            assertEquals(Arrays.asList(starter, thread, entry, coWatch, true), started.get(0));
        } finally {
            over.close();
        }
    }

    private void dismiss() {
        asked().getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
    }

    private CallConfirm.Starter recorder() {
        return new CallConfirm.Starter() {
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

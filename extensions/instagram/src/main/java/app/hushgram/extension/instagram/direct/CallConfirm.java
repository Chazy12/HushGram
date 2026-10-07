/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Ask before a call" patch.
 *
 * <p>Instagram 450 starts every call made from a chat through one method of the chat's call
 * starter ({@code X.01YY.A02}): the call buttons at the top of the chat, a call back from a call in
 * the chat and the chat's other ways in. The patch hands that method's arguments to {@link #hold}
 * first. With the switch on, the call waits for a question over the chat's own screen, found
 * through {@link #contextOf}: Call starts it through {@link #startCall}, which the patch fills with
 * Instagram's own call, and Cancel, Back or a tap outside start nothing.
 *
 * <p>Call's own start comes back through the hook first thing, and goes through. When Instagram
 * still has to ask for the microphone, or the camera too for a video call, the starter comes back
 * to that same call once more after the answer, so for {@link #PASS_MILLIS} one more start of that
 * call, in that chat and of that kind, goes through without asking. With them already allowed it
 * doesn't come back, and nothing goes through: a tap on the same button right after hanging up is
 * asked about. Any other start is asked about, and so is that one's repeat.
 *
 * <p>One question shows at a time: a start while it's on screen waits for it, so two quick taps on
 * a call button can't start two calls. A question counts as on screen only while its screen is: one
 * left behind by a screen that went away without closing it, as a dark mode switch or a window
 * resize can do, holds nothing. It's held weakly, so it never keeps that screen alive either. And it
 * holds only taps on its own screen: a tap on a screen opened over it, a chat opened from a
 * notification say, closes it and asks there instead.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, no screen to ask on or anything
 * thrown, the call starts as it always did.
 */
public final class CallConfirm {
    /** How long after Call the starter's one repeat of that call goes through unasked. */
    static final long PASS_MILLIS = 30_000;

    /** The step a failure is reported under. */
    static final String ASK = "ask before a call";

    /** What's counted each time a call waits for the question. */
    static final String ASKED = "asked before a call";

    /** The two stubs, which tests swap for stand-ins. */
    interface Starter {
        void start(Object starter, Object thread, Object entry, Object coWatch, boolean video);

        Object context(Object starter);
    }

    static final Starter PATCHED = new Starter() {
        @Override
        public void start(Object starter, Object thread, Object entry, Object coWatch, boolean video) {
            startCall(starter, thread, entry, coWatch, video);
        }

        @Override
        public Object context(Object starter) {
            return contextOf(starter);
        }
    };
    static volatile Starter access = PATCHED;

    /** The call Call let through, whose one repeat goes through unasked until {@link Pass#until}. */
    private static final class Pass {
        final Object starter;
        final Object thread;
        final boolean video;
        final long until;

        Pass(Object starter, Object thread, boolean video, long until) {
            this.starter = starter;
            this.thread = thread;
            this.video = video;
            this.until = until;
        }

        boolean covers(Object starter, Object thread, boolean video, long now) {
            return starter == this.starter && Objects.equals(thread, this.thread) && video == this.video && now < until;
        }
    }

    private static volatile Pass pass;

    /** True while Call's own start runs, whose first step is this hook again. Main thread only. */
    private static boolean starting;

    /** The question on screen, if one is. Weak, so a screen that went away isn't kept alive by it. */
    private static volatile WeakReference<AlertDialog> open;

    private CallConfirm() {
    }

    /**
     * Filled in by the patch: Instagram's own call start, on the chat's call starter, with the
     * arguments it was given. Only what {@link #hold} was handed may be passed.
     */
    public static void startCall(Object starter, Object thread, Object entry, Object coWatch, boolean video) {
    }

    /** Filled in by the patch: the context of the chat screen the starter belongs to, the one Instagram's call start reads. */
    public static Object contextOf(Object starter) {
        return null;
    }

    /**
     * Injected first in the chat's call start, with its own arguments. Answers true when the call
     * waits for the question, and false when Instagram should go on as usual. Never throws. The
     * video flag comes as an int, non-zero for a video call: a register the verifier types as int
     * wouldn't pass a boolean parameter.
     */
    public static boolean hold(Object starter, Object thread, Object entry, Object coWatch, int video) {
        return hold(starter, thread, entry, coWatch, video != 0, CallConfirm::switchedOn, SystemClock.uptimeMillis());
    }

    static boolean hold(Object starter, Object thread, Object entry, Object coWatch, boolean video,
                        BooleanSupplier on, long now) {
        try {
            HookStatus.invoked(FamilyNames.ASK_BEFORE_CALL);
            if (starter == null || !on.getAsBoolean() || starting) return false;
            Pass passed = pass;
            if (passed != null && passed.covers(starter, thread, video, now)) {
                pass = null;
                return false;
            }
            Activity activity = activityOf(access.context(starter));
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
            AlertDialog showing = question();
            if (up(showing)) {
                if (activityOf(showing.getContext()) == activity) return true;
                // Up over another screen, under this one: it goes, and this tap is asked about here.
                open = null;
                try {
                    showing.dismiss();
                } catch (Throwable ignored) {
                    // Its window is already gone, which is as good as dismissed.
                }
            }
            AlertDialog question = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t(video ? "Start a video call?" : "Start a voice call?"))
                    .setPositiveButton(L10n.t("Call"), (dialog, which) -> call(activity, starter, thread, entry, coWatch, video))
                    .setNegativeButton(L10n.t("Cancel"), null)
                    .create();
            question.setOnDismissListener(dialog -> {
                if (question() == dialog) open = null;
            });
            open = new WeakReference<>(question);
            question.show();
            HookStatus.counted(FamilyNames.ASK_BEFORE_CALL, ASKED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_CALL, ASK, failure);
            return false;
        }
    }

    /**
     * Call: starts the call the question held over [activity]. When Instagram will come back to it
     * after asking for a permission, the starter's one repeat of it goes through for a while.
     */
    private static void call(Activity activity, Object starter, Object thread, Object entry, Object coWatch, boolean video) {
        try {
            pass = comesBack(activity, video) ? new Pass(starter, thread, video, SystemClock.uptimeMillis() + PASS_MILLIS) : null;
            starting = true;
            try {
                access.start(starter, thread, entry, coWatch, video);
            } finally {
                starting = false;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_CALL, ASK, failure);
        }
    }

    /**
     * Whether Instagram will come back to a call's start after Call: it has to ask for the
     * microphone first, or for a video call the camera. A check that fails counts as yes, so the
     * repeat isn't asked about twice.
     */
    static boolean comesBack(Context context, boolean video) {
        return !granted(context, Manifest.permission.RECORD_AUDIO) || video && !granted(context, Manifest.permission.CAMERA);
    }

    private static boolean granted(Context context, String permission) {
        try {
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** The question last put up, while something still holds it, or null. */
    private static AlertDialog question() {
        WeakReference<AlertDialog> held = open;
        return held == null ? null : held.get();
    }

    /**
     * Whether [question] is still up: showing, over an activity that isn't going away, the rule
     * Ask before a like and Ask before a refresh use. A question whose activity went without
     * dismissing it still says it's showing, and must not hold a call.
     */
    static boolean up(Dialog question) {
        if (question == null || !question.isShowing()) return false;
        Activity activity = activityOf(question.getContext());
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    /** The activity behind [context], or null when there's none. */
    private static Activity activityOf(Object context) {
        Object at = context;
        for (int depth = 0; depth < 10 && at instanceof ContextWrapper; depth++) {
            if (at instanceof Activity) return (Activity) at;
            at = ((ContextWrapper) at).getBaseContext();
        }
        return null;
    }

    /** Lets a test start without a pass or a question from an earlier one. */
    static void resetForTests() {
        pass = null;
        starting = false;
        open = null;
        access = PATCHED;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.ASK_BEFORE_CALL.get();
    }
}

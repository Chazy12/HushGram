/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContextWrapper;
import android.os.SystemClock;

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
 * <p>After Call, the starter may come back to itself for a while, after a permission or a question
 * of Instagram's own, so for {@link #PASS_MILLIS} every start goes through without asking again.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, no screen to ask on or anything
 * thrown, the call starts as it always did.
 */
public final class CallConfirm {
    /** How long after Call the starter's own repeats go through unasked. */
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

    /** Until when, in uptime, a start goes through without asking. */
    private static volatile long passUntil;

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
            if (starter == null || !on.getAsBoolean() || now < passUntil) return false;
            Activity activity = activityOf(access.context(starter));
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
            new AlertDialog.Builder(activity)
                    .setTitle(L10n.t(video ? "Start a video call?" : "Start a voice call?"))
                    .setPositiveButton(L10n.t("Call"), (dialog, which) -> call(starter, thread, entry, coWatch, video))
                    .setNegativeButton(L10n.t("Cancel"), null)
                    .show();
            HookStatus.counted(FamilyNames.ASK_BEFORE_CALL, ASKED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_CALL, ASK, failure);
            return false;
        }
    }

    /** Call: starts the call the question held, and lets the starter's repeats through for a while. */
    private static void call(Object starter, Object thread, Object entry, Object coWatch, boolean video) {
        try {
            passUntil = SystemClock.uptimeMillis() + PASS_MILLIS;
            access.start(starter, thread, entry, coWatch, video);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_CALL, ASK, failure);
        }
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

    /** Lets a test start without a pass from an earlier Call. */
    static void resetForTests() {
        passUntil = 0;
        access = PATCHED;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.ASK_BEFORE_CALL.get();
    }
}

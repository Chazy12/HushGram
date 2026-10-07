/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContextWrapper;
import android.os.SystemClock;

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
 * <p>Call's own start comes back through the hook first thing, and goes through. After it, the
 * starter may come back to that same call once more, after a permission or a question of
 * Instagram's own, so for {@link #PASS_MILLIS} one more start of that call, in that chat and of that
 * kind, goes through without asking. Any other start is asked about, and so is that one's repeat.
 *
 * <p>One question shows at a time: a start while it's on screen waits for it, so two quick taps on
 * a call button can't start two calls.
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

    /** The question on screen, if one is. */
    private static AlertDialog open;

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
            AlertDialog showing = open;
            if (showing != null && showing.isShowing()) return true;
            Activity activity = activityOf(access.context(starter));
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
            AlertDialog question = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t(video ? "Start a video call?" : "Start a voice call?"))
                    .setPositiveButton(L10n.t("Call"), (dialog, which) -> call(starter, thread, entry, coWatch, video))
                    .setNegativeButton(L10n.t("Cancel"), null)
                    .create();
            question.setOnDismissListener(dialog -> {
                if (open == dialog) open = null;
            });
            open = question;
            question.show();
            HookStatus.counted(FamilyNames.ASK_BEFORE_CALL, ASKED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_CALL, ASK, failure);
            return false;
        }
    }

    /** Call: starts the call the question held, and lets the starter's one repeat of it through for a while. */
    private static void call(Object starter, Object thread, Object entry, Object coWatch, boolean video) {
        try {
            pass = new Pass(starter, thread, video, SystemClock.uptimeMillis() + PASS_MILLIS);
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

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.util.function.BooleanSupplier;

/**
 * Helper for the "Read messages without the seen receipt" patch.
 *
 * <p>Opening a chat queues a seen receipt, and Instagram's handler for it sends the request that
 * puts Seen under the other person's message. The patch asks {@link #hold} first in that handler.
 * While the switch is on, the handler reports the receipt done through Instagram's own callback
 * without sending it, so the chat still reads as seen on this phone and the queue doesn't retry it.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram sends the receipt as usual.
 */
public final class ThreadSeen {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";

    private static volatile boolean logged;

    private ThreadSeen() {
    }

    /**
     * Asked at the start of Instagram's seen receipt handler. True makes the handler finish without
     * sending. False while the switch is off, HushGram is paused or the settings aren't ready.
     * Never throws.
     */
    public static boolean hold() {
        return hold(ThreadSeen::switchedOn);
    }

    static boolean hold(BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.THREAD_SEEN);
            boolean hold = on.getAsBoolean();
            if (hold && !logged) {
                logged = true;
                Logger.printDebug(() -> "Messages: held back a seen receipt");
            }
            return hold;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, SWITCH, t);
            return false;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.READ_WITHOUT_SEEN_RECEIPT.get();
    }
}

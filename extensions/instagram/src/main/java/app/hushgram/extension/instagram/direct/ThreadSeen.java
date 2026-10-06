/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.os.SystemClock;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Helper for the "Read messages without the seen receipt" patch.
 *
 * <p>Opening a chat queues a seen receipt, and Instagram's handler for it sends the request that
 * puts Seen under the other person's message. The patch asks {@link #hold} first in that handler.
 * While the switch is on, the handler reports the receipt done through Instagram's own callback
 * without sending it, so the chat still reads as seen on this phone and the queue doesn't retry it.
 *
 * <p>While the switch is on, a long press on a chat in the inbox also offers Instagram's own Mark
 * as read, which Instagram otherwise offers only when several chats are picked
 * ({@link #offerMarkRead}). Tapping it ({@link #markRead}) queues that chat's receipt through
 * Instagram's own sender, picked the way Instagram's own handler for marking a chat read picks it,
 * and takes the chat's unread mark off. That chat's next receipt then goes through the hold once, if
 * it comes within a minute, and so does that same receipt if Instagram tries it again after a failed
 * send. Every other receipt stays held.
 *
 * <p>The hooks fail open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram sends the receipt and builds its menu as usual. A receipt whose chat
 * can't be read while a chat waits for its receipt is the one exception: it stays held, since
 * letting it through would send a receipt nobody asked for.
 */
public final class ThreadSeen {
    /** The steps a failure is reported under. */
    static final String SWITCH = "switch read";
    static final String ROW = "mark as read row";
    static final String MARK = "mark as read";
    static final String PASS = "chat marked read";
    static final String FEEDBACK = "mark as read feedback";

    /** How long a chat marked read by hand waits for its receipt. */
    static final long PASS_MILLIS = 60_000L;

    /** Instagram's chats, through the bodies the patch writes in {@link InstagramChats}. Tests stand in their own. */
    interface Chats {
        String threadId(Object key);

        Object receiptKey(Object receipt);

        Object lastMessage(Object thread);

        String messageId(Object message);

        String senderId(Object message);

        void sendSeen(Object session, String thread, String message, String sender);

        void clearUnread(Object session, Object key);
    }

    private static final Chats INSTAGRAM = new Chats() {
        @Override
        public String threadId(Object key) {
            return InstagramChats.threadId(key);
        }

        @Override
        public Object receiptKey(Object receipt) {
            return InstagramChats.receiptKey(receipt);
        }

        @Override
        public Object lastMessage(Object thread) {
            return InstagramChats.lastMessage(thread);
        }

        @Override
        public String messageId(Object message) {
            return InstagramChats.messageId(message);
        }

        @Override
        public String senderId(Object message) {
            return InstagramChats.senderId(message);
        }

        @Override
        public void sendSeen(Object session, String thread, String message, String sender) {
            InstagramChats.sendSeen(session, null, thread, message, sender);
        }

        @Override
        public void clearUnread(Object session, Object key) {
            InstagramChats.markUnread(session, key, false);
        }
    };

    private static final LongSupplier CLOCK = SystemClock::elapsedRealtime;

    private static final Object LOCK = new Object();

    /** Chats marked read by hand whose next receipt goes through, by thread id, with when that runs out. */
    private static final Map<String, Long> passes = new HashMap<>();

    /** Receipts let through, so one that Instagram tries again after a failed send goes through again. */
    private static final List<WeakReference<Object>> passed = new ArrayList<>();

    private static volatile boolean logged;

    private ThreadSeen() {
    }

    /**
     * Asked at the start of Instagram's seen receipt handler with the receipt. True makes the handler
     * finish without sending. False while the switch is off, HushGram is paused or the settings
     * aren't ready, and for the receipt of a chat just marked read by hand. Never throws.
     */
    public static boolean hold(Object receipt) {
        return hold(receipt, ThreadSeen::switchedOn, INSTAGRAM, CLOCK);
    }

    static boolean hold(Object receipt, BooleanSupplier on) {
        return hold(receipt, on, INSTAGRAM, CLOCK);
    }

    static boolean hold(Object receipt, BooleanSupplier on, Chats chats, LongSupplier clock) {
        try {
            HookStatus.invoked(FamilyNames.THREAD_SEEN);
            boolean hold = on.getAsBoolean();
            if (hold && markedRead(receipt, chats, clock)) {
                Logger.printDebug(() -> "Messages: sent the seen receipt for a chat marked read");
                return false;
            }
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

    /**
     * Called first in the builder that offers Mark as unread on a chat's long press, with the rows
     * so far and Instagram's own Mark as read. Adds Mark as read, once, while the switch is on.
     */
    public static void offerMarkRead(List<Object> rows, Object markAsRead) {
        offerMarkRead(rows, markAsRead, ThreadSeen::switchedOn);
    }

    static void offerMarkRead(List<Object> rows, Object markAsRead, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.THREAD_SEEN);
            if (rows != null && markAsRead != null && on.getAsBoolean() && !rows.contains(markAsRead)) {
                rows.add(markAsRead);
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, ROW, t);
        }
    }

    /**
     * Called first when a row of a chat's long press menu is tapped, with the row, Instagram's own
     * Mark as read, the account, the chat and its key. True when the tap was Mark as read and has
     * been handled here, so Instagram's code for the row doesn't run. Never throws.
     */
    public static boolean markRead(Object chosen, Object markAsRead, Object session, Object thread, Object key) {
        return markRead(chosen, markAsRead, session, thread, key, ThreadSeen::switchedOn, INSTAGRAM, CLOCK);
    }

    static boolean markRead(Object chosen, Object markAsRead, Object session, Object thread, Object key,
                            BooleanSupplier on, Chats chats, LongSupplier clock) {
        String waiting = null;
        try {
            HookStatus.invoked(FamilyNames.THREAD_SEEN);
            if (chosen == null || chosen != markAsRead || !on.getAsBoolean()) return false;
            String id = chats.threadId(key);
            Object last = chats.lastMessage(thread);
            String message = last == null ? null : chats.messageId(last);
            String sender = last == null ? null : chats.senderId(last);
            if (isEmpty(id) || isEmpty(message) || isEmpty(sender)) {
                Logger.printDebug(() -> "Messages: a chat marked read has no message to mark");
                toast(() -> L10n.t("Couldn't mark as read"));
                return true;
            }
            allow(id, clock.getAsLong());
            waiting = id;
            chats.sendSeen(session, id, message, sender);
            waiting = null;
            try {
                chats.clearUnread(session, key);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.THREAD_SEEN, MARK, t);
            }
            toast(() -> L10n.t("Marked as read"));
            return true;
        } catch (Throwable t) {
            if (waiting != null) revoke(waiting);
            HookStatus.threw(FamilyNames.THREAD_SEEN, MARK, t);
            toast(() -> L10n.t("Couldn't mark as read"));
            return false;
        }
    }

    /**
     * Whether [receipt] goes through because its chat was marked read by hand: the first receipt for
     * that chat within the minute, or one already let through that Instagram is trying again. A
     * receipt whose chat can't be read stays held, and the failure is reported.
     */
    private static boolean markedRead(Object receipt, Chats chats, LongSupplier clock) {
        synchronized (LOCK) {
            if (wasPassed(receipt)) return true;
            if (passes.isEmpty()) return false;
        }
        String thread;
        try {
            thread = chats.threadId(chats.receiptKey(receipt));
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, PASS, t);
            return false;
        }
        if (thread == null) return false;
        long now = clock.getAsLong();
        synchronized (LOCK) {
            Long until = passes.remove(thread);
            dropExpired(now);
            if (until == null || now > until) return false;
            passed.add(new WeakReference<>(receipt));
            return true;
        }
    }

    private static void allow(String thread, long now) {
        synchronized (LOCK) {
            dropExpired(now);
            passes.put(thread, now + PASS_MILLIS);
        }
    }

    private static void revoke(String thread) {
        synchronized (LOCK) {
            passes.remove(thread);
        }
    }

    /** Called holding {@link #LOCK}. */
    private static boolean wasPassed(Object receipt) {
        boolean found = false;
        for (Iterator<WeakReference<Object>> it = passed.iterator(); it.hasNext(); ) {
            Object held = it.next().get();
            if (held == null) it.remove();
            else if (receipt != null && held == receipt) found = true;
        }
        return found;
    }

    /** Called holding {@link #LOCK}. */
    private static void dropExpired(long now) {
        passes.values().removeIf(until -> now > until);
    }

    /** Forgets every chat marked read and every receipt let through, for tests. */
    static void forgetMarks() {
        synchronized (LOCK) {
            passes.clear();
            passed.clear();
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static void toast(Supplier<String> text) {
        try {
            Utils.showToastShort(text.get());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, FEEDBACK, t);
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.READ_WITHOUT_SEEN_RECEIPT.get();
    }
}

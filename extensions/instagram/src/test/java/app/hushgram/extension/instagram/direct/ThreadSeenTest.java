/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * When the chat seen receipt is held back, when Instagram sends it, and how a chat marked read by
 * hand lets its own receipt through. Runtime decisions only: the other person seeing Seen, or not,
 * needs a check with two accounts on a phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ThreadSeenTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };
    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;

    /** Instagram's own Mark as read, and another row of the same menu. */
    private static final Object MARK = new Object();
    private static final Object UNREAD = new Object();
    private static final Object SESSION = new Object();

    private final long[] now = {5_000L};
    private final LongSupplier clock = () -> now[0];
    private FakeChats chats;

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);
        HookStatus.clear();
        ThreadSeen.forgetMarks();
        ShadowToast.reset();
        chats = new FakeChats();
    }

    @After
    public void restore() {
        Settings.READ_WITHOUT_SEEN_RECEIPT.resetToDefault();
        Settings.VIEW_DM_MEDIA_ANONYMOUSLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        ThreadSeen.forgetMarks();
    }

    @Test
    public void withTheSwitchOnTheReceiptIsHeld() {
        assertTrue(ThreadSeen.hold(null));
        assertTrue(ThreadSeen.hold(new Receipt("t1")));
        assertTrue(HookStatus.missing(FamilyNames.THREAD_SEEN).toString(),
                HookStatus.missing(FamilyNames.THREAD_SEEN).isEmpty());
    }

    @Test
    public void offToStartAndOffSendTheReceipt() {
        Settings.READ_WITHOUT_SEEN_RECEIPT.resetToDefault();
        assertFalse(Settings.READ_WITHOUT_SEEN_RECEIPT.defaultValue);
        assertFalse(ThreadSeen.hold(null));
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(false);
        assertFalse(ThreadSeen.hold(null));
    }

    /** The view-once switch is a separate choice in both directions. */
    @Test
    public void viewOnceMediaIsAnIndependentChoice() {
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(false);
        Settings.VIEW_DM_MEDIA_ANONYMOUSLY.save(true);
        assertFalse(ThreadSeen.hold(null));
        Settings.VIEW_DM_MEDIA_ANONYMOUSLY.save(false);
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);
        assertTrue(ThreadSeen.hold(null));
        assertFalse(VisualSeen.hold());
    }

    @Test
    public void pausedAndUnreadySendTheReceipt() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ThreadSeen.hold(null));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(ThreadSeen.hold(null)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse(ThreadSeen.hold(null)));

        assertTrue(ThreadSeen.hold(null));
    }

    @Test
    public void aThrowingSwitchSendsTheReceiptAndIsReported() {
        assertFalse(ThreadSeen.hold(null, THROWS));

        String missing = HookStatus.missing(FamilyNames.THREAD_SEEN).toString();
        assertTrue(missing, missing.contains("'" + ThreadSeen.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    @Test
    public void aLongPressOffersMarkAsReadOnceWhileTheSwitchIsOn() {
        List<Object> rows = new ArrayList<>(Collections.singletonList(UNREAD));
        ThreadSeen.offerMarkRead(rows, MARK);
        ThreadSeen.offerMarkRead(rows, MARK);
        assertEquals(List.of(UNREAD, MARK), rows);

        List<Object> empty = new ArrayList<>();
        ThreadSeen.offerMarkRead(empty, null);
        ThreadSeen.offerMarkRead(null, MARK);
        assertTrue(empty.isEmpty());
        assertTrue(HookStatus.missing(FamilyNames.THREAD_SEEN).toString(),
                HookStatus.missing(FamilyNames.THREAD_SEEN).isEmpty());
    }

    @Test
    public void offPausedAndUnreadyOfferNoRow() {
        List<Object> rows = new ArrayList<>();
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(false);
        ThreadSeen.offerMarkRead(rows, MARK);
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ThreadSeen.offerMarkRead(rows, MARK);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> ThreadSeen.offerMarkRead(rows, MARK));
        SettingsContextRule.beforeThePauseIsDecided(() -> ThreadSeen.offerMarkRead(rows, MARK));
        assertTrue(rows.isEmpty());

        ThreadSeen.offerMarkRead(rows, MARK);
        assertEquals(List.of(MARK), rows);
    }

    @Test
    public void aThrowingSwitchOffersNoRowAndIsReported() {
        List<Object> rows = new ArrayList<>();
        ThreadSeen.offerMarkRead(rows, MARK, THROWS);
        assertTrue(rows.isEmpty());
        String missing = HookStatus.missing(FamilyNames.THREAD_SEEN).toString();
        assertTrue(missing, missing.contains("'" + ThreadSeen.ROW + "'"));
    }

    @Test
    public void markAsReadSendsThatChatsReceiptThroughInstagram() {
        Key key = new Key("t1");
        assertTrue(mark(MARK, chat("m1", "s1"), key));
        assertEquals(List.of("t1/m1/s1"), chats.sent);
        assertEquals(List.of(key), chats.cleared);
        assertEquals("Marked as read", toast());

        assertFalse("the marked chat's receipt goes through", hold(new Receipt("t1")));
        assertTrue("and only once", hold(new Receipt("t1")));
        assertTrue(HookStatus.missing(FamilyNames.THREAD_SEEN).toString(),
                HookStatus.missing(FamilyNames.THREAD_SEEN).isEmpty());
    }

    @Test
    public void onlyTheMarkedChatsReceiptGoesThrough() {
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        assertTrue(hold(new Receipt("t2")));
        assertTrue(hold(null));
        assertFalse(hold(new Receipt("t1")));
        assertTrue(hold(new Receipt("t2")));
        assertTrue(hold(new Receipt("t1")));
    }

    @Test
    public void aReceiptLetThroughGoesThroughAgainWhenInstagramRetriesIt() {
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        Receipt sent = new Receipt("t1");
        assertFalse(hold(sent));
        assertFalse(hold(sent));
        assertTrue(hold(new Receipt("t1")));
    }

    @Test
    public void aChatMarkedReadWaitsAMinuteForItsReceipt() {
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        now[0] += ThreadSeen.PASS_MILLIS;
        assertFalse(hold(new Receipt("t1")));

        assertTrue(mark(MARK, chat("m2", "s1"), new Key("t1")));
        now[0] += ThreadSeen.PASS_MILLIS + 1;
        assertTrue(hold(new Receipt("t1")));
        assertTrue(hold(new Receipt("t1")));
    }

    /** Marking the same chat again starts its minute over, and still lets one receipt through. */
    @Test
    public void markingAChatAgainStartsItsMinuteOver() {
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        now[0] += ThreadSeen.PASS_MILLIS - 1;
        assertTrue(mark(MARK, chat("m2", "s1"), new Key("t1")));
        now[0] += ThreadSeen.PASS_MILLIS - 1;
        assertFalse(hold(new Receipt("t1")));
        assertTrue(hold(new Receipt("t1")));
        assertEquals(List.of("t1/m1/s1", "t1/m2/s1"), chats.sent);
    }

    @Test
    public void otherRowsAndTheSwitchOffLeaveTheTapToInstagram() {
        Key key = new Key("t1");
        assertFalse(mark(UNREAD, chat("m1", "s1"), key));
        assertFalse(mark(null, chat("m1", "s1"), key));
        assertFalse(ThreadSeen.markRead(MARK, MARK, SESSION, chat("m1", "s1"), key, OFF, chats, clock));
        assertTrue(chats.sent.isEmpty());
        assertTrue(chats.cleared.isEmpty());
        assertTrue(hold(new Receipt("t1")));
    }

    @Test
    public void offPausedAndUnreadyLeaveTheTapToInstagram() {
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(false);
        assertFalse(ThreadSeen.markRead(MARK, MARK, SESSION, chat("m1", "s1"), new Key("t1")));
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ThreadSeen.markRead(MARK, MARK, SESSION, chat("m1", "s1"), new Key("t1")));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() ->
                assertFalse(ThreadSeen.markRead(MARK, MARK, SESSION, chat("m1", "s1"), new Key("t1"))));
        SettingsContextRule.beforeThePauseIsDecided(() ->
                assertFalse(ThreadSeen.markRead(MARK, MARK, SESSION, chat("m1", "s1"), new Key("t1"))));
        assertTrue(ThreadSeen.hold(new Receipt("t1")));
    }

    /** A chat whose message or ids can't be read is told so, and nothing waits for its receipt. */
    @Test
    public void aChatWithNothingToMarkIsToldSo() {
        assertTrue(mark(MARK, new Chat(null), new Key("t1")));
        assertTrue(mark(MARK, chat("", "s1"), new Key("t1")));
        assertTrue(mark(MARK, chat("m1", null), new Key("t1")));
        assertTrue(mark(MARK, chat("m1", "s1"), new Key(null)));
        assertEquals("Couldn't mark as read", toast());
        assertTrue(chats.sent.isEmpty());
        assertTrue(chats.cleared.isEmpty());
        assertTrue(hold(new Receipt("t1")));
    }

    /** Unpatched bridges answer nothing, which reads the same as a chat with nothing to mark. */
    @Test
    public void unpatchedBridgesMarkNothing() {
        assertTrue(ThreadSeen.markRead(MARK, MARK, SESSION, new Object(), new Object()));
        assertEquals("Couldn't mark as read", toast());
        assertTrue(ThreadSeen.hold(new Object()));
    }

    @Test
    public void aFailedSendTakesItsAllowanceBackAndIsReported() {
        chats.sendThrows = true;
        assertFalse(mark(MARK, chat("m1", "s1"), new Key("t1")));
        assertEquals("Couldn't mark as read", toast());
        chats.sendThrows = false;
        assertTrue(hold(new Receipt("t1")));
        String missing = HookStatus.missing(FamilyNames.THREAD_SEEN).toString();
        assertTrue(missing, missing.contains("'" + ThreadSeen.MARK + "'"));
    }

    @Test
    public void aFailedUnreadClearStillSendsAndIsReported() {
        chats.clearThrows = true;
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        assertEquals(List.of("t1/m1/s1"), chats.sent);
        assertEquals("Marked as read", toast());
        assertFalse(hold(new Receipt("t1")));
        String missing = HookStatus.missing(FamilyNames.THREAD_SEEN).toString();
        assertTrue(missing, missing.contains("'" + ThreadSeen.MARK + "'"));
    }

    /** While a chat waits, a receipt whose chat can't be read stays held rather than going out unasked. */
    @Test
    public void anUnreadableReceiptStaysHeldWhileAChatWaits() {
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        assertTrue(hold(new Object()));
        chats.keyThrows = true;
        assertTrue(hold(new Receipt("t1")));
        String missing = HookStatus.missing(FamilyNames.THREAD_SEEN).toString();
        assertTrue(missing, missing.contains("'" + ThreadSeen.PASS + "'"));
        chats.keyThrows = false;
        assertFalse(hold(new Receipt("t1")));
    }

    /** With no chat waiting, receipts are held without reading them at all. */
    @Test
    public void withNoChatWaitingReceiptsAreNotRead() {
        chats.keyThrows = true;
        assertTrue(hold(new Receipt("t1")));
        assertTrue(HookStatus.missing(FamilyNames.THREAD_SEEN).toString(),
                HookStatus.missing(FamilyNames.THREAD_SEEN).isEmpty());
    }

    /** Off, a chat marked read before keeps nothing back: every receipt goes, and the mark waits. */
    @Test
    public void theSwitchOffSendsEveryReceiptAndKeepsTheMark() {
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        assertFalse(ThreadSeen.hold(new Receipt("t2"), OFF, chats, clock));
        assertFalse(ThreadSeen.hold(new Receipt("t1"), OFF, chats, clock));
        assertFalse(hold(new Receipt("t1")));
    }

    private boolean mark(Object chosen, Object thread, Object key) {
        return ThreadSeen.markRead(chosen, MARK, SESSION, thread, key, ON, chats, clock);
    }

    private boolean hold(Object receipt) {
        return ThreadSeen.hold(receipt, ON, chats, clock);
    }

    private static Chat chat(String message, String sender) {
        return new Chat(new Message(message, sender));
    }

    private static String toast() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return String.valueOf(ShadowToast.getTextOfLatestToast());
    }

    private static final class Key {
        final String thread;

        Key(String thread) {
            this.thread = thread;
        }
    }

    private static final class Receipt {
        final String thread;

        Receipt(String thread) {
            this.thread = thread;
        }
    }

    private static final class Message {
        final String id;
        final String sender;

        Message(String id, String sender) {
            this.id = id;
            this.sender = sender;
        }
    }

    private static final class Chat {
        final Message last;

        Chat(Message last) {
            this.last = last;
        }
    }

    /** Instagram's chats as these tests keep them: a receipt and a key name their chat, a chat its last message. */
    private static final class FakeChats implements ThreadSeen.Chats {
        final List<String> sent = new ArrayList<>();
        final List<Object> cleared = new ArrayList<>();
        boolean sendThrows;
        boolean clearThrows;
        boolean keyThrows;

        @Override
        public String threadId(Object key) {
            return key instanceof Key ? ((Key) key).thread : null;
        }

        @Override
        public Object receiptKey(Object receipt) {
            if (keyThrows) throw new IllegalStateException("Required value was null.");
            return receipt instanceof Receipt ? new Key(((Receipt) receipt).thread) : null;
        }

        @Override
        public Object lastMessage(Object thread) {
            return thread instanceof Chat ? ((Chat) thread).last : null;
        }

        @Override
        public String messageId(Object message) {
            return ((Message) message).id;
        }

        @Override
        public String senderId(Object message) {
            return ((Message) message).sender;
        }

        @Override
        public void sendSeen(Object session, String thread, String message, String sender) {
            if (sendThrows) throw new IllegalStateException("the queue went away");
            assertEquals(SESSION, session);
            sent.add(thread + "/" + message + "/" + sender);
        }

        @Override
        public void clearUnread(Object session, Object key) {
            if (clearThrows) throw new IllegalStateException("the store went away");
            assertEquals(SESSION, session);
            cleared.add(key);
        }
    }
}

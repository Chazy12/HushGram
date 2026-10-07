/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Lock your messages covers, hides and holds, and when it leaves everything to Instagram. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class MessagesLockTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int FRAME = 0x7f0b0001;
    private static final int LIST = 0x7f0b0002;
    private static final int CHAT = 0x7f0b0003;

    /** Each ask's two answers, oldest first. */
    private final List<Runnable[]> asks = new ArrayList<>();

    @Before
    public void enable() {
        MessagesLock.resetForTests();
        Map<String, Integer> ids = new HashMap<>();
        ids.put(MessagesLock.INBOX_FRAME, FRAME);
        ids.put(MessagesLock.INBOX_LIST, LIST);
        ids.put(MessagesLock.CHAT_ROOT, CHAT);
        MessagesLock.idsForTests = ids;
        MessagesLock.asker = (activity, confirmed, notConfirmed) -> asks.add(new Runnable[]{confirmed, notConfirmed});
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.LOCK_MESSAGES.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        MessagesLock.resetForTests();
        Settings.LOCK_MESSAGES.resetToDefault();
        Settings.LOCK_APP.resetToDefault();
        Settings.LOCK_AGAIN.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void lockedMessageNotificationsSayOnlyThatAMessageCame() {
        Notification original = message("ig_direct", Notification.CATEGORY_MESSAGE);

        Notification hidden = MessagesLock.notification(original);

        assertNotSame(original, hidden);
        assertEquals("New message", hidden.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertFalse(String.valueOf(hidden.extras.getCharSequence(Notification.EXTRA_TITLE)).contains("Alice"));
        assertNull(hidden.actions);
        assertSame(original.contentIntent, hidden.contentIntent);
        assertEquals(original.getGroup(), hidden.getGroup());
        assertEquals(original.getChannelId(), hidden.getChannelId());
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());

        // Marked by its channel alone, a message is still a message; a call and anything else aren't.
        Notification byChannel = message("ig_direct", null);
        assertNotSame(byChannel, MessagesLock.notification(byChannel));
        Notification call = message("ig_direct", Notification.CATEGORY_CALL);
        assertSame(call, MessagesLock.notification(call));
        Notification like = message("ig_other", Notification.CATEGORY_SOCIAL);
        assertSame(like, MessagesLock.notification(like));
    }

    @Test
    public void offPausedUnreadyAndUnlockedLeaveEverythingToInstagram() {
        Notification original = message("ig_direct", Notification.CATEGORY_MESSAGE);

        Settings.LOCK_MESSAGES.resetToDefault();
        assertFalse(Settings.LOCK_MESSAGES.get());
        assertSame(original, MessagesLock.notification(original));
        assertFalse(MessagesLock.holdBanner());
        Settings.LOCK_MESSAGES.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(original, MessagesLock.notification(original));
        assertFalse(MessagesLock.holdBanner());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertSame(original, MessagesLock.notification(original));
            assertFalse(MessagesLock.holdBanner());
        });

        assertTrue(MessagesLock.holdBanner());
        MessagesLock.confirmThen(Robolectric.buildActivity(Activity.class).setup().get(), () -> { });
        asks.get(0)[0].run();
        assertSame(original, MessagesLock.notification(original));
        assertFalse(MessagesLock.holdBanner());
    }

    @Test
    public void theInboxIsCoveredAndThePhoneAskedOnce() {
        ActivityController<Activity> controller = inbox();
        Activity activity = controller.get();
        View frame = activity.findViewById(FRAME);

        MessagesLock.check(activity);
        MessagesLock.check(activity);

        View cover = cover(activity);
        assertNotNull("no cover over the inbox", cover);
        assertEquals(View.VISIBLE, cover.getVisibility());
        assertEquals(visible(frame).width(), cover.getWidth());
        assertEquals(visible(frame).height(), cover.getHeight());
        assertEquals(1, asks.size());
        assertTrue(MessagesLock.holdBanner());

        // Cancelled: the cover stays, and isn't asked again until the inbox shows again.
        asks.get(0)[1].run();
        MessagesLock.check(activity);
        assertEquals(View.VISIBLE, cover.getVisibility());
        assertEquals(1, asks.size());
    }

    @Test
    public void confirmedOpensUntilInstagramLeavesTheScreen() {
        Application app = RuntimeEnvironment.getApplication();
        MessagesLock.watch(app);
        ActivityController<Activity> controller = inbox();
        Activity activity = controller.get();
        MessagesLock.check(activity);
        View cover = cover(activity);
        assertNotNull(cover);

        asks.get(0)[0].run();
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.GONE, cover.getVisibility());
        assertFalse(MessagesLock.locked());

        controller.pause().stop();
        assertTrue("leaving Instagram didn't lock the messages again", MessagesLock.locked());
        controller.start().resume();
        layout(activity);
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.VISIBLE, cover(activity).getVisibility());
        assertEquals(2, asks.size());
    }

    @Test
    public void aChatIsCoveredTooAndNothingElseIs() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View other = new View(activity);
        root.addView(other, new FrameLayout.LayoutParams(400, 400));
        activity.setContentView(root);
        layout(activity);
        MessagesLock.check(activity);
        assertNull("a screen that isn't the messages got a cover", cover(activity));
        assertEquals(0, asks.size());

        FrameLayout chat = new FrameLayout(activity);
        chat.setId(CHAT);
        root.addView(chat, new FrameLayout.LayoutParams(300, 500));
        layout(activity);
        MessagesLock.check(activity);
        assertNotNull("no cover over the chat", cover(activity));
        assertEquals(visible(chat).height(), cover(activity).getHeight());
        assertEquals(1, asks.size());
    }

    @Test
    public void lockAllOfInstagramCoversTheWholeScreen() {
        Settings.LOCK_MESSAGES.save(false);
        Settings.LOCK_APP.save(true);
        Activity activity = inbox().get();
        View content = activity.findViewById(android.R.id.content);

        MessagesLock.check(activity);

        View cover = cover(activity);
        assertNotNull("no cover over Instagram", cover);
        assertEquals(View.VISIBLE, cover.getVisibility());
        assertEquals(visible(content).width(), cover.getWidth());
        assertEquals(visible(content).height(), cover.getHeight());
        assertEquals("the inbox got its own cover too", 1, covers(activity));
        assertEquals(1, asks.size());
        Notification message = message("ig_direct", Notification.CATEGORY_MESSAGE);
        assertNotSame(message, MessagesLock.notification(message));

        asks.get(0)[0].run();
        MessagesLock.check(activity);
        assertEquals(View.GONE, cover.getVisibility());
        assertEquals(1, covers(activity));
    }

    @Test
    public void lockAgainWaitsAsLongAsYouPicked() {
        Settings.LOCK_AGAIN.save(LockDelay.FIVE_MINUTES);
        MessagesLock.watch(RuntimeEnvironment.getApplication());
        ActivityController<Activity> controller = inbox();
        MessagesLock.check(controller.get());
        asks.get(0)[0].run();

        controller.pause().stop();
        SystemClock.sleep(4 * 60_000);
        assertFalse("locked before five minutes away", MessagesLock.locked());
        // Restarted, as Android brings a stopped activity back, so the next stop is a real one.
        controller.restart().resume();
        assertFalse(MessagesLock.locked());
        assertEquals(1, asks.size());

        // Away again: the time starts over, and once it's up the notifications are hidden at once.
        controller.pause().stop();
        SystemClock.sleep(4 * 60_000);
        assertFalse(MessagesLock.locked());
        SystemClock.sleep(60_000);
        assertTrue("five minutes away didn't lock", MessagesLock.locked());
        Notification message = message("ig_direct", Notification.CATEGORY_MESSAGE);
        assertNotSame(message, MessagesLock.notification(message));
    }

    @Test
    public void turningALockOnWaitsUntilYouLeave() {
        Settings.LOCK_MESSAGES.save(false);
        MessagesLock.openUntilLeft();
        Settings.LOCK_APP.save(true);
        assertFalse(MessagesLock.locked());

        // Turned on while the other lock is locked, it opens nothing.
        MessagesLock.relock(true);
        MessagesLock.openUntilLeft();
        assertTrue(MessagesLock.locked());
    }

    @Test
    public void aPhoneWithoutAScreenLockLeavesTheMessagesOpenAndSaysWhy() {
        MessagesLock.asker = MessagesLock.realAskerForTests();
        Activity activity = inbox().get();

        MessagesLock.check(activity);

        assertFalse(MessagesLock.locked());
        ShadowLooper.idleMainLooper();
        assertNotNull(ShadowToast.getTextOfLatestToast());
        MessagesLock.check(activity);
        assertEquals(View.GONE, cover(activity).getVisibility());
    }

    private static ActivityController<Activity> inbox() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(FRAME);
        View list = new View(activity);
        list.setId(LIST);
        frame.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600));
        root.addView(frame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 800));
        activity.setContentView(root);
        layout(activity);
        return controller;
    }

    private static void layout(Activity activity) {
        ShadowLooper.idleMainLooper();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 1920);
    }

    /** Where [view] shows in its window: the part a cover has to hide. */
    private static Rect visible(View view) {
        Rect area = new Rect();
        assertTrue(view.getGlobalVisibleRect(area));
        return area;
    }

    /** The cover the lock put in the activity's window, if any. */
    private static View cover(Activity activity) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        for (int i = 0; i < decor.getChildCount(); i++) {
            View child = decor.getChildAt(i);
            if (MessagesLock.COVER_TAG.equals(child.getTag())) return child;
        }
        return null;
    }

    private static int covers(Activity activity) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        int count = 0;
        for (int i = 0; i < decor.getChildCount(); i++) {
            if (MessagesLock.COVER_TAG.equals(decor.getChildAt(i).getTag())) count++;
        }
        return count;
    }

    private static Notification message(String channel, String category) {
        Context context = RuntimeEnvironment.getApplication();
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent("open"), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Alice")
                .setContentText("meet me at 8")
                .setContentIntent(open)
                .setGroup("direct")
                .addAction(new Notification.Action.Builder(null, "Reply", open).build());
        if (category != null) builder.setCategory(category);
        return builder.build();
    }
}

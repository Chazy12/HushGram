/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Where Group Instagram's notifications puts each notification Instagram posts, and when it leaves it alone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class NotificationGroupsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    private Context context;
    private NotificationManager manager;

    @Before
    public void setUp() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        context = RuntimeEnvironment.getApplication();
        manager = context.getSystemService(NotificationManager.class);
    }

    @After
    public void restore() {
        Settings.GROUP_NOTIFICATIONS.resetToDefault();
        Settings.GROUP_NOTIFICATIONS_BY_TYPE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private Notification built(String channel, String text) {
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent("open").setPackage(context.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Instagram")
                .setContentText(text)
                .setContentIntent(open)
                .build();
    }

    private Notification shown(String tag, int id) {
        return shadowOf(manager).getNotification(tag, id);
    }

    @Test
    public void offToStartAndOffPostAsBuilt() {
        assertFalse(Settings.GROUP_NOTIFICATIONS.get());
        assertFalse(Settings.GROUP_NOTIFICATIONS_BY_TYPE.get());
        Notification like = built("likes", "liked your post");
        NotificationGroups.notify(manager, "like", 1, like);
        Notification message = built("direct", "sent you a message");
        NotificationGroups.notify(manager, 2, message);

        assertSame(like, shown("like", 1));
        assertSame(message, shown(null, 2));
        assertEquals(2, shadowOf(manager).size());
    }

    @Test
    public void onePutsEveryNotificationInOneGroupWithACount() {
        NotificationGroups.post(manager, "like", 1, built("likes", "first like"), ON, OFF);
        assertEquals(NotificationGroups.ONE_GROUP, shown("like", 1).getGroup());
        assertNull("one notification gets no summary", shown(NotificationGroups.SUMMARY_TAG, NotificationGroups.ONE_GROUP.hashCode()));

        NotificationGroups.post(manager, "like", 2, built("likes", "second like"), ON, OFF);
        NotificationGroups.post(manager, null, 3, built("direct", "a message"), ON, OFF);

        Notification message = shown(null, 3);
        assertEquals(NotificationGroups.ONE_GROUP, message.getGroup());
        assertEquals("a message", message.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertNotNull("the tap still opens what it did", message.contentIntent);
        Notification summary = shown(NotificationGroups.SUMMARY_TAG, NotificationGroups.ONE_GROUP.hashCode());
        assertNotNull(summary);
        assertTrue((summary.flags & Notification.FLAG_GROUP_SUMMARY) != 0);
        assertEquals(NotificationGroups.ONE_GROUP, summary.getGroup());
        assertEquals(3, summary.number);
        assertEquals("3 notifications", summary.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertEquals(Notification.GROUP_ALERT_CHILDREN, summary.getGroupAlertBehavior());
        assertEquals(4, shadowOf(manager).size());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(FamilyNames.NOTIFICATION_GROUPS) && report.contains(NotificationGroups.GROUPED + " 3"));
        assertTrue(HookStatus.missing(FamilyNames.NOTIFICATION_GROUPS).toString(),
                HookStatus.missing(FamilyNames.NOTIFICATION_GROUPS).isEmpty());
    }

    @Test
    public void byTypeGivesEachChannelItsOwnGroup() {
        NotificationGroups.post(manager, "like", 1, built("likes", "first like"), ON, ON);
        NotificationGroups.post(manager, "like", 2, built("likes", "second like"), ON, ON);
        NotificationGroups.post(manager, null, 3, built("direct", "a message"), ON, ON);

        String likes = NotificationGroups.TYPE_GROUP + "likes";
        String direct = NotificationGroups.TYPE_GROUP + "direct";
        assertEquals(likes, shown("like", 2).getGroup());
        assertEquals(direct, shown(null, 3).getGroup());
        assertEquals(2, shown(NotificationGroups.SUMMARY_TAG, likes.hashCode()).number);
        assertNull(shown(NotificationGroups.SUMMARY_TAG, direct.hashCode()));
    }

    @Test
    public void aGroupDownToOneLosesItsSummary() {
        NotificationGroups.post(manager, "like", 1, built("likes", "first like"), ON, OFF);
        NotificationGroups.post(manager, "like", 2, built("likes", "second like"), ON, OFF);
        assertNotNull(shown(NotificationGroups.SUMMARY_TAG, NotificationGroups.ONE_GROUP.hashCode()));

        manager.cancel("like", 1);
        manager.cancel("like", 2);
        NotificationGroups.post(manager, "like", 3, built("likes", "third like"), ON, OFF);

        assertNull(shown(NotificationGroups.SUMMARY_TAG, NotificationGroups.ONE_GROUP.hashCode()));
        assertEquals(1, shadowOf(manager).size());
    }

    @Test
    public void instagramsOwnSummaryIsLeftOutOnlyWhileOn() {
        Notification summary = new Notification.Builder(context, "direct")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setGroup("instagram_direct")
                .setGroupSummary(true)
                .build();

        NotificationGroups.post(manager, "summary", 9, summary, ON, OFF);
        assertNull(shown("summary", 9));

        NotificationGroups.post(manager, "summary", 9, summary, OFF, OFF);
        assertSame(summary, shown("summary", 9));
    }

    @Test
    public void anOngoingNotificationStaysAsBuilt() {
        Notification upload = new Notification.Builder(context, "uploads")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentText("Posting")
                .setOngoing(true)
                .build();

        NotificationGroups.post(manager, null, 5, upload, ON, OFF);

        assertSame(upload, shown(null, 5));
        assertNull(upload.getGroup());
    }

    @Test
    public void aFailureStillPostsTheNotificationAsBuilt() {
        Notification like = built("likes", "liked your post");

        NotificationGroups.post(manager, "like", 1, like, THROWS, OFF);

        assertSame(like, shown("like", 1));
        assertFalse(HookStatus.missing(FamilyNames.NOTIFICATION_GROUPS).isEmpty());
    }

    @Test
    public void pausedAndUnreadyPostAsBuilt() {
        Settings.GROUP_NOTIFICATIONS.save(true);
        Notification like = built("likes", "liked your post");
        NotificationGroups.notify(manager, "like", 1, like);
        assertEquals(NotificationGroups.ONE_GROUP, shown("like", 1).getGroup());

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(app.hushgram.extension.shared.settings.HushgramPause.Reason.SWITCH);
        NotificationGroups.notify(manager, "like", 2, like);
        assertSame(like, shown("like", 2));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> NotificationGroups.notify(manager, "like", 3, like));
        assertSame(like, shown("like", 3));
    }

    @Test
    public void theSecondSwitchPicksAGroupPerType() {
        Settings.GROUP_NOTIFICATIONS.save(true);
        Settings.GROUP_NOTIFICATIONS_BY_TYPE.save(true);
        NotificationGroups.notify(manager, 7, built("comments", "commented"));
        assertEquals(NotificationGroups.TYPE_GROUP + "comments", shown(null, 7).getGroup());
        assertEquals(NotificationGroups.TYPE_GROUP + NotificationGroups.NO_CHANNEL,
                NotificationGroups.groupFor(new Notification(), true));
    }
}

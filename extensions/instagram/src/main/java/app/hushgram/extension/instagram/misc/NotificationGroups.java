/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.StatusBarNotification;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Group Instagram's notifications" patch.
 *
 * <p>The patch hands every notification Instagram posts to {@link #notify} in place of
 * {@code NotificationManager.notify}. While the switch is on, each one is posted in one group, or
 * in a group per notification channel (likes, comments, messages and so on) with the second switch
 * on, and a quiet summary showing the count is posted once a group has two. Instagram's own group
 * summaries are left out while that's on, since every child has moved to HushGram's groups.
 * Ongoing notifications, like an upload's progress, stay as Instagram built them. Tapping a
 * notification still opens what it did, since only its group changes.
 *
 * <p>With the switch off, HushGram paused, the settings not read yet or anything thrown while
 * regrouping, the notification is posted exactly as Instagram built it. What the manager itself
 * throws reaches Instagram as before.
 */
public final class NotificationGroups {
    /** The group every notification joins with one group. */
    static final String ONE_GROUP = "hushgram_notifications";

    /** The start of each channel's group key with a group per type. */
    static final String TYPE_GROUP = "hushgram_notifications_";

    /** The group of a notification with no channel, with a group per type. */
    static final String NO_CHANNEL = "other";

    /** The tag HushGram's summaries are posted under, so they never meet Instagram's own ids. */
    static final String SUMMARY_TAG = "hushgram_notification_group";

    /** The step a failed regroup is reported under. */
    static final String REGROUP = "regroup";

    /** The step a failed summary is reported under. */
    static final String SUMMARY = "summary";

    /** What's counted for each notification moved into a group. */
    static final String GROUPED = "grouped";

    /** A group gets its summary once it has this many notifications. */
    static final int SUMMARY_FROM = 2;

    private static volatile boolean logged;

    private NotificationGroups() {
    }

    /** In place of {@code manager.notify(tag, id, notification)}. */
    public static void notify(NotificationManager manager, String tag, int id, Notification notification) {
        post(manager, tag, id, notification, NotificationGroups::switchedOn, NotificationGroups::byType);
    }

    /** In place of {@code manager.notify(id, notification)}, which is the same with no tag. */
    public static void notify(NotificationManager manager, int id, Notification notification) {
        post(manager, null, id, notification, NotificationGroups::switchedOn, NotificationGroups::byType);
    }

    static void post(NotificationManager manager, String tag, int id, Notification notification,
                     BooleanSupplier on, BooleanSupplier byType) {
        String group = null;
        Notification posted = notification;
        try {
            HookStatus.invoked(FamilyNames.NOTIFICATION_GROUPS);
            if (notification != null && !isOngoing(notification) && on.getAsBoolean()) {
                if (isSummary(notification)) {
                    // Every child is in HushGram's groups now, so Instagram's summary would stand alone.
                    return;
                }
                group = groupFor(notification, byType.getAsBoolean());
                posted = regrouped(notification, group);
                HookStatus.counted(FamilyNames.NOTIFICATION_GROUPS, GROUPED);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.NOTIFICATION_GROUPS, REGROUP, failure);
            group = null;
            posted = notification;
        }
        manager.notify(tag, id, posted);
        if (group == null) return;
        try {
            summarize(manager, group, posted);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.NOTIFICATION_GROUPS, SUMMARY, failure);
        }
    }

    /** The group [notification] joins: the one group, or its channel's. */
    static String groupFor(Notification notification, boolean byType) {
        if (!byType) return ONE_GROUP;
        String channel = notification.getChannelId();
        return TYPE_GROUP + (channel == null || channel.isEmpty() ? NO_CHANNEL : channel);
    }

    static boolean isSummary(Notification notification) {
        return (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }

    static boolean isOngoing(Notification notification) {
        return (notification.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0;
    }

    /** A copy of [notification] in [group], made through the platform's own builder so nothing else changes. */
    private static Notification regrouped(Notification notification, String group) {
        if (group.equals(notification.getGroup())) return notification;
        Context context = Utils.getContext();
        Notification copy = Notification.Builder.recoverBuilder(context, notification).setGroup(group).build();
        if (!logged) {
            logged = true;
            Logger.printDebug(() -> "Notification groups: posted a notification in " + group);
        }
        return copy;
    }

    /**
     * Posts or updates [group]'s summary once it holds {@link #SUMMARY_FROM} notifications: quiet,
     * on the newest one's channel and icon, saying how many there are. Below that, a summary left
     * from before is taken down, so a lone notification doesn't sit under a header.
     */
    private static void summarize(NotificationManager manager, String group, Notification newest) {
        int count = 0;
        for (StatusBarNotification shown : manager.getActiveNotifications()) {
            if (SUMMARY_TAG.equals(shown.getTag())) continue;
            Notification active = shown.getNotification();
            if (group.equals(active.getGroup()) && !isSummary(active)) count++;
        }
        if (count < SUMMARY_FROM) {
            manager.cancel(SUMMARY_TAG, group.hashCode());
            return;
        }
        Context context = Utils.getContext();
        Notification.Builder summary = new Notification.Builder(context, newest.getChannelId());
        if (newest.getSmallIcon() != null) {
            summary.setSmallIcon(newest.getSmallIcon());
        } else {
            summary.setSmallIcon(context.getApplicationInfo().icon);
        }
        summary.setContentTitle(context.getApplicationInfo().loadLabel(context.getPackageManager()))
                .setContentText(L10n.f("%d notifications", count))
                .setNumber(count)
                .setGroup(group)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN);
        manager.notify(SUMMARY_TAG, group.hashCode(), summary.build());
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.GROUP_NOTIFICATIONS.get();
    }

    private static boolean byType() {
        return Settings.GROUP_NOTIFICATIONS_BY_TYPE.get();
    }
}

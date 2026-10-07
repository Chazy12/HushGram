/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.format.DateUtils;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Details in the menu of a feed post, with the Details switch on.
 *
 * <p>The row is offered beside Save all and Open in another player, before the builder splits into
 * your own and others' rows, so every post gets it, and the short menu keeps it after them. A tap
 * shows what Instagram already holds for the post, or for a carousel the page on screen: when it
 * went up, who posted it, its media ID, the page's place in the carousel and the size of the file a
 * Download would fetch, with a button that copies that file's direct address. Nothing is fetched to
 * show it.
 *
 * <p>The address is a signed link to the file on Meta's servers, so it goes on the clipboard marked
 * sensitive and never into a log.
 */
public final class PostInfo {
    /** The name of the row's option, made once, as Save all's is. */
    static final String OPTION = "HUSHGRAM_POST_DETAILS";

    /** How the post's time reads: the date with the year, and the time, in the phone's style. */
    static final int FORMAT = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_TIME
            | DateUtils.FORMAT_ABBREV_MONTH;

    /** Past this many seconds, the time in milliseconds no longer fits a long. */
    private static final long LAST_SECOND = Long.MAX_VALUE / 1000L;

    private static Object option;

    private PostInfo() {
    }

    /** The row's option, made once. Null when it can't be made. Never throws. */
    public static synchronized Object option() {
        try {
            if (option == null) option = InstagramMedia.feedOption(OPTION);
            return option;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed details option", failure);
            return null;
        }
    }

    /** Whether the switch is on, which a pause answers off. */
    static boolean on() {
        try {
            return Utils.settingsReady() && Settings.POST_DETAILS.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed details switch", failure);
            return false;
        }
    }

    /**
     * Adds Details to [rows], the list the feed menu's builder [menu] fills, when the switch is on
     * and the menu is for a post. Never throws.
     */
    public static void offer(Object menu, ArrayList<?> rows) {
        try {
            if (menu == null || rows == null || !on()) return;
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            if (InstagramMedia.feedMenuMedia(menu) == null) return;
            Object row = option();
            if (row != null) InstagramMedia.addSaveAllRow(menu, rows, row, L10n.t("Details"));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed details row", failure);
        }
    }

    /**
     * [options], the options the short feed menu keeps, with Details after HushGram's own rows that
     * are on it, [ours], or in front when none is. A list that has it already comes back as it came.
     */
    static List<?> withDetails(List<?> options, Object... ours) {
        Object details = option();
        if (details == null || options.contains(details)) return options;
        List<Object> allowed = new ArrayList<>(options);
        int after = -1;
        for (Object row : ours) {
            if (row != null) after = Math.max(after, allowed.indexOf(row));
        }
        allowed.add(after + 1, details);
        return allowed;
    }

    /**
     * Shows the details of the post [media], or of the carousel page on screen that [itemState],
     * the post's feed state, names, over [activity], when its Details row is tapped. Never throws.
     */
    public static void show(Object media, Object itemState, Activity activity) {
        try {
            if (!on() || media == null || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            Facts facts = Facts.of(VideoDownload.shown(media, itemState), media);
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t(activity, "Details"))
                    .setMessage(facts.text(activity))
                    .setNegativeButton(L10n.t(activity, "Close"), null);
            String link = facts.link();
            if (link != null) builder.setPositiveButton(L10n.t(activity, "Copy media link"), (dialog, which) -> copy(activity, link));
            AlertDialog dialog = builder.create();
            dialog.show();
            TextView message = dialog.findViewById(android.R.id.message);
            // So the ID or the name can be copied on its own.
            if (message != null) message.setTextIsSelectable(true);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed details", failure);
        }
    }

    /** Puts [link] on the clipboard, marked sensitive, and says so. */
    static void copy(Context context, String link) {
        try {
            Utils.setClipboard(context, L10n.t(context, "Media link"), link);
            Utils.showToastShort(L10n.t(context, "Media link copied"));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "copy media link", failure);
            try {
                Utils.showToastShort(L10n.t(context, "Couldn't copy the media link"));
            } catch (Throwable feedbackFailure) {
                HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "copy media link feedback", feedbackFailure);
            }
        }
    }

    /** What the details show of one post or page, read on the tap. */
    static final class Facts {
        /** When it went up, in seconds since 1970, or null. */
        @Nullable final Long posted;
        /** Who posted it, or null. */
        @Nullable final String owner;
        /** Instagram's media ID, {@code <media pk>_<owner's pk>}, or null. */
        @Nullable final String id;
        /** Its place in the carousel, counted from 1, and the carousel's pages, or 0 and 0. */
        final int page;
        final int pages;
        /** The file a Download would fetch, or null when there's none. */
        @Nullable final MediaSave.Rendition file;

        Facts(@Nullable Long posted, @Nullable String owner, @Nullable String id, int page, int pages,
              @Nullable MediaSave.Rendition file) {
            this.posted = posted;
            this.owner = owner;
            this.id = id;
            this.page = page;
            this.pages = pages;
            this.file = file;
        }

        /**
         * The facts of [shown], the post [post] itself or the page of its carousel on screen. A page
         * keeps its own ID, and takes the poster and the time from the post when it doesn't list them.
         * A carousel whose page on screen isn't known, [shown] null, shows the post's facts and no
         * file, since its first page might not be the one on screen.
         */
        static Facts of(@Nullable Object shown, Object post) {
            if (shown == null) {
                Facts known = of(post, post);
                return new Facts(known.posted, known.owner, known.id, 0, 0, null);
            }
            Long posted = InstagramMedia.takenAt(shown);
            if ((posted == null || posted <= 0) && shown != post) posted = InstagramMedia.takenAt(post);
            Object user = InstagramMedia.owner(shown);
            if (user == null && shown != post) user = InstagramMedia.owner(post);
            String owner = user == null ? null : InstagramMedia.username(user);
            String id = InstagramMedia.mediaId(shown);
            List<?> carousel = InstagramMedia.carouselMedia(post);
            int pages = carousel == null || shown == post ? 0 : carousel.size();
            int page = pages == 0 ? 0 : VideoDownload.pageOf(shown, post);
            MediaSave.Rendition file = VideoDownload.hasVideo(shown)
                    ? MediaSave.picked(ReelDownload.renditions(shown), true)
                    : MediaSave.picked(StoryDownload.pictures(shown), false);
            return new Facts(posted == null || posted <= 0 ? null : posted, empty(owner) ? null : owner,
                    empty(id) ? null : id, page, page == 0 ? 0 : pages, file);
        }

        /** The address to copy, or null when there's no file. */
        @Nullable
        String link() {
            return file == null ? null : file.url;
        }

        /** One line for each fact known, in the phone's language. */
        String text(Context context) {
            List<String> lines = new ArrayList<>();
            if (posted != null && posted <= LAST_SECOND) {
                lines.add(L10n.f(context, "Posted %1$s", DateUtils.formatDateTime(context, posted * 1000L, FORMAT)));
            }
            if (owner != null) lines.add(L10n.f(context, "By @%1$s", L10n.isolate(owner)));
            if (page > 0) lines.add(L10n.f(context, "Page %1$d of %2$d", page, pages));
            if (file != null && file.width > 0 && file.height > 0) {
                lines.add(L10n.f(context, "Size %1$d × %2$d", file.width, file.height));
            }
            if (id != null) lines.add(L10n.f(context, "Media ID %1$s", L10n.isolate(id)));
            if (file == null) lines.add(L10n.t(context, "No direct link for this one"));
            return String.join("\n", lines);
        }

        private static boolean empty(String text) {
            return text == null || text.trim().isEmpty();
        }
    }
}

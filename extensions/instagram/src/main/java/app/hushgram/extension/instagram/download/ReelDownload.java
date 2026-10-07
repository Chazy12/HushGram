/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Download in every reel's more menu.
 *
 * <p>Instagram has a Download row of its own there, which it shows only on reels whose owner lets
 * other people download them, and which fetches a copy with a watermark after asking Instagram's
 * server. The patch keeps that row and changes who shows it and what a tap does:
 *
 * <ul>
 *   <li>Each builder of the menu asks {@link #offer} with Instagram's answer to whether the reel
 *       may be downloaded, and, in the builders that also read a server flag that holds the row
 *       back, {@link #withhold} with the flag. With the switch on, every reel gets the row.
 *   <li>The menu's handler asks {@link #save} first when Download is tapped. With the switch on,
 *       the reel is saved from the addresses its Media already holds, through {@link MediaSave},
 *       and Instagram's own download never starts. A photo the Reels viewer shows with its music
 *       has no video at all, so it saves its picture at the largest size instead (#71).
 *   <li>The menu's adder of one row asks {@link #rows} first for Download. A photo that comes with
 *       music gets two rows in its place, Download as video and Download as photo, as a photo
 *       story with music does. As video builds an MP4 of the photo with the post's part of the
 *       track ({@link MusicVideo}), and the handler hands a tap on either row to {@link #save} too.
 *   <li>With Download cover on, a reel with a video gets two rows of ours in Download's place,
 *       Download and Download cover. Download saves the reel as Instagram's own row would, and
 *       Download cover saves the still picture Instagram shows before the reel plays, its image
 *       versions at the largest size (#48). A frame of the video is never a stand in.
 * </ul>
 *
 * <p>Every hook fails open: until the settings are ready, while HushGram is paused, with the switch
 * off, or when something throws, the menu is Instagram's own.
 */
public final class ReelDownload {
    private ReelDownload() {
    }

    /** The source a reel save's lines carry in the diagnostic report. */
    private static final String SOURCE = "ReelDownload";

    /**
     * What a tap on Download found, counted in the diagnostic report under these fixed labels, so
     * a report shows which way the save went: no single video file, no DASH manifest, a picture to
     * fall back on, and the picture's save started.
     */
    static final String NO_VIDEO_VERSIONS = "no video versions";
    static final String NO_MANIFEST = "no manifest";
    static final String HAS_IMAGE_CANDIDATES = "has image candidates";
    static final String SAVED_AS_PHOTO = "saved as photo";

    /**
     * What the menu found for a photo with music, and what a tap on Download as video started:
     * music with a track to fetch, which brings the two rows, music with none, and the video's
     * build under way. {@link MusicVideo} counts how the build ends.
     */
    static final String HAS_MUSIC = "has music";
    static final String NO_AUDIO_URL = "no audio url";
    static final String SAVED_AS_VIDEO = "saved as video";

    /**
     * The names of the two options a photo with music gets in Download's place. They're made like
     * Download, with its icon and ordinal, so the menu draws them and hands a tap on them to its
     * handler the way it does Download.
     */
    static final String VIDEO_OPTION = "HUSHGRAM_DOWNLOAD_AS_VIDEO";
    static final String PHOTO_OPTION = "HUSHGRAM_DOWNLOAD_AS_PHOTO";

    /**
     * The names of the two options a reel with a video gets in Download's place with Download cover
     * on: Download, which saves the reel as Instagram's own row would, and Download cover. They're
     * made the same way as the two above.
     */
    static final String REEL_OPTION = "HUSHGRAM_DOWNLOAD_REEL";
    static final String COVER_OPTION = "HUSHGRAM_DOWNLOAD_COVER";

    /** Every row of ours, by name. */
    private static final List<String> OUR_ROWS = Arrays.asList(VIDEO_OPTION, PHOTO_OPTION, REEL_OPTION, COVER_OPTION);

    /** What the menu found for a reel's cover, and what a tap on Download cover started. */
    static final String HAS_COVER = "has cover";
    static final String SAVED_COVER = "saved cover";

    /**
     * The entry the patch calls, handed Instagram's answer as an int, non-zero for yes, so the hook
     * doesn't depend on Instagram's code leaving that register typed as a boolean.
     */
    public static boolean offer(int eligible) {
        return offer(eligible != 0);
    }

    /** Instagram's answer [eligible] to whether this reel has a Download row, or yes with the switch on. Never throws. */
    public static boolean offer(boolean eligible) {
        return eligible || on();
    }

    /** The entry the patch calls, handed Instagram's flag as an int, non-zero for yes, as {@link #offer(int)} is. */
    public static boolean withhold(int held) {
        return withhold(held != 0);
    }

    /** Instagram's flag [held] that keeps the Download row out, or no with the switch on. Never throws. */
    public static boolean withhold(boolean held) {
        return held && !on();
    }

    /** The options the reduced reel menu lists above Download, by the names Instagram keeps. */
    private static final List<String> ABOVE_DOWNLOAD = Arrays.asList("SHOP_SIMILAR", "SAVE", "UNSAVE");

    /**
     * Adds [download], Instagram's Download option, to [options], the list the reduced reel menu
     * shows, with the switch on. It goes after the save rows, which puts it above Playback as in the
     * full menu. A list that has it already is left alone. Never throws.
     */
    public static void addTo(List<Object> options, Object download) {
        try {
            if (options == null || download == null || !on() || options.contains(download)) return;
            int at = 0;
            for (int i = 0; i < options.size(); i++) {
                Object option = options.get(i);
                if (option instanceof Enum && ABOVE_DOWNLOAD.contains(((Enum<?>) option).name())) at = i + 1;
            }
            options.add(at, download);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reduced reel menu", t);
        }
    }

    /**
     * Adds Download as video and Download as photo to the reel menu in Download's place, when the
     * reel [media] is a photo that comes with music and the switch is on, and answers whether it
     * did, in which case Instagram's own Download row is left out. [menu] is the menu's helper, and
     * [context], [sheet] and [rowState] are what its adder of one row was handed for Download. Any
     * other reel answers false and gets the one Download row. Never throws.
     */
    public static boolean rows(Object menu, Object media, Object context, Object sheet, Object rowState) {
        try {
            if (media == null || !on()) return false;
            if (!renditions(media).isEmpty() || InstagramMedia.dashManifest(media) != null) {
                return coverRows(menu, media, context, sheet, rowState);
            }
            if (StoryDownload.pictures(media).isEmpty()) return false;
            MusicVideo.Music music = MusicVideo.music(media);
            if (music == null) return false;
            if (music.url == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, NO_AUDIO_URL);
                return false;
            }
            HookStatus.counted(FamilyNames.REEL_DOWNLOAD, HAS_MUSIC);
            Object video = InstagramMedia.reelOption(VIDEO_OPTION);
            Object photo = InstagramMedia.reelOption(PHOTO_OPTION);
            if (video == null || photo == null) return false;
            if (!InstagramMedia.addReelRow(menu, context, video, sheet, rowState, StoryDownload.label(StoryDownload.Choice.VIDEO))) {
                return false;
            }
            try {
                return InstagramMedia.addReelRow(menu, context, photo, sheet, rowState,
                    StoryDownload.label(StoryDownload.Choice.PHOTO));
            } catch (Throwable t) {
                // Instagram's own Download row follows the video's then, and a tap on it saves the photo.
                HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel menu photo row", t);
                return false;
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel menu rows", t);
            return false;
        }
    }

    /**
     * Download and Download cover in Download's place for [media], a reel with a video, when Download
     * cover is on and the reel lists its picture, and whether they went in. Once Download is in, the
     * answer is yes even if the cover's row isn't, so Instagram's own row doesn't come as a second
     * Download.
     */
    private static boolean coverRows(Object menu, Object media, Object context, Object sheet, Object rowState) {
        if (!coverOn() || StoryDownload.pictures(media).isEmpty()) return false;
        Object reel = InstagramMedia.reelOption(REEL_OPTION);
        Object cover = InstagramMedia.reelOption(COVER_OPTION);
        if (reel == null || cover == null) return false;
        if (!InstagramMedia.addReelRow(menu, context, reel, sheet, rowState,
                StoryDownload.label(StoryDownload.Choice.STORY))) {
            return false;
        }
        HookStatus.counted(FamilyNames.REEL_DOWNLOAD, HAS_COVER);
        try {
            InstagramMedia.addReelRow(menu, context, cover, sheet, rowState, L10n.t(Utils.getContext(), "Download cover"));
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel menu cover row", t);
        }
        return true;
    }

    /** Whether [option], one the reel menu's handler was handed, is a row {@link #rows} added. Never throws. */
    public static boolean ours(Object option) {
        try {
            return row(option) != null;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel menu option", t);
            return false;
        }
    }

    /** The name of [option] when it's one of our rows, else null. */
    private static String row(Object option) {
        if (!(option instanceof Enum)) return null;
        String name = ((Enum<?>) option).name();
        return OUR_ROWS.contains(name) ? name : null;
    }

    /**
     * Saves the reel [media] when [option], its Download row or one {@link #rows} added, is tapped
     * with the switch on, and answers whether it did, in which case Instagram's own handling is
     * skipped. [activity] is the one the menu belongs to. A save that can't start says so. Never
     * throws.
     */
    public static boolean save(Object option, Object media, Activity activity) {
        // A row of ours is no option Instagram knows, so a tap on one never goes on to Instagram,
        // even with the switch turned off while the menu was open.
        boolean ours = false;
        try {
            HookStatus.invoked(FamilyNames.REEL_DOWNLOAD);
            String row = row(option);
            ours = row != null;
            if (!on()) return ours;
            Context context = activity != null ? activity : Utils.getContext();
            if ((row == null || REEL_OPTION.equals(row))
                    && ExternalDownload.handOff(context, ExternalDownload.postLink(media, true), FamilyNames.REEL_DOWNLOAD)) {
                return true;
            }
            boolean started = VIDEO_OPTION.equals(row) ? saveAsVideo(context, media)
                : PHOTO_OPTION.equals(row) ? savePicture(context, StoryDownload.pictures(media), media)
                : COVER_OPTION.equals(row) ? saveCover(context, media)
                : saveReel(context, media);
            if (!started) {
                Context application = context.getApplicationContext();
                Feedback.show(application, L10n.t(application, "Download failed"), true);
            }
            return true;
        } catch (Throwable t) {
            // It runs inside Instagram's click dispatch, where a throw ends the app. Instagram's own
            // download goes ahead instead, for its own Download row.
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel menu", t);
            return ours;
        }
    }

    /**
     * Starts the save of the reel [media] and answers whether it started: its video, or, for an
     * item with neither a single video file nor a manifest, its picture at the largest size. The
     * Reels viewer shows photos that come with music, and those have no video at all (#71).
     */
    static boolean saveReel(Context context, Object media) {
        List<MediaSave.Rendition> renditions = renditions(media);
        String manifest = InstagramMedia.dashManifest(media);
        final int files = renditions.size();
        final boolean dash = manifest != null;
        if (files == 0) HookStatus.counted(FamilyNames.REEL_DOWNLOAD, NO_VIDEO_VERSIONS);
        if (!dash) HookStatus.counted(FamilyNames.REEL_DOWNLOAD, NO_MANIFEST);
        if (files > 0 || dash) {
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "reel download tapped: " + files + " file(s)" + (dash ? " and a manifest" : ", no manifest"));
            return MediaSave.saveVideo(context, renditions, manifest, details(media));
        }
        List<MediaSave.Rendition> pictures = StoryDownload.pictures(media);
        final int sizes = pictures.size();
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "reel download tapped: no video file and no manifest, " + sizes + " picture size(s)");
        if (sizes == 0) return false;
        HookStatus.counted(FamilyNames.REEL_DOWNLOAD, HAS_IMAGE_CANDIDATES);
        return savePicture(context, pictures, media);
    }

    /** Starts the save of [pictures], the sizes of [media]'s picture, at the largest, and answers whether it started. */
    private static boolean savePicture(Context context, List<MediaSave.Rendition> pictures, Object media) {
        if (pictures.isEmpty()) return false;
        boolean started = MediaSave.savePhoto(context, pictures, details(media));
        if (started) HookStatus.counted(FamilyNames.REEL_DOWNLOAD, SAVED_AS_PHOTO);
        return started;
    }

    /**
     * Starts the save of [media]'s cover, the still picture Instagram shows before the reel plays,
     * and answers whether it started. The sizes are all of that one picture, so the largest goes.
     */
    static boolean saveCover(Context context, Object media) {
        List<MediaSave.Rendition> pictures = StoryDownload.pictures(media);
        final int sizes = pictures.size();
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "reel cover tapped: " + sizes + " picture size(s)");
        if (sizes == 0) return false;
        boolean started = MediaSave.savePhoto(context, pictures, details(media));
        if (started) HookStatus.counted(FamilyNames.REEL_DOWNLOAD, SAVED_COVER);
        return started;
    }

    /**
     * Starts building and saving a video of [media], a photo with music: its largest picture held
     * for the part of the track the post plays, with that part as its sound. Answers whether it
     * started.
     */
    static boolean saveAsVideo(Context context, Object media) {
        MediaSave.Rendition picture = MusicVideo.picture(StoryDownload.pictures(media));
        MusicVideo.Music music = MusicVideo.music(media);
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "reel download as video tapped: "
            + (picture == null ? "no picture" : picture.toString()) + ", " + (music == null ? "no music" : music.toString()));
        if (music == null || music.url == null) {
            HookStatus.counted(FamilyNames.REEL_DOWNLOAD, NO_AUDIO_URL);
            return false;
        }
        if (picture == null) return false;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Thread worker = MediaSave.start(application, true, details(media), (writer, progress) ->
            MusicVideo.save(application, picture, music, writer, MediaSave.policyFor(application), MediaSave.cap(), progress));
        if (worker == null) return false;
        HookStatus.counted(FamilyNames.REEL_DOWNLOAD, SAVED_AS_VIDEO);
        return true;
    }

    private static boolean on() {
        try {
            return Utils.settingsReady() && Settings.DOWNLOAD_REELS.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel menu switch", t);
            return false;
        }
    }

    /** Whether a reel with a video gets Download cover. Never throws. */
    private static boolean coverOn() {
        try {
            return Utils.settingsReady() && Settings.DOWNLOAD_REEL_COVER.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel cover switch", t);
            return false;
        }
    }

    /** The single files [media] lists for its video, with the size each states. Never null. */
    static List<MediaSave.Rendition> renditions(Object media) {
        List<?> versions = InstagramMedia.videoVersions(media);
        if (versions == null) return Collections.emptyList();
        List<MediaSave.Rendition> renditions = new ArrayList<>(versions.size());
        for (Object version : versions) {
            if (version == null) continue;
            String url = InstagramMedia.versionUrl(version);
            if (url == null || url.isEmpty()) continue;
            renditions.add(new MediaSave.Rendition(url, orZero(InstagramMedia.versionWidth(version)),
                    orZero(InstagramMedia.versionHeight(version)), 0));
        }
        return renditions;
    }

    /** The id, poster and posting day of [media], each null when it doesn't say. */
    static PostDetails details(Object media) {
        Object user = InstagramMedia.owner(media);
        String owner = user == null ? null : InstagramMedia.username(user);
        Long takenAt = InstagramMedia.takenAt(media);
        Date posted = takenAt == null || takenAt <= 0 ? null : new Date(takenAt * 1000L);
        return PostDetails.of(InstagramMedia.mediaId(media), owner, posted);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}

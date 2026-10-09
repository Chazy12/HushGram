/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.content.Context;

import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.download.Feedback;
import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.download.MediaSave;
import app.hushgram.extension.instagram.download.PostDetails;
import app.hushgram.extension.instagram.download.ReelDownload;
import app.hushgram.extension.instagram.download.StoryDownload;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Saves View-Once and disappearing photos and videos in direct messages directly to disk,
 * eliminating the need to take screenshots or record the screen.
 */
public final class ViewOnceDownload {
    private static final String SOURCE = "ViewOnceDownload";

    private ViewOnceDownload() {}

    /**
     * Checks if saving view-once media is enabled.
     */
    public static boolean isEnabled() {
        return Utils.settingsReady() && Settings.VIEW_ONCE_DOWNLOAD.get();
    }

    /**
     * Saves a view-once or expiring visual message (photo or video) directly to storage.
     *
     * @param media The direct visual message or media object
     * @param context Android context
     * @return true if the download was initiated, false otherwise
     */
    public static boolean save(Object media, Context context) {
        if (media == null || !isEnabled()) return false;
        try {
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            Context appContext = context != null ? context.getApplicationContext() : Utils.getContext();
            if (appContext == null) return false;

            // Check if it's a video
            List<MediaSave.Rendition> videoRenditions = ReelDownload.renditions(media);
            String dashManifest = InstagramMedia.dashManifest(media);

            boolean isVideo = !videoRenditions.isEmpty() || dashManifest != null;
            PostDetails details = PostDetails.of(InstagramMedia.mediaId(media), null, null);

            boolean started;
            if (isVideo) {
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                        () -> "Saving disappearing video directly (" + videoRenditions.size() + " renditions)");
                started = MediaSave.saveVideo(appContext, videoRenditions, dashManifest, details);
            } else {
                List<MediaSave.Rendition> photos = StoryDownload.pictures(media);
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                        () -> "Saving disappearing photo directly (" + photos.size() + " candidates)");
                started = MediaSave.savePhoto(appContext, photos, details);
            }

            if (started) {
                Feedback.show(appContext, L10n.t(appContext, "Saving disappearing media..."), false);
            } else {
                Feedback.show(appContext, L10n.t(appContext, "Download failed"), true);
            }
            return started;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "view once download", t);
            return false;
        }
    }
}

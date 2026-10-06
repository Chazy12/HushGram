/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import kotlin.jvm.functions.Function0;
import app.hushgram.extension.instagram.download.CommentPhotoDownload;
import app.hushgram.extension.instagram.download.MediaSave;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** An explicit Save action in the selected comment's native menu, for the comment's own photo. */
public final class CommentPhoto {
    private CommentPhoto() {}

    interface NativeRows {
        List<MediaSave.Rendition> photo(Object comment);
        Object row(Object callback);
        Object callback(Object row);
    }

    interface Save {
        void photo(Context context, List<MediaSave.Rendition> snapshot);
    }

    /** The native reads behind a comment's photo, one Instagram getter each, made in this order. */
    interface PhotoReads {
        boolean selected(Object comment);
        Object raw(Object selected);
        Object gif(Object raw);
        Object info(Object raw);
        Object media(Object info);
        Object kind(Object media);
        int photoKind();
        Object mediaGif(Object media);
    }

    private static final PhotoReads READS = new PhotoReads() {
        public boolean selected(Object comment) { return CommentPhotoNative.selected(comment) != 0; }
        public Object raw(Object selected) { return CommentPhotoNative.raw(selected); }
        public Object gif(Object raw) { return CommentPhotoNative.gif(raw); }
        public Object info(Object raw) { return CommentPhotoNative.info(raw); }
        public Object media(Object info) { return CommentPhotoNative.media(info); }
        public Object kind(Object media) { return CommentPhotoNative.kind(media); }
        public int photoKind() { return CommentPhotoNative.photoKind(); }
        public Object mediaGif(Object media) { return CommentPhotoNative.mediaGif(media); }
    };

    // What the diagnostic report counts when a read finds no photo, one name per step. The names
    // are fixed text: nothing read from the comment goes in, bar a media_type kept to a small number.
    static final String NOT_SELECTED = "not a selected comment";
    static final String NO_RAW = "no raw comment";
    static final String COMMENT_GIF = "comment GIF";
    static final String NO_INFO = "no media_comment_info";
    static final String NO_MEDIA = "no media in media_comment_info";
    static final String NO_KIND = "no media_type";
    static final String MEDIA_GIF = "media GIF";
    /**
     * Instagram's media types are single digits (1 photo, 2 video, 8 carousel). Anything else shares
     * one name, so a strange value can't use up the sixteen names a family's counts keep.
     */
    private static final int KINDS_NAMED = 10;

    private static final NativeRows NATIVE = new NativeRows() {
        public List<MediaSave.Rendition> photo(Object comment) {
            return CommentPhotoDownload.snapshot(photoMedia(comment, READS));
        }
        public Object row(Object callback) { return CommentPhotoNative.newRow(callback); }
        public Object callback(Object row) { return CommentPhotoNative.callback(row); }
    };

    private static final Save SAVE = CommentPhotoDownload::save;

    public static List<?> rows(List<?> rows, Object comment, Context context) {
        return rows(rows, comment, context, NATIVE, SAVE);
    }

    static List<?> rows(List<?> rows, Object comment, Context context, NativeRows nativeRows, Save save) {
        try {
            HookStatus.invoked(FamilyNames.COMMENT_PHOTO);
            if (rows == null || context == null || comment == null || !enabled()) return rows;
            // Copied now, so the row saves the photo this menu was opened for.
            List<MediaSave.Rendition> read = nativeRows.photo(comment);
            List<MediaSave.Rendition> snapshot = read == null || read.isEmpty() ? null : CommentPhotoDownload.copy(read);
            int owned = 0;
            PhotoAction existing = null;
            for (Object row : rows) {
                Object callback = nativeRows.callback(row);
                if (callback instanceof PhotoAction) {
                    owned++;
                    existing = (PhotoAction) callback;
                }
            }
            if (snapshot == null) {
                if (owned == 0) return rows;
                List<Object> cleaned = new ArrayList<>(rows.size() - owned);
                for (Object row : rows) {
                    if (!(nativeRows.callback(row) instanceof PhotoAction)) cleaned.add(row);
                }
                return cleaned;
            }
            if (owned == 1 && samePhoto(snapshot, existing.snapshot)) return rows;
            Object photo = nativeRows.row(new PhotoAction(snapshot, context, save));
            if (photo == null) return rows;
            List<Object> augmented = new ArrayList<>(rows.size() + 1);
            for (Object row : rows) {
                if (!(nativeRows.callback(row) instanceof PhotoAction)) augmented.add(row);
            }
            augmented.add(photo);
            return augmented;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_PHOTO, "comment menu", failure);
            return rows;
        }
    }

    /**
     * The Media of the selected comment's own still photo, or null for anything else: another
     * object, a comment with no media, a GIF, a video. Each null is counted under the step that
     * found nothing, so a report from a phone says where a photo comment's read stopped.
     */
    static Object photoMedia(Object comment, PhotoReads reads) {
        if (!reads.selected(comment)) return refused(NOT_SELECTED);
        Object raw = reads.raw(comment);
        if (raw == null) return refused(NO_RAW);
        if (reads.gif(raw) != null) return refused(COMMENT_GIF);
        Object info = reads.info(raw);
        if (info == null) return refused(NO_INFO);
        Object media = reads.media(info);
        if (media == null) return refused(NO_MEDIA);
        Object kind = reads.kind(media);
        if (!(kind instanceof Integer)) return refused(NO_KIND);
        int value = (Integer) kind;
        if (value != reads.photoKind()) {
            return refused(value >= 0 && value < KINDS_NAMED ? "media_type " + value : "media_type other");
        }
        if (reads.mediaGif(media) != null) return refused(MEDIA_GIF);
        return media;
    }

    private static Object refused(String step) {
        HookStatus.counted(FamilyNames.COMMENT_PHOTO, step);
        return null;
    }

    private static boolean enabled() {
        return Utils.settingsReady() && Settings.SAVE_COMMENT_PHOTOS.get();
    }

    private static boolean samePhoto(List<MediaSave.Rendition> current, List<MediaSave.Rendition> shown) {
        if (current.size() != shown.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            MediaSave.Rendition a = current.get(i);
            MediaSave.Rendition b = shown.get(i);
            if (!a.url.equals(b.url) || a.width != b.width || a.height != b.height) return false;
        }
        return true;
    }

    /** Holds only the copied sizes and the short-lived menu's context. */
    public static final class PhotoAction implements Function0<Object> {
        final List<MediaSave.Rendition> snapshot;
        final Context context;
        final Save save;

        PhotoAction(List<MediaSave.Rendition> snapshot, Context context, Save save) {
            this.snapshot = snapshot;
            this.context = context;
            this.save = save;
        }

        /** Instagram ignores this result, then dismisses its menu using the stock callback. */
        @Override public Object invoke() {
            try {
                if (enabled()) save.photo(context, snapshot);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.COMMENT_PHOTO, "save comment photo", failure);
                CommentPhotoDownload.failed(context);
            }
            return null;
        }
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Feedback;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Adds a direct download button to posts in the Instagram home and profile feed.
 *
 * <p>Attaches to feed item action rows (UFI bar beside Like, Comment, Share and Bookmark)
 * or provides a long-press download shortcut so users can save videos and photos
 * with a single tap instead of navigating through the 3-dots overflow menu.
 */
public final class FeedDownloadButton {
    private static final String SOURCE = "FeedDownloadButton";

    private FeedDownloadButton() {}

    /**
     * Checks if the feed download button setting is enabled.
     */
    public static boolean isEnabled() {
        return Utils.settingsReady() && Settings.FEED_DOWNLOAD_BUTTON.get();
    }

    /**
     * Attaches the direct download button or shortcut to a feed row view.
     *
     * @param anchorView View in the UFI row (e.g. repost icon, share icon, or save icon)
     * @param media The Instagram Media object for the post
     * @param itemState The feed state (e.g. for current carousel page index)
     */
    public static void attach(@Nullable View anchorView, @Nullable Object media, @Nullable Object itemState) {
        if (anchorView == null || !isEnabled()) return;
        try {
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);

            if (media != null) {
                // Add long-press shortcut to the anchor view
                anchorView.setOnLongClickListener(v -> {
                    Activity activity = getActivity(v);
                    boolean saved = VideoDownload.save(media, itemState, activity);
                    if (saved) {
                        Feedback.show(v.getContext(), L10n.t(v.getContext(), "Downloading..."), false);
                    }
                    return true;
                });
            }

            // If anchorView is inside a ViewGroup (the UFI button bar), inject a download icon
            if (anchorView.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) anchorView.getParent();
                injectDownloadButton(parent, media, itemState);
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed download button", t);
        }
    }

    /**
     * Injects a download ImageView into the UFI row if not already added.
     */
    private static void injectDownloadButton(ViewGroup container, @Nullable Object media, @Nullable Object itemState) {
        View existing = container.findViewWithTag("hushgram_feed_download_btn");
        if (existing != null) {
            if (media != null) {
                existing.setOnClickListener(v -> performDownload(v, media, itemState));
            }
            return;
        }

        Context context = container.getContext();
        ImageView downloadBtn = new ImageView(context);
        downloadBtn.setTag("hushgram_feed_download_btn");

        try {
            int iconRes = android.R.drawable.stat_sys_download;
            Drawable icon = context.getDrawable(iconRes);
            if (icon != null) {
                downloadBtn.setImageDrawable(icon);
            }
        } catch (Throwable ignored) {}

        int size = (int) (24 * context.getResources().getDisplayMetrics().density);
        int margin = (int) (8 * context.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(margin, 0, margin, 0);
        downloadBtn.setLayoutParams(params);

        downloadBtn.setContentDescription("Download");
        downloadBtn.setClickable(true);
        downloadBtn.setFocusable(true);

        if (media != null) {
            downloadBtn.setOnClickListener(v -> performDownload(v, media, itemState));
            downloadBtn.setOnLongClickListener(v -> {
                Activity activity = getActivity(v);
                VideoDownload.saveAll(media, activity);
                Feedback.show(context, L10n.t(context, "Saving all items..."), false);
                return true;
            });
        }

        container.addView(downloadBtn);
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "Direct download button injected into feed UFI row");
    }

    private static void performDownload(View v, Object media, Object itemState) {
        Activity activity = getActivity(v);
        boolean started = VideoDownload.save(media, itemState, activity);
        if (started) {
            Feedback.show(v.getContext(), L10n.t(v.getContext(), "Download started"), false);
        }
    }

    @Nullable
    private static Activity getActivity(View view) {
        Context context = view.getContext();
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}

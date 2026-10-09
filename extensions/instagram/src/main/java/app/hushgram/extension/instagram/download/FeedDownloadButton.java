/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Adds a direct download button to posts in the Instagram feed.
 *
 * <p>Injected right beside the Bookmark/Save button on feed posts, providing one-tap
 * downloads for videos, carousels, and photos, and a long-press shortcut to save all items.
 */
public final class FeedDownloadButton {
    private static final String SOURCE = "FeedDownloadButton";
    private static final String VIEW_TAG = "hushgram_feed_download_btn";
    private static final Map<View, Object> BOUND_MEDIA = Collections.synchronizedMap(new WeakHashMap<>());

    private FeedDownloadButton() {}

    /**
     * Checks if the feed download button setting is enabled.
     */
    public static boolean isEnabled() {
        return Utils.settingsReady() && Settings.FEED_DOWNLOAD_BUTTON.get();
    }

    /**
     * Entry point called from bytecode when an UFI row is being bound.
     */
    public static void onUfiBound(@Nullable View view) {
        onUfiBound(view, null, null);
    }

    /**
     * Entry point with optional media and feed state.
     */
    public static void onUfiBound(@Nullable View view, @Nullable Object media) {
        onUfiBound(view, media, null);
    }

    /**
     * Entry point with full parameters.
     */
    public static void onUfiBound(@Nullable View view, @Nullable Object media, @Nullable Object itemState) {
        attach(view, media, itemState);
    }

    /**
     * Attaches the direct download button to a feed UFI row.
     *
     * @param anchorView Any view in the UFI row (e.g. Save/Bookmark icon, Share icon, or Repost icon)
     * @param media The Instagram Media object for the post (if available)
     * @param itemState The feed state (e.g. for carousel page index)
     */
    public static void attach(@Nullable View anchorView, @Nullable Object media, @Nullable Object itemState) {
        if (anchorView == null || !isEnabled()) return;
        try {
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);

            if (media != null) {
                BOUND_MEDIA.put(anchorView, media);
            }

            // Find parent ViewGroup of the UFI row
            ViewGroup container = findUfiContainer(anchorView);
            if (container == null) return;

            // Find the Save/Bookmark button in the container
            View bookmarkView = findBookmarkView(container, anchorView);

            // Setup long-press shortcut on the bookmark view
            if (bookmarkView != null) {
                bookmarkView.setOnLongClickListener(v -> {
                    Object targetMedia = media != null ? media : findMedia(v, container);
                    if (targetMedia != null) {
                        Activity activity = getActivity(v);
                        boolean saved = VideoDownload.save(targetMedia, itemState, activity);
                        if (saved) {
                            Feedback.show(v.getContext(), L10n.t(v.getContext(), "Downloading..."), false);
                        }
                        return true;
                    }
                    return false;
                });
            }

            // Inject the dedicated download icon immediately to the left of the bookmark
            injectDownloadButton(container, bookmarkView != null ? bookmarkView : anchorView, media, itemState);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed download button", t);
        }
    }

    /**
     * Injects the download ImageView into the container beside the anchor/bookmark view.
     */
    private static void injectDownloadButton(@NonNull ViewGroup container, @NonNull View anchor,
                                            @Nullable Object media, @Nullable Object itemState) {
        View existing = container.findViewWithTag(VIEW_TAG);
        if (existing instanceof ImageView) {
            ImageView downloadBtn = (ImageView) existing;
            if (media != null) {
                BOUND_MEDIA.put(downloadBtn, media);
            }
            downloadBtn.setOnClickListener(v -> handleDownloadClick(v, media, itemState, container));
            downloadBtn.setOnLongClickListener(v -> handleDownloadLongClick(v, media, container));
            return;
        }

        Context context = container.getContext();
        ImageView downloadBtn = new ImageView(context);
        downloadBtn.setTag(VIEW_TAG);

        // Vector download icon with automatic theme tint
        DownloadIconDrawable iconDrawable = new DownloadIconDrawable(context);
        downloadBtn.setImageDrawable(iconDrawable);

        // Size and LayoutParams cloned safely from anchor view
        int density = (int) context.getResources().getDisplayMetrics().density;
        int size = (int) (24 * density);
        int padding = (int) (4 * density);
        downloadBtn.setPadding(padding, padding, padding, padding);

        ViewGroup.LayoutParams anchorParams = anchor.getLayoutParams();
        ViewGroup.LayoutParams params = createSafeLayoutParams(anchorParams, size, density);
        downloadBtn.setLayoutParams(params);

        downloadBtn.setContentDescription("Download");
        downloadBtn.setClickable(true);
        downloadBtn.setFocusable(true);

        if (media != null) {
            BOUND_MEDIA.put(downloadBtn, media);
        }

        downloadBtn.setOnClickListener(v -> handleDownloadClick(v, media, itemState, container));
        downloadBtn.setOnLongClickListener(v -> handleDownloadLongClick(v, media, container));

        // Insert at the position of the bookmark view (to its immediate left)
        int index = container.indexOfChild(anchor);
        if (index >= 0) {
            container.addView(downloadBtn, index);
        } else {
            container.addView(downloadBtn);
        }

        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "Direct download button injected beside bookmark at index " + index);
    }

    private static void handleDownloadClick(View v, @Nullable Object media, @Nullable Object itemState, ViewGroup container) {
        Object targetMedia = media != null ? media : findMedia(v, container);
        if (targetMedia != null) {
            Activity activity = getActivity(v);
            boolean started = VideoDownload.save(targetMedia, itemState, activity);
            if (started) {
                Feedback.show(v.getContext(), L10n.t(v.getContext(), "Download started"), false);
            }
        } else {
            Feedback.show(v.getContext(), L10n.t(v.getContext(), "Download failed"), true);
        }
    }

    private static boolean handleDownloadLongClick(View v, @Nullable Object media, ViewGroup container) {
        Object targetMedia = media != null ? media : findMedia(v, container);
        if (targetMedia != null) {
            Activity activity = getActivity(v);
            VideoDownload.saveAll(targetMedia, activity);
            Feedback.show(v.getContext(), L10n.t(v.getContext(), "Saving all items..."), false);
            return true;
        }
        return false;
    }

    /**
     * Resolves the container ViewGroup that holds the UFI buttons.
     */
    @Nullable
    private static ViewGroup findUfiContainer(View view) {
        if (view instanceof ViewGroup && ((ViewGroup) view).getChildCount() > 1) {
            return (ViewGroup) view;
        }
        if (view.getParent() instanceof ViewGroup) {
            return (ViewGroup) view.getParent();
        }
        return null;
    }

    /**
     * Finds the Bookmark/Save view inside the container.
     */
    @Nullable
    private static View findBookmarkView(ViewGroup container, View fallback) {
        int count = container.getChildCount();
        for (int i = count - 1; i >= 0; i--) {
            View child = container.getChildAt(i);
            if (child == null || child.getTag() != null && child.getTag().equals(VIEW_TAG)) continue;
            CharSequence desc = child.getContentDescription();
            if (desc != null) {
                String d = desc.toString().toLowerCase();
                if (d.contains("save") || d.contains("salva") || d.contains("guardar") || d.contains("bookmark")) {
                    return child;
                }
            }
        }
        // Typically the bookmark is the rightmost child
        if (count > 0) {
            View last = container.getChildAt(count - 1);
            if (last != null && !(VIEW_TAG.equals(last.getTag()))) {
                return last;
            }
        }
        return fallback;
    }

    /**
     * Attempts to find the Instagram Media object by inspecting bound caches, View tags, and listeners.
     */
    @Nullable
    private static Object findMedia(View view, @Nullable ViewGroup container) {
        Object cached = BOUND_MEDIA.get(view);
        if (cached != null) return cached;

        if (container != null) {
            cached = BOUND_MEDIA.get(container);
            if (cached != null) return cached;
            for (int i = 0; i < container.getChildCount(); i++) {
                Object cMedia = BOUND_MEDIA.get(container.getChildAt(i));
                if (cMedia != null) return cMedia;
            }
        }

        // Check view tags in hierarchy
        View current = view;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            Object tag = current.getTag();
            if (isInstagramMedia(tag)) return tag;
            Object fromFields = findMediaInObject(tag);
            if (fromFields != null) return fromFields;
            if (current.getParent() instanceof View) {
                current = (View) current.getParent();
            } else {
                break;
            }
        }

        return null;
    }

    private static boolean isInstagramMedia(Object obj) {
        if (obj == null) return false;
        String name = obj.getClass().getName();
        return name.contains("feed.media.Media") || name.equals("com.instagram.feed.media.Media");
    }

    @Nullable
    private static Object findMediaInObject(Object target) {
        if (target == null) return null;
        try {
            for (Field f : target.getClass().getDeclaredFields()) {
                if (isInstagramMedia(f.getType())) {
                    f.setAccessible(true);
                    Object val = f.get(target);
                    if (val != null) return val;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Creates LayoutParams preserving the exact type of the parent/anchor to prevent ClassCastExceptions.
     */
    @NonNull
    private static ViewGroup.LayoutParams createSafeLayoutParams(@Nullable ViewGroup.LayoutParams anchorParams, int size, int density) {
        int margin = 8 * density;
        if (anchorParams != null) {
            try {
                // Try copying the specific LayoutParams class (e.g. LinearLayout$LayoutParams)
                Constructor<?> ctor = anchorParams.getClass().getConstructor(anchorParams.getClass());
                ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams) ctor.newInstance(anchorParams);
                lp.width = anchorParams.width > 0 ? anchorParams.width : size;
                lp.height = anchorParams.height > 0 ? anchorParams.height : size;
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) lp).setMargins(margin, 0, margin, 0);
                }
                return lp;
            } catch (Throwable ignored) {}

            try {
                if (anchorParams instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams mlp = new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) anchorParams);
                    mlp.width = anchorParams.width > 0 ? anchorParams.width : size;
                    mlp.height = anchorParams.height > 0 ? anchorParams.height : size;
                    mlp.setMargins(margin, 0, margin, 0);
                    return mlp;
                }
            } catch (Throwable ignored) {}
        }

        ViewGroup.MarginLayoutParams mlp = new ViewGroup.MarginLayoutParams(size, size);
        mlp.setMargins(margin, 0, margin, 0);
        return mlp;
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

    /**
     * Crisp, vector-rendered download icon matching Instagram's native line icon style.
     */
    private static final class DownloadIconDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int color;

        DownloadIconDrawable(Context context) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            boolean isDark = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            this.color = isDark ? Color.WHITE : Color.parseColor("#262626");
            paint.setColor(this.color);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;

            float w = b.width();
            float h = b.height();
            float stroke = Math.max(2.0f, w * 0.08f);
            paint.setStrokeWidth(stroke);
            paint.setColor(color);

            float cx = b.left + w * 0.5f;
            float topY = b.top + h * 0.18f;
            float arrowTipY = b.top + h * 0.58f;
            float arrowWingSize = w * 0.20f;
            float trayY = b.top + h * 0.82f;
            float trayLeft = b.left + w * 0.20f;
            float trayRight = b.left + w * 0.80f;
            float trayLipY = b.top + h * 0.68f;

            path.reset();
            // Arrow shaft
            path.moveTo(cx, topY);
            path.lineTo(cx, arrowTipY);
            // Arrow wings
            path.moveTo(cx - arrowWingSize, arrowTipY - arrowWingSize * 0.8f);
            path.lineTo(cx, arrowTipY);
            path.lineTo(cx + arrowWingSize, arrowTipY - arrowWingSize * 0.8f);
            // Tray
            path.moveTo(trayLeft, trayLipY);
            path.lineTo(trayLeft, trayY);
            path.lineTo(trayRight, trayY);
            path.lineTo(trayRight, trayLipY);

            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public void setTintList(@Nullable ColorStateList tint) {
            if (tint != null) {
                this.color = tint.getDefaultColor();
                paint.setColor(this.color);
                invalidateSelf();
            }
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private static final List<Object> RECENT_MEDIA = Collections.synchronizedList(new ArrayList<>());

    private FeedDownloadButton() {}

    /**
     * Checks if the feed download button setting is enabled.
     */
    public static boolean isEnabled() {
        return Utils.settingsReady() && Settings.FEED_DOWNLOAD_BUTTON.get();
    }

    /**
     * Records a recently encountered Instagram Media object.
     */
    public static void recordRecentMedia(@Nullable Object media) {
        if (media == null || !isInstagramMedia(media)) return;
        try {
            RECENT_MEDIA.remove(media);
            RECENT_MEDIA.add(0, media);
            if (RECENT_MEDIA.size() > 50) {
                RECENT_MEDIA.remove(RECENT_MEDIA.size() - 1);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Injected by bytecode into IgBouncyUfiButtonImageView constructors.
     */
    public static void onBouncyButtonCreated(@Nullable View bouncy) {
        if (bouncy == null || !isEnabled()) return;
        try {
            bouncy.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    v.post(() -> attach(v, null, null));
                }

                @Override
                public void onViewDetachedFromWindow(View v) {}
            });
            bouncy.post(() -> attach(bouncy, null, null));
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "bouncy created", t);
        }
    }

    /**
     * Injected by bytecode into IgBouncyUfiButtonImageView onAttachedToWindow or setImageDrawable.
     */
    public static void onBouncyButtonAttached(@Nullable View bouncy) {
        if (bouncy == null || !isEnabled()) return;
        try {
            attach(bouncy, null, null);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "bouncy attached", t);
        }
    }

    /**
     * Injected by bytecode when setOnClickListener is called on an UFI button.
     */
    public static void onBouncyButtonListenerSet(@Nullable View bouncy, @Nullable View.OnClickListener listener) {
        if (bouncy == null || !isEnabled()) return;
        try {
            Object media = extractMediaFromObject(listener);
            if (media != null) {
                BOUND_MEDIA.put(bouncy, media);
                recordRecentMedia(media);
                if (bouncy.getParent() instanceof View) {
                    BOUND_MEDIA.put((View) bouncy.getParent(), media);
                }
            }
            attach(bouncy, media, null);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "bouncy listener", t);
        }
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
     */
    public static void attach(@Nullable View anchorView, @Nullable Object media, @Nullable Object itemState) {
        if (anchorView == null || !isEnabled()) return;
        try {
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);

            if (media != null) {
                BOUND_MEDIA.put(anchorView, media);
                recordRecentMedia(media);
            }

            // Find parent ViewGroup of the UFI row
            ViewGroup container = findUfiContainer(anchorView);
            if (container == null) {
                // If not yet attached to container, try after a short delay
                anchorView.post(() -> {
                    ViewGroup c = findUfiContainer(anchorView);
                    if (c != null) {
                        injectDownloadButton(c, anchorView, media, itemState);
                    }
                });
                return;
            }

            injectDownloadButton(container, anchorView, media, itemState);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed download button attach", t);
        }
    }

    /**
     * Injects the download ImageView into the container beside the bookmark view.
     */
    private static void injectDownloadButton(@NonNull ViewGroup container, @NonNull View anchor,
                                            @Nullable Object media, @Nullable Object itemState) {
        // Find the Save/Bookmark button in the container
        View bookmarkView = findBookmarkView(container, anchor);

        // Long-press shortcut on the bookmark view as well
        if (bookmarkView != null) {
            bookmarkView.setOnLongClickListener(v -> {
                Object targetMedia = media != null ? media : findMedia(v, container);
                if (targetMedia != null) {
                    Activity activity = getActivity(v);
                    boolean saved = VideoDownload.save(targetMedia, itemState, activity);
                    if (saved) {
                        Feedback.show(v.getContext(), L10n.t(v.getContext(), "Download started"), false);
                    }
                    return true;
                }
                return false;
            });
        }

        View targetAnchor = bookmarkView != null ? bookmarkView : anchor;

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
        DownloadIconDrawable iconDrawable = new DownloadIconDrawable(context, targetAnchor);
        downloadBtn.setImageDrawable(iconDrawable);

        int density = (int) context.getResources().getDisplayMetrics().density;
        int size = (int) (40 * density);
        int padding = (int) (8 * density);
        downloadBtn.setPadding(padding, padding, padding, padding);

        ViewGroup.LayoutParams anchorParams = targetAnchor.getLayoutParams();
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
        int index = container.indexOfChild(targetAnchor);
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
        View current = view;
        ViewGroup bestContainer = null;
        for (int i = 0; i < 4 && current != null; i++) {
            if (current instanceof ViewGroup && ((ViewGroup) current).getChildCount() >= 2) {
                return (ViewGroup) current;
            }
            if (current.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) current.getParent();
                if (parent.getChildCount() >= 2) {
                    return parent;
                }
                if (bestContainer == null) {
                    bestContainer = parent;
                }
                current = parent;
            } else {
                break;
            }
        }
        return bestContainer;
    }

    /**
     * Finds the Bookmark/Save view inside the container.
     */
    @Nullable
    private static View findBookmarkView(ViewGroup container, View fallback) {
        int count = container.getChildCount();
        for (int i = count - 1; i >= 0; i--) {
            View child = container.getChildAt(i);
            if (child == null || (child.getTag() != null && VIEW_TAG.equals(child.getTag()))) continue;
            CharSequence desc = child.getContentDescription();
            if (desc != null) {
                String d = desc.toString().toLowerCase();
                if (d.contains("save") || d.contains("salva") || d.contains("guardar") || d.contains("bookmark")
                        || d.contains("speichern") || d.contains("enregistrer")) {
                    return child;
                }
            }
            if (child instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) child;
                for (int j = 0; j < vg.getChildCount(); j++) {
                    View sub = vg.getChildAt(j);
                    if (sub != null && sub.getContentDescription() != null) {
                        String sd = sub.getContentDescription().toString().toLowerCase();
                        if (sd.contains("save") || sd.contains("salva") || sd.contains("guardar") || sd.contains("bookmark")) {
                            return child;
                        }
                    }
                }
            }
        }
        // Rightmost non-download view is typically the bookmark
        if (count > 0) {
            for (int i = count - 1; i >= 0; i--) {
                View last = container.getChildAt(i);
                if (last != null && !(VIEW_TAG.equals(last.getTag()))) {
                    return last;
                }
            }
        }
        return fallback;
    }

    /**
     * Finds the Instagram Media object by inspecting bound caches, view tags, listeners and recent items.
     */
    @Nullable
    private static Object findMedia(View view, @Nullable ViewGroup container) {
        Object cached = BOUND_MEDIA.get(view);
        if (cached != null) return cached;

        if (container != null) {
            cached = BOUND_MEDIA.get(container);
            if (cached != null) return cached;
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                Object cMedia = BOUND_MEDIA.get(child);
                if (cMedia != null) return cMedia;
                // Check listener on child view
                Object fromListener = extractMediaFromViewListener(child);
                if (fromListener != null) {
                    BOUND_MEDIA.put(container, fromListener);
                    return fromListener;
                }
            }
        }

        // Check view tags and listeners in hierarchy
        View current = view;
        for (int depth = 0; depth < 6 && current != null; depth++) {
            Object tag = current.getTag();
            if (isInstagramMedia(tag)) return tag;
            Object fromFields = extractMediaFromObject(tag);
            if (fromFields != null) return fromFields;

            Object fromListener = extractMediaFromViewListener(current);
            if (fromListener != null) return fromListener;

            if (current.getParent() instanceof View) {
                current = (View) current.getParent();
            } else {
                break;
            }
        }

        // Fallback to most recent media if available
        synchronized (RECENT_MEDIA) {
            if (!RECENT_MEDIA.isEmpty()) {
                return RECENT_MEDIA.get(0);
            }
        }

        return null;
    }

    @Nullable
    private static Object extractMediaFromViewListener(View view) {
        if (view == null) return null;
        try {
            Field listenerInfoField = View.class.getDeclaredField("mListenerInfo");
            listenerInfoField.setAccessible(true);
            Object listenerInfo = listenerInfoField.get(view);
            if (listenerInfo != null) {
                Field onClickField = listenerInfo.getClass().getDeclaredField("mOnClickListener");
                onClickField.setAccessible(true);
                Object listener = onClickField.get(listenerInfo);
                Object media = extractMediaFromObject(listener);
                if (media != null) return media;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Nullable
    private static Object extractMediaFromObject(Object target) {
        if (target == null) return null;
        if (isInstagramMedia(target)) return target;
        try {
            Class<?> clazz = target.getClass();
            for (Field f : clazz.getDeclaredFields()) {
                if (isInstagramMedia(f.getType())) {
                    f.setAccessible(true);
                    Object val = f.get(target);
                    if (val != null) return val;
                }
            }
            // Check one level deeper for delegates/holders
            for (Field f : clazz.getDeclaredFields()) {
                f.setAccessible(true);
                Object sub = f.get(target);
                if (sub != null && !sub.getClass().getName().startsWith("android.") && !sub.getClass().getName().startsWith("java.")) {
                    for (Field subF : sub.getClass().getDeclaredFields()) {
                        if (isInstagramMedia(subF.getType())) {
                            subF.setAccessible(true);
                            Object val = subF.get(sub);
                            if (val != null) return val;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isInstagramMedia(Object obj) {
        if (obj == null) return false;
        String name = obj.getClass().getName();
        return name.contains("feed.media.Media") || name.equals("com.instagram.feed.media.Media");
    }

    private static boolean isInstagramMedia(Class<?> clazz) {
        if (clazz == null) return false;
        String name = clazz.getName();
        return name.contains("feed.media.Media") || name.equals("com.instagram.feed.media.Media");
    }

    /**
     * Creates LayoutParams preserving the exact type of the parent/anchor to prevent ClassCastExceptions.
     */
    @NonNull
    private static ViewGroup.LayoutParams createSafeLayoutParams(@Nullable ViewGroup.LayoutParams anchorParams, int size, int density) {
        int margin = 4 * density;
        if (anchorParams != null) {
            try {
                Constructor<?> ctor = anchorParams.getClass().getConstructor(anchorParams.getClass());
                ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams) ctor.newInstance(anchorParams);
                lp.width = size;
                lp.height = size;
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) lp).setMargins(margin, 0, margin, 0);
                }
                return lp;
            } catch (Throwable ignored) {}

            try {
                if (anchorParams instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams mlp = new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) anchorParams);
                    mlp.width = size;
                    mlp.height = size;
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

        DownloadIconDrawable(Context context, @Nullable View anchor) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            int resolvedColor = 0;
            if (anchor instanceof ImageView) {
                ColorStateList tint = ((ImageView) anchor).getImageTintList();
                if (tint != null) {
                    resolvedColor = tint.getDefaultColor();
                }
            }

            if (resolvedColor == 0) {
                boolean isDark = (context.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
                resolvedColor = isDark ? Color.WHITE : Color.parseColor("#262626");
            }

            this.color = resolvedColor;
            paint.setColor(this.color);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;

            float w = b.width();
            float h = b.height();
            float stroke = Math.max(2.2f, w * 0.085f);
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

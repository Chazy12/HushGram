/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Clear the media cache" patch.
 *
 * <p>Instagram keeps the images and videos it has shown in its cache folders, and they grow to
 * several gigabytes. While the switch is on, each time Instagram goes to the background and those
 * folders hold more than {@link #LIMIT}, HushGram deletes the files in them on a background thread,
 * the same files Android's own Clear cache button deletes. Sign-in, drafts and settings live
 * outside the cache folders, so they stay. HushGram's own folders there (a save in progress) stay
 * too, and so does any file written in the last minute, since Instagram may still be writing it.
 * The folders themselves stay, so code holding one keeps working.
 *
 * <p>The Clear now row in HushGram's settings does the same at once, whatever the size, and shows
 * what it freed.
 */
public final class MediaCache {
    /** The size the cache folders may reach before a trip to the background clears them. */
    static final long LIMIT = 500L * 1024 * 1024;

    /** A file younger than this may still be open for writing, so it stays. */
    static final long SETTLE_MILLIS = 60_000;

    /** HushGram's own folders in the cache start with this, and are never cleared. */
    static final String OWN_PREFIX = "hushgram";

    /** The step a failed clear is reported under. */
    static final String CLEAR = "clear";

    /** What's counted for each clear that found the cache over the limit. */
    static final String CLEARED = "cleared over the limit";

    private static final AtomicBoolean clearing = new AtomicBoolean();
    private static volatile boolean watching;

    private MediaCache() {
    }

    /**
     * Called once Instagram's application has started. With the patch in, HushGram hears each time
     * Instagram's screens leave the foreground and clears the cache then if it's over the limit.
     */
    public static void watch(Context context) {
        try {
            if (watching || !SettingsStatus.mediaCache()) return;
            Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            application.registerComponentCallbacks(new ComponentCallbacks2() {
                @Override
                public void onTrimMemory(int level) {
                    if (level == TRIM_MEMORY_UI_HIDDEN) onBackground(application, MediaCache::switchedOn);
                }

                @Override
                public void onConfigurationChanged(Configuration configuration) {
                }

                @Override
                public void onLowMemory() {
                }
            });
            watching = true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MEDIA_CACHE, CLEAR, failure);
        }
    }

    /** Queues a clear when the switch is on. Answers whether it queued one. */
    static boolean onBackground(Context context, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.MEDIA_CACHE);
            if (!on.getAsBoolean()) return false;
            return Utils.runOnBackgroundThread(() -> clearIfOver(context, LIMIT, System.currentTimeMillis()));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MEDIA_CACHE, CLEAR, failure);
            return false;
        }
    }

    /**
     * Clears the cache folders when they hold more than [limit]. Answers the bytes freed: none under
     * the limit, while another clear is running, or on a failure.
     */
    static long clearIfOver(Context context, long limit, long now) {
        if (!clearing.compareAndSet(false, true)) return 0;
        try {
            List<File> roots = roots(context);
            long size = 0;
            for (File root : roots) size += sizeOf(root, true);
            if (size <= limit) return 0;
            long freed = 0;
            for (File root : roots) freed += clear(root, now, true);
            HookStatus.counted(FamilyNames.MEDIA_CACHE, CLEARED);
            final long total = size;
            final long gone = freed;
            Logger.printDebug(() -> "Media cache: freed " + gone + " of " + total + " bytes");
            return freed;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MEDIA_CACHE, CLEAR, failure);
            return 0;
        } finally {
            clearing.set(false);
        }
    }

    /** Clear now: the cache folders whatever their size. Answers the bytes freed. */
    public static long clearNow(Context context) {
        if (!clearing.compareAndSet(false, true)) return 0;
        try {
            long now = System.currentTimeMillis();
            long freed = 0;
            for (File root : roots(context)) freed += clear(root, now, true);
            return freed;
        } finally {
            clearing.set(false);
        }
    }

    /** Instagram's cache folders: the one on internal storage and the one on shared storage, when there is one. */
    static List<File> roots(Context context) {
        List<File> roots = new ArrayList<>(2);
        File internal = context.getCacheDir();
        if (internal != null) roots.add(internal);
        File external = context.getExternalCacheDir();
        if (external != null && !external.equals(internal)) roots.add(external);
        return roots;
    }

    private static boolean own(File file) {
        return file.getName().toLowerCase(Locale.ROOT).startsWith(OWN_PREFIX);
    }

    private static long sizeOf(File file, boolean top) {
        if (Files.isSymbolicLink(file.toPath())) return 0;
        if (!file.isDirectory()) return file.length();
        File[] children = file.listFiles();
        if (children == null) return 0;
        long size = 0;
        for (File child : children) {
            if (top && own(child)) continue;
            size += sizeOf(child, false);
        }
        return size;
    }

    private static long clear(File file, long now, boolean top) {
        if (Files.isSymbolicLink(file.toPath())) return 0;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return 0;
            long freed = 0;
            for (File child : children) {
                if (top && own(child)) continue;
                freed += clear(child, now, false);
            }
            return freed;
        }
        if (now - file.lastModified() < SETTLE_MILLIS) return 0;
        long length = file.length();
        return file.delete() ? length : 0;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.CLEAR_MEDIA_CACHE.get();
    }
}

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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
 * <p>Instagram keeps the images and videos it has shown in two caches in its cache folder, and they
 * grow to several gigabytes. While the switch is on, each time Instagram goes to the background and
 * those two hold more than {@link #LIMIT}, HushGram deletes their files on a background thread.
 * Nothing else in the cache folder is touched: uploads, drafts being made, saved network answers,
 * HushGram's own saves and the rest stay, and so do sign-in and settings, which live elsewhere. Any
 * file written in the last minute stays too, since Instagram may still be writing it. The folders
 * themselves stay, so code holding one keeps working.
 *
 * <p>The two caches, as Instagram 450 names them:
 * <ul>
 *   <li>Images: {@code image_scoped}, the path Meta's storage registry gives the image cache
 *       ({@code X.02ix.A00}, "cache/image_scoped"), and {@code images}, that cache's older name
 *       ({@code X.05Nm.A06}).
 *   <li>Video: {@code ExoPlayerCacheDir}. The video player's cache settings put it in the cache
 *       folder ({@code X.06jh.A0N}, {@code getCacheDir()}), and {@code X.08nf.A00} names its folders
 *       videocache, videoprefetchcache and videocachemetadata. A span of video is a file ending in
 *       {@link #SPAN_SUFFIX}. The player's index of them goes only when every file in the video cache
 *       does, all of them settled, so the index never loses the spans it lists while some stay.
 * </ul>
 *
 * <p>The Clear now row in HushGram's settings does the same at once, whatever the size, and shows
 * what it freed.
 */
public final class MediaCache {
    /** The size the two caches may reach before a trip to the background clears them. */
    static final long LIMIT = 500L * 1024 * 1024;

    /** A file younger than this may still be open for writing, so it stays. */
    static final long SETTLE_MILLIS = 60_000;

    /** The image cache's folders in the cache folder (see the class comment). */
    static final List<String> IMAGE_FOLDERS = Collections.unmodifiableList(Arrays.asList("image_scoped", "images"));

    /** The video cache's folder in the cache folder (see the class comment). */
    static final String VIDEO_FOLDER = "ExoPlayerCacheDir";

    /** The end of a video span's file name. */
    static final String SPAN_SUFFIX = ".exo";

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
     * Clears the two caches when they hold more than [limit]. Answers the bytes freed: none under
     * the limit, while another clear is running, or on a failure.
     */
    static long clearIfOver(Context context, long limit, long now) {
        if (!clearing.compareAndSet(false, true)) return 0;
        try {
            File cache = context.getCacheDir();
            if (cache == null) return 0;
            long size = 0;
            for (File folder : folders(cache)) size += sizeOf(folder);
            if (size <= limit) return 0;
            long freed = clearMedia(cache, now);
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

    /** Clear now: the two caches whatever their size, by the same rules. Answers the bytes freed. */
    public static long clearNow(Context context) {
        if (!clearing.compareAndSet(false, true)) return 0;
        try {
            File cache = context.getCacheDir();
            return cache == null ? 0 : clearMedia(cache, System.currentTimeMillis());
        } finally {
            clearing.set(false);
        }
    }

    /** The image folders and the video folder in [cache]. */
    static List<File> folders(File cache) {
        List<File> folders = new ArrayList<>(IMAGE_FOLDERS.size() + 1);
        for (String name : IMAGE_FOLDERS) folders.add(new File(cache, name));
        folders.add(new File(cache, VIDEO_FOLDER));
        return folders;
    }

    /**
     * Deletes the settled images, then the video cache: all of it, index included, when every file
     * in it is settled, and otherwise only its settled spans.
     */
    private static long clearMedia(File cache, long now) {
        long freed = 0;
        for (String name : IMAGE_FOLDERS) freed += clear(new File(cache, name), now, null);
        File video = new File(cache, VIDEO_FOLDER);
        freed += clear(video, now, settled(video, now) ? null : SPAN_SUFFIX);
        return freed;
    }

    private static long sizeOf(File file) {
        if (Files.isSymbolicLink(file.toPath())) return 0;
        if (!file.isDirectory()) return file.isFile() ? file.length() : 0;
        File[] children = file.listFiles();
        if (children == null) return 0;
        long size = 0;
        for (File child : children) size += sizeOf(child);
        return size;
    }

    /** Whether no file under [file] was written in the last {@link #SETTLE_MILLIS}. */
    private static boolean settled(File file, long now) {
        if (Files.isSymbolicLink(file.toPath())) return true;
        if (!file.isDirectory()) return !file.isFile() || now - file.lastModified() >= SETTLE_MILLIS;
        File[] children = file.listFiles();
        if (children == null) return true;
        for (File child : children) if (!settled(child, now)) return false;
        return true;
    }

    /** Deletes the settled files under [file], only those whose names end with [suffix] when it's given. */
    private static long clear(File file, long now, String suffix) {
        if (Files.isSymbolicLink(file.toPath())) return 0;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return 0;
            long freed = 0;
            for (File child : children) freed += clear(child, now, suffix);
            return freed;
        }
        if (!file.isFile() || (suffix != null && !file.getName().endsWith(suffix))) return 0;
        if (now - file.lastModified() < SETTLE_MILLIS) return 0;
        long length = file.length();
        return file.delete() ? length : 0;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.CLEAR_MEDIA_CACHE.get();
    }
}

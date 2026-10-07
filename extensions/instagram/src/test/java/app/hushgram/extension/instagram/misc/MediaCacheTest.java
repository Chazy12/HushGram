/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** What Clear the media cache deletes, what it keeps, and when it leaves the cache alone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class MediaCacheTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final long NOW = 1_800_000_000_000L;
    private static final long OLD = NOW - 10 * 60_000;

    private Context context;
    private File cache;

    @Before
    public void setUp() {
        HookStatus.clear();
        context = RuntimeEnvironment.getApplication();
        cache = context.getCacheDir();
    }

    @After
    public void tearDown() {
        HookStatus.clear();
    }

    private File file(String path, long bytes, long modified) throws IOException {
        File file = new File(cache, path);
        file.getParentFile().mkdirs();
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.setLength(bytes);
        }
        assertTrue(file.setLastModified(modified));
        return file;
    }

    @Test
    public void overTheLimitItDeletesOldFilesAndKeepsTheRest() throws IOException {
        File image = file("images/a.jpg", 400, OLD);
        File video = file("ExoPlayerCacheDir/v/1.exo", 500, OLD);
        File writing = file("ExoPlayerCacheDir/v/2.exo", 300, NOW - 5_000);
        File save = file("hushgram-save/reel.mp4", 700, OLD);

        assertEquals(900, MediaCache.clearIfOver(context, 1_000, NOW));

        assertFalse(image.exists());
        assertFalse(video.exists());
        assertTrue("a file still being written stays", writing.exists());
        assertTrue("HushGram's own folder stays", save.exists());
        assertTrue("the folders stay", new File(cache, "ExoPlayerCacheDir/v").isDirectory());
        assertTrue(new File(cache, "images").isDirectory());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(MediaCache.CLEARED + " 1"));
    }

    @Test
    public void underTheLimitNothingGoes() throws IOException {
        File image = file("images/a.jpg", 400, OLD);
        file("hushgram-save/reel.mp4", 5_000, OLD);

        assertEquals("HushGram's own files don't count toward the limit", 0, MediaCache.clearIfOver(context, 1_000, NOW));

        assertTrue(image.exists());
    }

    @Test
    public void clearNowIgnoresTheLimit() throws IOException {
        File image = file("images/a.jpg", 40, System.currentTimeMillis() - 2 * MediaCache.SETTLE_MILLIS);
        long freed = MediaCache.clearNow(context);
        assertEquals(40, freed);
        assertFalse(image.exists());
    }

    @Test
    public void offOrThrowingQueuesNothing() throws IOException {
        File image = file("images/a.jpg", 400, OLD);

        assertFalse(MediaCache.onBackground(context, () -> false));
        assertFalse(MediaCache.onBackground(context, () -> {
            throw new IllegalStateException("settings went away");
        }));

        assertTrue(image.exists());
        assertFalse(HookStatus.missing(FamilyNames.MEDIA_CACHE).isEmpty());
    }

    @Test
    public void withoutThePatchItDoesntWatch() {
        MediaCache.watch(context);
        assertTrue(HookStatus.missing(FamilyNames.MEDIA_CACHE).toString(), HookStatus.missing(FamilyNames.MEDIA_CACHE).isEmpty());
    }
}

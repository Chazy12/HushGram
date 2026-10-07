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
    public void overTheLimitItDeletesOldImagesAndSpansAndKeepsTheRest() throws IOException {
        File image = file("image_scoped/0/a.jpg", 400, OLD);
        File legacy = file("images/b.jpg", 100, OLD);
        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 500, OLD);
        File writing = file("ExoPlayerCacheDir/videocache/2.0.1.v3.exo", 300, NOW - 5_000);
        File index = file("ExoPlayerCacheDir/videocache/cached_content_index.exi", 50, OLD);
        File save = file("hushgram-save/reel.mp4", 700, OLD);

        assertEquals(1_000, MediaCache.clearIfOver(context, 1_000, NOW));

        assertFalse(image.exists());
        assertFalse(legacy.exists());
        assertFalse(span.exists());
        assertTrue("a file still being written stays", writing.exists());
        assertTrue("the video index stays while a span it lists stays", index.exists());
        assertTrue("HushGram's own folder stays", save.exists());
        assertTrue("the folders stay", new File(cache, "ExoPlayerCacheDir/videocache").isDirectory());
        assertTrue(new File(cache, "image_scoped/0").isDirectory());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(MediaCache.CLEARED + " 1"));
    }

    /** Once nothing in the video cache is being written, all of it goes, its index and metadata with it. */
    @Test
    public void aSettledVideoCacheGoesWithItsIndex() throws IOException {
        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 900, OLD);
        File index = file("ExoPlayerCacheDir/videocache/cached_content_index.exi", 50, OLD);
        File prefetch = file("ExoPlayerCacheDir/videoprefetchcache/3.0.1.v3.exo", 40, OLD);
        File metadata = file("ExoPlayerCacheDir/videocachemetadata/meta", 20, OLD);

        assertEquals(1_010, MediaCache.clearIfOver(context, 1_000, NOW));

        assertFalse(span.exists());
        assertFalse(index.exists());
        assertFalse(prefetch.exists());
        assertFalse(metadata.exists());
    }

    /** Everything outside the two caches stays, however big and old, and doesn't count toward the limit. */
    @Test
    public void filesOutsideTheMediaCachesSurvive() throws IOException {
        File image = file("image_scoped/a.jpg", 400, OLD);
        File responses = file("http_responses/feed", 5_000, OLD);
        File upload = file("pending_media/upload.tmp", 5_000, OLD);
        File loose = file("cached_content_index.exi", 5_000, OLD);
        File looseSpan = file("other/1.0.1.v3.exo", 5_000, OLD);
        File external = new File(context.getExternalCacheDir(), "ExoPlayerCacheDir/videocache/1.exo");
        external.getParentFile().mkdirs();
        assertTrue(external.createNewFile() || external.exists());
        assertTrue(external.setLastModified(OLD));

        assertEquals("only the image counts, and it's under the limit", 0, MediaCache.clearIfOver(context, 1_000, NOW));
        assertTrue(image.exists());

        assertEquals(400, MediaCache.clearIfOver(context, 100, NOW));
        assertFalse(image.exists());
        for (File kept : new File[] {responses, upload, loose, looseSpan, external}) assertTrue(kept.getPath(), kept.exists());
    }

    @Test
    public void underTheLimitNothingGoes() throws IOException {
        File image = file("image_scoped/a.jpg", 400, OLD);
        file("hushgram-save/reel.mp4", 5_000, OLD);

        assertEquals("HushGram's own files don't count toward the limit", 0, MediaCache.clearIfOver(context, 1_000, NOW));

        assertTrue(image.exists());
    }

    /** Clear now ignores the limit and follows the same rules: images and videos only. */
    @Test
    public void clearNowIgnoresTheLimit() throws IOException {
        long old = System.currentTimeMillis() - 2 * MediaCache.SETTLE_MILLIS;
        File image = file("image_scoped/a.jpg", 40, old);
        File responses = file("http_responses/feed", 70, old);
        long freed = MediaCache.clearNow(context);
        assertEquals(40, freed);
        assertFalse(image.exists());
        assertTrue("a file outside the media caches stays", responses.exists());
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

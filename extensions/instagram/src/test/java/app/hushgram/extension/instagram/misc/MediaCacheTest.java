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
import app.hushgram.extension.shared.Utils;
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
        MediaCache.restartForTests();
        context = RuntimeEnvironment.getApplication();
        cache = context.getCacheDir();
    }

    @After
    public void tearDown() {
        HookStatus.clear();
        MediaCache.restartForTests();
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
    public void overTheLimitItDeletesOldImagesAndLeavesTheVideosForTheNextStart() throws IOException {
        File image = file("image_scoped/0/a.jpg", 400, OLD);
        File legacy = file("images/b.jpg", 100, OLD);
        File writing = file("images/c.jpg", 300, NOW - 5_000);
        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 500, OLD);
        File index = file("ExoPlayerCacheDir/videocache/cached_content_index.exi", 50, OLD);
        File save = file("hushgram-save/reel.mp4", 700, OLD);

        assertEquals(500, MediaCache.clearIfOver(context, 1_000, NOW));

        assertFalse(image.exists());
        assertFalse(legacy.exists());
        assertTrue("an image still being written stays", writing.exists());
        assertTrue("no video goes while Instagram runs", span.exists());
        assertTrue(index.exists());
        assertTrue("the videos go at the next start", MediaCache.videosWaiting(context));
        assertTrue("HushGram's own folder stays", save.exists());
        assertTrue("the folders stay", new File(cache, "image_scoped/0").isDirectory());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(MediaCache.CLEARED + " 1"));
    }

    /**
     * The first time Instagram names the video cache's folders in a process, before its player has
     * built the cache, the video cache a clear asked for goes: all of it, index and metadata with it.
     */
    @Test
    public void theNextStartClearsTheVideos() throws Exception {
        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 900, OLD);
        File index = file("ExoPlayerCacheDir/videocache/cached_content_index.exi", 50, OLD);
        File prefetch = file("ExoPlayerCacheDir/videoprefetchcache/3.0.1.v3.exo", 40, OLD);
        File metadata = file("ExoPlayerCacheDir/videocachemetadata/meta", 20, OLD);
        File image = file("image_scoped/a.jpg", 100, NOW - 5_000);
        File responses = file("http_responses/feed", 70, OLD);
        assertEquals(0, MediaCache.clearIfOver(context, 1_000, NOW));

        MediaCache.beforeVideoCache(cache.getPath());
        Utils.awaitBackgroundTasksForTests();

        for (File gone : new File[] {span, index, prefetch, metadata}) assertFalse(gone.getPath(), gone.exists());
        assertFalse(new File(cache, MediaCache.VIDEO_FOLDER).exists());
        assertFalse("the request is used up", MediaCache.videosWaiting(context));
        assertEquals("nothing moved aside is left", 0, leftovers().length);
        assertTrue(image.exists());
        assertTrue(responses.exists());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(MediaCache.VIDEOS_CLEARED + " 1"));
    }

    /** Once Instagram has named the folders in this process, its player may hold them, so nothing more goes. */
    @Test
    public void onlyTheFirstCallInAProcessClears() throws Exception {
        MediaCache.beforeVideoCache(cache.getPath());
        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 900, OLD);
        assertEquals(0, MediaCache.clearNow(context));
        assertTrue(MediaCache.videosWaiting(context));

        MediaCache.beforeVideoCache(cache.getPath());
        Utils.awaitBackgroundTasksForTests();

        assertTrue(span.exists());
        assertTrue("still waiting for the next start", MediaCache.videosWaiting(context));
    }

    /** Without a clear asking, a start leaves the videos, and only deletes what a start before it moved aside. */
    @Test
    public void aStartNobodyAskedForKeepsTheVideos() throws Exception {
        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 900, OLD);
        File moved = file(MediaCache.OLD_VIDEOS + "1/videocache/2.0.1.v3.exo", 900, OLD);

        MediaCache.beforeVideoCache(cache.getPath());
        Utils.awaitBackgroundTasksForTests();

        assertTrue(span.exists());
        assertFalse(moved.exists());
        assertEquals(0, leftovers().length);
    }

    /** A folder Instagram hands over that isn't there, or none at all, changes nothing and doesn't throw. */
    @Test
    public void anOddFolderChangesNothing() throws Exception {
        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 900, OLD);
        assertEquals(0, MediaCache.clearNow(context));

        MediaCache.beforeVideoCache(null);
        MediaCache.restartForTests();
        MediaCache.beforeVideoCache(new File(cache, "elsewhere").getPath());
        Utils.awaitBackgroundTasksForTests();

        assertTrue(span.exists());
        assertTrue(MediaCache.videosWaiting(context));
    }

    private File[] leftovers() {
        File[] found = cache.listFiles((parent, name) -> name.startsWith(MediaCache.OLD_VIDEOS));
        return found == null ? new File[0] : found;
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

    /** Clear now ignores the limit and follows the same rules: images now, videos at the next start. */
    @Test
    public void clearNowIgnoresTheLimit() throws IOException {
        long old = System.currentTimeMillis() - 2 * MediaCache.SETTLE_MILLIS;
        File image = file("image_scoped/a.jpg", 40, old);
        File responses = file("http_responses/feed", 70, old);
        long freed = MediaCache.clearNow(context);
        assertEquals(40, freed);
        assertFalse(image.exists());
        assertTrue("a file outside the media caches stays", responses.exists());
        assertFalse("no videos, so nothing waits for a start", MediaCache.videosWaiting(context));

        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 900, old);
        assertEquals(0, MediaCache.clearNow(context));
        assertTrue(span.exists());
        assertTrue(MediaCache.videosWaiting(context));
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

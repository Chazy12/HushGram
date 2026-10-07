/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** What the reel menu's hooks answer, and what a tap on Download does with them. */
@RunWith(RobolectricTestRunner.class)
public class ReelDownloadTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void tearDown() throws InterruptedException {
        // A save a test started ends before the next test, so none of them holds a slot.
        long deadline = System.currentTimeMillis() + 10_000;
        while (MediaSave.savesInFlight() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        MediaSave.policyForTests = null;
        Item.videos = null;
        Item.manifest = null;
        Item.pictures = null;
        HookStatus.clear();
        Settings.DOWNLOAD_REELS.save(true);
    }

    /** With the switch on, every reel gets the row, whatever Instagram and its flag say. */
    @Test
    public void withTheSwitchOnEveryReelOffersDownload() {
        assertTrue(ReelDownload.offer(false));
        assertTrue(ReelDownload.offer(true));
        assertFalse(ReelDownload.withhold(true));
        assertFalse(ReelDownload.withhold(false));
    }

    /** Off, the menu is Instagram's own. Pause turns the switch off for the whole process, as it does every switch. */
    @Test
    public void offInstagramDecides() {
        Settings.DOWNLOAD_REELS.save(false);
        assertFalse(ReelDownload.offer(false));
        assertTrue(ReelDownload.offer(true));
        assertTrue(ReelDownload.withhold(true));
        assertFalse(ReelDownload.withhold(false));
        assertFalse("a tap was taken from Instagram", ReelDownload.save(new Object(), null));
        List<Object> reduced = options(Option.PLAYBACK_CONTROLS, Option.REPORT);
        ReelDownload.addTo(reduced, Option.DOWNLOAD);
        assertEquals(options(Option.PLAYBACK_CONTROLS, Option.REPORT), reduced);
    }

    /** The patch hands Instagram's answers over as ints, since a boolean method may return one. Non-zero is yes. */
    @Test
    public void thePatchsIntEntriesReadNonZeroAsYes() {
        assertTrue(ReelDownload.offer(0));
        assertTrue(ReelDownload.offer(2));
        assertFalse(ReelDownload.withhold(1));
        Settings.DOWNLOAD_REELS.save(false);
        assertFalse("with the switch off, 0 became a yes", ReelDownload.offer(0));
        assertTrue("with the switch off, 1 lost Instagram's yes", ReelDownload.offer(1));
        assertTrue("2 is a yes too", ReelDownload.offer(2));
        assertFalse(ReelDownload.withhold(0));
        assertTrue("with the switch off, Instagram's flag lost its hold", ReelDownload.withhold(1));
        assertTrue(ReelDownload.withhold(2));
    }

    /** Instagram's option names, as the reduced reel menu's list holds them. */
    private enum Option { SHOP_SIMILAR, SAVE, UNSAVE, PLAYBACK_CONTROLS, DOWNLOAD, WHY_AM_I_SEEING_THIS, REPORT }

    private static List<Object> options(Object... options) {
        return new ArrayList<>(Arrays.asList(options));
    }

    /** The reduced menu gets Download after its save rows, which is above Playback, and only once. */
    @Test
    public void theReducedMenuGetsDownloadAboveItsRows() {
        List<Object> plain = options(Option.PLAYBACK_CONTROLS, Option.WHY_AM_I_SEEING_THIS, Option.REPORT);
        ReelDownload.addTo(plain, Option.DOWNLOAD);
        assertEquals(options(Option.DOWNLOAD, Option.PLAYBACK_CONTROLS, Option.WHY_AM_I_SEEING_THIS, Option.REPORT), plain);

        List<Object> saved = options(Option.SHOP_SIMILAR, Option.UNSAVE, Option.PLAYBACK_CONTROLS, Option.REPORT);
        ReelDownload.addTo(saved, Option.DOWNLOAD);
        assertEquals(options(Option.SHOP_SIMILAR, Option.UNSAVE, Option.DOWNLOAD, Option.PLAYBACK_CONTROLS, Option.REPORT), saved);

        List<Object> already = options(Option.DOWNLOAD, Option.REPORT);
        ReelDownload.addTo(already, Option.DOWNLOAD);
        assertEquals(options(Option.DOWNLOAD, Option.REPORT), already);

        List<Object> empty = options();
        ReelDownload.addTo(empty, Option.DOWNLOAD);
        assertEquals(options(Option.DOWNLOAD), empty);
        ReelDownload.addTo(null, Option.DOWNLOAD);
    }

    /**
     * A tap with the switch on is HushGram's, even when the reel gives nothing to save: an unpatched
     * build's bridges answer nothing, so the save can't start, and the toast says so rather than
     * Instagram's own download starting.
     */
    @Test
    public void aTapWithNothingToSaveSaysSo() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HookStatus.clear();

        assertTrue(ReelDownload.save(new Object(), activity));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Download failed", String.valueOf(ShadowToast.getTextOfLatestToast()));
        assertEquals(List.of(FamilyNames.REEL_DOWNLOAD + ": invoked 1, 0 found, 0 missing. Counted: no video versions 1, no manifest 1"),
                HookStatus.report());
    }

    /**
     * A photo the Reels viewer shows with its music has no video file and no manifest, only its
     * picture's sizes. A tap saves the picture rather than failing (#71), and the report says which
     * way it went.
     */
    @Test
    @Config(shadows = Item.class)
    public void aReelItemWithNoVideoSavesItsPicture() {
        Item.pictures = List.of(new MediaSave.Rendition(META + "1080.jpg", 1080, 1350, 0),
                new MediaSave.Rendition(META + "150.jpg", 150, 150, 0));
        refuseEveryFetch();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HookStatus.clear();

        assertTrue(ReelDownload.save(new Object(), activity));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(List.of(FamilyNames.REEL_DOWNLOAD + ": invoked 1, 0 found, 0 missing. "
                + "Counted: no video versions 1, no manifest 1, has image candidates 1, saved as photo 1"), HookStatus.report());
        assertTrue("the save didn't say it started", ShadowToast.showedToast("Saving...")
                || ShadowToast.showedToast("Saving... Cancel: Downloads in HushGram."));
    }

    /** A reel with a video saves the video, never the picture every video has as its cover. */
    @Test
    @Config(shadows = Item.class)
    public void aReelWithAVideoNeverSavesItsCover() {
        Item.videos = List.of(new MediaSave.Rendition(META + "720.mp4", 720, 1280, 0));
        Item.pictures = List.of(new MediaSave.Rendition(META + "cover.jpg", 720, 1280, 0));
        refuseEveryFetch();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HookStatus.clear();

        assertTrue(ReelDownload.save(new Object(), activity));

        assertEquals(List.of(FamilyNames.REEL_DOWNLOAD + ": invoked 1, 0 found, 0 missing. Counted: no manifest 1"),
                HookStatus.report());
    }

    /** An address on Meta's media servers, which the save takes. */
    private static final String META = "https://scontent.cdninstagram.com/v/t51.2885-15/";

    /** Every lookup answers a private address, so a started save ends at once and nothing goes out. */
    private static void refuseEveryFetch() {
        MediaSave.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] {InetAddress.getByName("10.9.8.7")});
    }

    /** A reel item as the bridges read it: its video files, its manifest and its picture's sizes. */
    @Implements(value = InstagramMedia.class, isInAndroidSdk = false)
    public static class Item {
        static List<MediaSave.Rendition> videos;
        static String manifest;
        static List<MediaSave.Rendition> pictures;

        @Implementation protected static List<?> videoVersions(Object media) { return videos; }
        @Implementation protected static String dashManifest(Object media) { return manifest; }
        @Implementation protected static String versionUrl(Object version) { return ((MediaSave.Rendition) version).url; }
        @Implementation protected static Integer versionWidth(Object version) { return ((MediaSave.Rendition) version).width; }
        @Implementation protected static Integer versionHeight(Object version) { return ((MediaSave.Rendition) version).height; }
        @Implementation protected static Object imageVersions(Object media) { return pictures == null ? null : media; }
        @Implementation protected static List<?> imageCandidates(Object versions) { return pictures; }
        @Implementation protected static String candidateUrl(Object candidate) { return ((MediaSave.Rendition) candidate).url; }
        @Implementation protected static int candidateWidth(Object candidate) { return ((MediaSave.Rendition) candidate).width; }
        @Implementation protected static int candidateHeight(Object candidate) { return ((MediaSave.Rendition) candidate).height; }
    }

    /** Without the bridges written, a reel has no files and no details, and neither read throws. */
    @Test
    public void anUnpatchedReelHasNothing() {
        Object reel = new Object();
        assertTrue(ReelDownload.renditions(reel).isEmpty());
        PostDetails details = ReelDownload.details(reel);
        assertFalse(details.hasVideoId());
        assertFalse(details.hasOwner());
        assertNull(details.posted);
    }
}

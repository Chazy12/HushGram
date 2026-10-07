/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Save profile picture's row: what it reads when the menu opens, and what a tap saves. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ProfilePictureTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private static final String FULL = "https://scontent.cdninstagram.com/v/t51.2885-19/full_n.jpg?stp=dst-jpg_s1080x1080";
    private static final String SHOWN = "https://scontent.cdninstagram.com/v/t51.2885-19/full_n.jpg?stp=dst-jpg_s150x150";
    private final Object sheet = new Object();
    private final Object user = new Object();
    private final FakeNative reads = new FakeNative();
    private final List<List<MediaSave.Rendition>> saved = new ArrayList<>();
    private final List<PostDetails> named = new ArrayList<>();
    private boolean saveStarts = true;
    private final ProfilePicture.Save save = (context, sizes, details) -> {
        saved.add(sizes);
        named.add(details);
        return saveStarts;
    };
    private Context context;

    @Before public void enable() {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 36;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SAVE_PROFILE_PICTURES.save(true);
        HookStatus.clear();
        ShadowToast.reset();
    }

    @After public void restore() {
        Settings.SAVE_PROFILE_PICTURES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private List<String> counted() {
        return HookStatus.report();
    }

    @Test public void theSwitchStartsOff() {
        Settings.SAVE_PROFILE_PICTURES.resetToDefault();
        assertFalse(Settings.SAVE_PROFILE_PICTURES.get());
    }

    @Test public void offTheMenuIsInstagramsAndNothingIsRead() {
        Settings.SAVE_PROFILE_PICTURES.save(false);
        ProfilePicture.offer(sheet, user, context, reads, save);
        assertNull(reads.row);
        assertEquals("nothing of the account was read", 0, reads.calls);
    }

    @Test public void pausedOrUnreadyTheMenuIsInstagrams() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ProfilePicture.offer(sheet, user, context, reads, save);
        assertNull(reads.row);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> ProfilePicture.offer(sheet, user, context, reads, save));
        assertNull(reads.row);
        assertEquals(0, reads.calls);
    }

    @Test public void theRowSavesTheSizesReadWhenTheMenuOpened() {
        ProfilePicture.offer(sheet, user, context, reads, save);
        assertSame(sheet, reads.sheet);
        assertSame(context, reads.context);
        assertEquals("Save profile picture", reads.label);
        assertTrue("showing the menu saves nothing", saved.isEmpty());

        // What the account holds later doesn't change what the row saves.
        reads.fullUrl = "https://scontent.cdninstagram.com/later.jpg";
        reads.row.onClick(null);
        assertEquals(1, saved.size());
        List<MediaSave.Rendition> sizes = saved.get(0);
        assertEquals(2, sizes.size());
        assertEquals(FULL, sizes.get(0).url);
        assertEquals(1080, sizes.get(0).width);
        assertEquals(1080, sizes.get(0).height);
        assertEquals(SHOWN, sizes.get(1).url);
        assertThrows(UnsupportedOperationException.class, () -> sizes.add(MediaSave.Rendition.of(FULL)));
        assertEquals("someone", named.get(0).owner);
        assertFalse(named.get(0).hasPosted());
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 1, 0 found, 0 missing. Counted: full size picture 1"), counted());
    }

    @Test public void anAccountWithoutTheFullSizeSavesTheShownOne() {
        reads.full = null;
        ProfilePicture.offer(sheet, user, context, reads, save);
        reads.row.onClick(new View(context));
        assertEquals(1, saved.get(0).size());
        assertEquals(SHOWN, saved.get(0).get(0).url);
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 1, 0 found, 0 missing. Counted: shown size only 1"), counted());
    }

    /** The same address twice is one size. */
    @Test public void theSameAddressIsKeptOnce() {
        reads.shownUrl = FULL;
        ProfilePicture.offer(sheet, user, context, reads, save);
        reads.row.onClick(null);
        assertEquals(1, saved.get(0).size());
    }

    @Test public void anAccountWithNoPictureGetsNoRow() {
        reads.full = null;
        reads.shown = null;
        ProfilePicture.offer(sheet, user, context, reads, save);
        assertNull(reads.row);
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 1, 0 found, 0 missing. Counted: no profile picture 1"), counted());
    }

    /** Only Meta's media servers count: a picture anywhere else is never fetched. */
    @Test public void anAddressOffMetasServersIsLeftOut() {
        reads.fullUrl = "https://example.com/full.jpg";
        reads.shownUrl = "http://scontent.cdninstagram.com/shown.jpg";
        ProfilePicture.offer(sheet, user, context, reads, save);
        assertNull(reads.row);
    }

    @Test public void aRowThatDidntGoInIsCounted() {
        reads.adds = false;
        ProfilePicture.offer(sheet, user, context, reads, save);
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 1, 0 found, 0 missing. Counted: full size picture 1, row not added 1"), counted());
    }

    @Test public void aTapAfterTheSwitchWentOffSavesNothing() {
        ProfilePicture.offer(sheet, user, context, reads, save);
        Settings.SAVE_PROFILE_PICTURES.save(false);
        reads.row.onClick(null);
        assertTrue(saved.isEmpty());
    }

    @Test public void aSaveThatCantStartSaysSo() {
        saveStarts = false;
        ProfilePicture.offer(sheet, user, context, reads, save);
        reads.row.onClick(null);
        ShadowLooper.idleMainLooper();
        assertEquals("Download failed", ShadowToast.getTextOfLatestToast());
    }

    /** A read that throws leaves Instagram's menu as it was, and the report says where. */
    @Test public void aReadThatThrowsLeavesTheMenuAlone() {
        reads.broken = true;
        ProfilePicture.offer(sheet, user, context, reads, save);
        assertNull(reads.row);
        assertTrue(String.valueOf(counted()), counted().get(0).contains("profile menu"));
    }

    @Test public void nothingToWorkWithAddsNothing() {
        ProfilePicture.offer(null, user, context, reads, save);
        ProfilePicture.offer(sheet, null, context, reads, save);
        ProfilePicture.offer(sheet, user, null, reads, save);
        assertNull(reads.row);
        assertEquals(0, reads.calls);
    }

    /** As built, with no patch, the row's adder adds nothing and every read answers nothing. */
    @Test public void unpatchedTheMenuIsInstagrams() {
        ProfilePicture.offer(sheet, user, context);
        assertFalse(ProfilePicture.addRow(sheet, context, view -> { }, "label"));
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                + ": invoked 1, 0 found, 0 missing. Counted: no profile picture 1"), counted());
    }

    private static final class FakeNative implements ProfilePicture.Native {
        final Object fullInfo = new Object(), shownImage = new Object();
        Object full = fullInfo, shown = shownImage;
        String fullUrl = FULL, shownUrl = SHOWN;
        boolean adds = true, broken;
        int calls;
        Object sheet;
        Context context;
        View.OnClickListener row;
        String label;

        @Override public boolean addRow(Object sheet, Context context, View.OnClickListener listener, String label) {
            this.sheet = sheet;
            this.context = context;
            this.label = label;
            if (adds) row = listener;
            return adds;
        }
        @Override public Object fullSize(Object user) {
            calls++;
            if (broken) throw new ClassCastException("not an account");
            return full;
        }
        @Override public String fullSizeUrl(Object info) { calls++; return info == fullInfo ? fullUrl : null; }
        @Override public int fullSizeWidth(Object info) { calls++; return 1080; }
        @Override public int fullSizeHeight(Object info) { calls++; return 1080; }
        @Override public Object shown(Object user) { calls++; return shown; }
        @Override public String shownUrl(Object image) { calls++; return image == shownImage ? shownUrl : null; }
        @Override public int shownWidth(Object image) { calls++; return 150; }
        @Override public int shownHeight(Object image) { calls++; return 150; }
        @Override public String username(Object user) { calls++; return "someone"; }
    }
}

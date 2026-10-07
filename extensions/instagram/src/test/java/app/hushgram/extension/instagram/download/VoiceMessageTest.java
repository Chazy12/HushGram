/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.*;
import android.content.Context;
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

/** Download voice messages: when a voice message's menu gets Save, and what Save then saves. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class VoiceMessageTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private static final String RECORDING = "https://cdn.fbsbx.com/v/t59.3654-21/audioclip-1.mp4?_nc_cat=1";
    private final Object message = new Object();
    private final FakeNative reads = new FakeNative();
    private final List<String> saved = new ArrayList<>();
    private boolean saveStarts = true;
    private final VoiceMessage.Save save = (context, url, details) -> {
        saved.add(url);
        return saveStarts;
    };
    private Context context;

    @Before public void enable() {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 36;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.DOWNLOAD_VOICE_MESSAGES.save(true);
        HookStatus.clear();
        ShadowToast.reset();
    }

    @After public void restore() {
        Settings.DOWNLOAD_VOICE_MESSAGES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test public void theSwitchStartsOff() {
        Settings.DOWNLOAD_VOICE_MESSAGES.resetToDefault();
        assertFalse(Settings.DOWNLOAD_VOICE_MESSAGES.get());
    }

    @Test public void aVoiceMessageGetsSave() {
        assertTrue(VoiceMessage.offer(false, message, reads));
        assertEquals(Collections.singletonList(FamilyNames.VOICE_MESSAGE
                + ": invoked 1, 0 found, 0 missing. Counted: Save offered 1"), HookStatus.report());
    }

    /** Instagram's yes stands whatever the switch says, and nothing of the message is read for it. */
    @Test public void instagramsYesStands() {
        Settings.DOWNLOAD_VOICE_MESSAGES.save(false);
        assertTrue(VoiceMessage.offer(true, message, reads));
        assertTrue(VoiceMessage.offer(1, message));
        assertEquals(0, reads.calls);
    }

    @Test public void offTheMenuIsInstagrams() {
        Settings.DOWNLOAD_VOICE_MESSAGES.save(false);
        assertFalse(VoiceMessage.offer(false, message, reads));
        assertEquals("nothing of the message was read", 0, reads.calls);
    }

    @Test public void pausedOrUnreadyTheMenuIsInstagrams() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(VoiceMessage.offer(false, message, reads));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse(VoiceMessage.offer(false, message, reads)));
        assertEquals(0, reads.calls);
    }

    /** A photo, a text or anything else that isn't a voice message keeps Instagram's no, uncounted. */
    @Test public void anythingElseKeepsInstagramsNo() {
        reads.address = null;
        assertFalse(VoiceMessage.offer(false, message, reads));
        assertFalse(VoiceMessage.offer(false, null, reads));
        assertTrue(HookStatus.report().isEmpty());
    }

    @Test public void oneSentToBePlayedOnceKeepsInstagramsNo() {
        for (String mode : new String[] {"once", "replayable", "something new"}) {
            reads.mode = mode;
            assertFalse(mode, VoiceMessage.offer(false, message, reads));
        }
        assertEquals(Collections.singletonList(FamilyNames.VOICE_MESSAGE
                + ": invoked 3, 0 found, 0 missing. Counted: sent to be played once 3"), HookStatus.report());
    }

    @Test public void aPermanentOrUnmarkedOneGetsSave() {
        for (String mode : new String[] {"permanent", "", null}) {
            reads.mode = mode;
            assertTrue(String.valueOf(mode), VoiceMessage.offer(false, message, reads));
        }
    }

    /** Only Meta's media servers count: a recording anywhere else, or a file on the phone, is never offered. */
    @Test public void aRecordingOffMetasServersKeepsInstagramsNo() {
        for (String address : new String[] {"https://example.com/voice.mp4", "/data/user/0/com.instagram.android/cache/voice.m4a"}) {
            reads.address = address;
            assertFalse(address, VoiceMessage.offer(false, message, reads));
        }
        assertEquals(Collections.singletonList(FamilyNames.VOICE_MESSAGE
                + ": invoked 2, 0 found, 0 missing. Counted: recording not on Meta's servers 2"), HookStatus.report());
    }

    @Test public void aReadThatThrowsKeepsInstagramsNo() {
        reads.broken = true;
        assertFalse(VoiceMessage.offer(false, message, reads));
        assertTrue(String.valueOf(HookStatus.report()), HookStatus.report().get(0).contains("voice message menu"));
    }

    @Test public void saveTakesAVoiceMessageAndSavesItsRecording() {
        assertTrue(VoiceMessage.save(context, message, reads, save));
        assertEquals(Collections.singletonList(RECORDING), saved);
    }

    /** Anything that isn't a voice message goes on to Instagram's own save. */
    @Test public void saveLeavesEverythingElseToInstagram() {
        reads.address = null;
        assertFalse(VoiceMessage.save(context, message, reads, save));
        assertFalse(VoiceMessage.save(context, null, reads, save));
        reads.address = RECORDING;
        reads.mode = "once";
        assertFalse(VoiceMessage.save(context, message, reads, save));
        Settings.DOWNLOAD_VOICE_MESSAGES.save(false);
        reads.mode = null;
        assertFalse(VoiceMessage.save(context, message, reads, save));
        assertTrue(saved.isEmpty());
    }

    @Test public void aSaveThatCantStartSaysSo() {
        saveStarts = false;
        assertTrue("the message is still taken, so Instagram's save doesn't fail on it too",
                VoiceMessage.save(context, message, reads, save));
        ShadowLooper.idleMainLooper();
        assertEquals("Download failed", ShadowToast.getTextOfLatestToast());
    }

    @Test public void aReadThatThrowsAtSaveLeavesItToInstagram() {
        reads.broken = true;
        assertFalse(VoiceMessage.save(context, message, reads, save));
        assertTrue(saved.isEmpty());
    }

    /** As built, with no patch, every read answers nothing, so no menu changes and every save is Instagram's. */
    @Test public void unpatchedEverythingIsInstagrams() {
        assertFalse(VoiceMessage.offer(0, message));
        assertFalse(VoiceMessage.save(context, message));
        assertNull(VoiceMessage.audio(message));
        assertNull(VoiceMessage.viewMode(message));
        assertTrue(HookStatus.report().isEmpty());
    }

    private static final class FakeNative implements VoiceMessage.Native {
        String address = RECORDING;
        String mode = "permanent";
        boolean broken;
        int calls;

        @Override public String audio(Object message) {
            calls++;
            if (broken) throw new ClassCastException("not a message");
            return address;
        }

        @Override public String viewMode(Object message) {
            calls++;
            return mode;
        }
    }
}

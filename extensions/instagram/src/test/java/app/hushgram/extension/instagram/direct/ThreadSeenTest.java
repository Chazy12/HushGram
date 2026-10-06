/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * When the chat seen receipt is held back, and when Instagram sends it. Runtime decisions only: the
 * other person not seeing Seen needs a check with two accounts on a phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ThreadSeenTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.READ_WITHOUT_SEEN_RECEIPT.resetToDefault();
        Settings.VIEW_DM_MEDIA_ANONYMOUSLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnTheReceiptIsHeld() {
        assertTrue(ThreadSeen.hold());
        assertTrue(HookStatus.missing(FamilyNames.THREAD_SEEN).toString(),
                HookStatus.missing(FamilyNames.THREAD_SEEN).isEmpty());
    }

    @Test
    public void offToStartAndOffSendTheReceipt() {
        Settings.READ_WITHOUT_SEEN_RECEIPT.resetToDefault();
        assertFalse(Settings.READ_WITHOUT_SEEN_RECEIPT.defaultValue);
        assertFalse(ThreadSeen.hold());
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(false);
        assertFalse(ThreadSeen.hold());
    }

    /** The view-once switch is a separate choice in both directions. */
    @Test
    public void viewOnceMediaIsAnIndependentChoice() {
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(false);
        Settings.VIEW_DM_MEDIA_ANONYMOUSLY.save(true);
        assertFalse(ThreadSeen.hold());
        Settings.VIEW_DM_MEDIA_ANONYMOUSLY.save(false);
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);
        assertTrue(ThreadSeen.hold());
        assertFalse(VisualSeen.hold());
    }

    @Test
    public void pausedAndUnreadySendTheReceipt() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ThreadSeen.hold());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(ThreadSeen.hold()));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse(ThreadSeen.hold()));

        assertTrue(ThreadSeen.hold());
    }

    @Test
    public void aThrowingSwitchSendsTheReceiptAndIsReported() {
        assertFalse(ThreadSeen.hold(THROWS));

        String missing = HookStatus.missing(FamilyNames.THREAD_SEEN).toString();
        assertTrue(missing, missing.contains("'" + ThreadSeen.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}

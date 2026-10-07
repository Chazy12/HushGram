/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide the home feed" patch.
 *
 * <p>The patch passes every item Instagram's home feed parse helper reads through {@link #filter},
 * beside the other feed filters. While the switch is on, each item comes back as null, whatever its
 * kind, and every caller of that helper skips a null item, so Home's pages arrive empty. The stories
 * row at the top of Home comes from a request of its own and stays. Profiles, Explore and Reels
 * don't go through that helper.
 *
 * <p>An empty Home would draw its loading row for good, since nothing asks for the next page of an
 * empty feed. {@link FeedSuggestions#feedEnded} asks {@link #emptied} and says there's no next page,
 * so Instagram draws its own empty feed card there.
 */
public final class HomeFeed {
    /** The counted label for an item taken out, in the diagnostics report. */
    static final String TAKEN_OUT = "item taken out";

    /** The step a failed switch read in {@link #filter} is reported under. */
    static final String SWITCH = "feed item";

    /** Set once {@link #filter} has taken an item out of Home in this run. Tests clear it. */
    static volatile boolean tookOut;

    private HomeFeed() {
    }

    /**
     * Injected at the return of Instagram's feed item parse helper. Answers null for every item while
     * the switch is on, and [item] itself otherwise, or when anything goes wrong. Never throws.
     */
    public static Object filter(Object item) {
        return filter(item, HomeFeed::switchedOn);
    }

    static Object filter(Object item, BooleanSupplier on) {
        if (item == null) return null;
        try {
            HookStatus.invoked(FamilyNames.HOME_FEED);
            if (!on.getAsBoolean()) return item;
            HookStatus.counted(FamilyNames.HOME_FEED, TAKEN_OUT);
            if (!tookOut) {
                tookOut = true;
                Logger.printDebug(() -> "Home feed: taking the posts out of Home");
            }
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HOME_FEED, SWITCH, failure);
            return item;
        }
    }

    /**
     * Whether {@link #filter} has taken items out in this run and the switch is still on, so Home's
     * empty feed has nothing more coming. False when anything goes wrong. Never throws.
     */
    static boolean emptied() {
        if (!tookOut) return false;
        try {
            return switchedOn();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HOME_FEED, "empty feed", failure);
            return false;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_HOME_FEED.get();
    }
}

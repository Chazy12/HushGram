/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import app.hushgram.extension.instagram.feed.FeedItemKinds;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide Reels in the feed" patch.
 *
 * <p>Each item of Instagram's home feed carries its kind as an enum: a post (MEDIA), an ad, a row
 * of suggested reels (CLIPS_NETEGO) and so on. The patch passes every item Instagram's parse helper
 * reads through {@link #filter}, and an item of one of the {@link #REEL_UNITS} kinds comes back as
 * null, which every caller of that helper skips. A reel someone you follow posts is a MEDIA item and
 * stays.
 *
 * <p>{@link FeedItemKinds} reads the kind, and the patch checks at patch time that only one of the
 * item's enum types names any of these kinds.
 */
public final class FeedReels {
    /** The feed item kinds this patch hides, by the constant names Instagram gives them. */
    static final Set<String> REEL_UNITS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "CLIPS_NETEGO", "IMMERSIVE_SEGUE_ITEM", "VIBES_IN_FEED_UNIT", "HATCH_IMMERSIVE_IN_FEED_UNIT")));

    /** The diagnostic counter route: every feed item parsed, and the reels units taken out. */
    static final String ROUTE = "Feed reels";

    private FeedReels() {
    }

    /**
     * Injected at the return of Instagram's feed item parse helper. Answers null for a row of
     * suggested reels and the other reels units while the switch is on, and [item] itself
     * otherwise, or when anything goes wrong. Never throws.
     */
    public static Object filter(Object item) {
        if (item == null) return null;
        try {
            HookStatus.invoked(FamilyNames.FEED_REELS);
            String kind = reelUnitOf(item);
            if (kind == null) {
                if (Utils.settingsReady() && Settings.HIDE_FEED_REELS.get() && isReelPost(item)) {
                    FeedFilterCounters.removed(ROUTE, 1, "POST_REEL");
                    Logger.printDebug(() -> "Feed reels: took out a reel post");
                    return null;
                }
                return item;
            }
            FeedFilterCounters.sawKind(ROUTE, kind);
            if (!Utils.settingsReady() || !Settings.HIDE_FEED_REELS.get()) return item;
            FeedFilterCounters.removed(ROUTE, 1, kind);
            Logger.printDebug(() -> "Feed reels: took out a " + kind + " unit");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_REELS, "feed item", failure);
            return item;
        }
    }

    /** The name of [item]'s kind when it's one of {@link #REEL_UNITS}, or null. */
    static String reelUnitOf(Object item) throws IllegalAccessException {
        return FeedItemKinds.kindIn(item, REEL_UNITS, FamilyNames.FEED_REELS);
    }

    /** Checks whether an item's media is a Reel video. */
    static boolean isReelPost(Object item) {
        if (item == null) return false;
        try {
            for (java.lang.reflect.Field field : item.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object val = field.get(item);
                if (val != null && val.getClass().getName().contains("Media")) {
                    if (app.hushgram.extension.instagram.download.InstagramMedia.clipsMetadata(val) != null) {
                        return true;
                    }
                    Integer origType = app.hushgram.extension.instagram.download.InstagramMedia.originalMediaType(val);
                    if (origType != null && origType == 2) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }
}

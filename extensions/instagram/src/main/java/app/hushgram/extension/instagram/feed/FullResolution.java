/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Full resolution photos" patch.
 *
 * <p>Instagram's server sends every photo in several sizes, and one method picks the size a photo
 * in the feed loads at: the one closest to the screen's width, never wider than 1080 pixels. A
 * feed post, a carousel page and a post opened from a profile grid all get their address there.
 * The patch hands {@link #photo} the post and the size Instagram picked, right after the pick.
 * While the switch is on it answers the largest size the server sent of the same picture, and
 * Instagram loads, decodes and caches that one its own way. The view's size comes from the post's
 * own shape, not from the size loaded, so nothing on screen moves.
 *
 * <p>Instagram's pick stays while the switch is off, HushGram is paused, the settings aren't
 * ready or anything throws, and when the pick isn't one of the post's own sizes or no larger size
 * has its shape. A size over {@link #MAX_SIDE} pixels on its longer side is never picked, so one
 * photo can't take too much memory.
 */
public final class FullResolution {
    /** The longer side, in pixels, of the largest size this picks. Instagram's own largest are 1440 wide. */
    static final int MAX_SIDE = 2048;

    /** The step a failed read is reported under. */
    static final String STEP = "photo size";

    /** How a size is read. Instagram's sizes are read through the bridges the patch writes. */
    interface Sizes {
        @Nullable
        String url(Object size);

        int width(Object size);

        int height(Object size);
    }

    private static final Sizes INSTAGRAM = new Sizes() {
        @Override
        public String url(Object size) {
            return InstagramMedia.candidateUrl(size);
        }

        @Override
        public int width(Object size) {
            return InstagramMedia.candidateWidth(size);
        }

        @Override
        public int height(Object size) {
            return InstagramMedia.candidateHeight(size);
        }
    };

    private static volatile boolean logged;

    private FullResolution() {
    }

    /**
     * Injected right after Instagram picks the size a feed photo loads at, with the post and that
     * size. Answers the largest size of the same picture while the switch is on, and Instagram's
     * pick otherwise. What it answers is always the pick itself or one of the post's sizes of the
     * pick's own class. Never throws.
     */
    public static Object photo(Object media, Object chosen) {
        return photo(media, chosen, FullResolution::switchedOn, FullResolution::sizesOf, INSTAGRAM);
    }

    /** The sizes the server sent for [media]'s picture, or null when there are none to read. */
    @Nullable
    private static List<?> sizesOf(Object media) {
        Object versions = InstagramMedia.imageVersions(media);
        return versions == null ? null : InstagramMedia.imageCandidates(versions);
    }

    /** What the post's sizes are, read from it. */
    interface Candidates {
        @Nullable
        List<?> of(Object media);
    }

    static Object photo(Object media, Object chosen, BooleanSupplier on, Candidates candidates, Sizes sizes) {
        try {
            HookStatus.invoked(FamilyNames.FULL_RESOLUTION);
            if (media == null || chosen == null || !on.getAsBoolean()) return chosen;
            Object largest = largest(candidates.of(media), chosen, sizes);
            if (largest != chosen && !logged) {
                logged = true;
                Logger.printDebug(() -> "Full resolution photos: loaded " + sizes.width(largest) + "x"
                        + sizes.height(largest) + " in place of " + sizes.width(chosen) + "x" + sizes.height(chosen));
            }
            return largest;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_RESOLUTION, STEP, failure);
            return chosen;
        }
    }

    /**
     * The largest of [candidates] with [chosen]'s shape and class, an address and no side over
     * {@link #MAX_SIDE}, or [chosen] itself when it isn't one of them by address, its size can't be
     * read or none is larger.
     */
    static Object largest(@Nullable List<?> candidates, Object chosen, Sizes sizes) {
        if (candidates == null || candidates.isEmpty()) return chosen;
        String url = sizes.url(chosen);
        int width = sizes.width(chosen);
        int height = sizes.height(chosen);
        if (url == null || width <= 0 || height <= 0) return chosen;
        boolean listed = false;
        Object best = chosen;
        long bestPixels = (long) width * height;
        for (Object candidate : candidates) {
            if (candidate == null) continue;
            String candidateUrl = sizes.url(candidate);
            if (candidateUrl == null) continue;
            if (url.equals(candidateUrl)) listed = true;
            if (candidate.getClass() != chosen.getClass()) continue;
            int candidateWidth = sizes.width(candidate);
            int candidateHeight = sizes.height(candidate);
            if (candidateWidth <= 0 || candidateHeight <= 0 || Math.max(candidateWidth, candidateHeight) > MAX_SIDE
                    || !sameShape(candidateWidth, candidateHeight, width, height)) continue;
            long pixels = (long) candidateWidth * candidateHeight;
            if (pixels > bestPixels) {
                best = candidate;
                bestPixels = pixels;
            }
        }
        return listed ? best : chosen;
    }

    /**
     * Whether a [width] by [height] size has the shape of an [otherWidth] by [otherHeight] one,
     * within 2 percent, which the server's rounding of each size stays inside. A square crop of a
     * tall photo doesn't.
     */
    static boolean sameShape(int width, int height, int otherWidth, int otherHeight) {
        long one = (long) width * otherHeight;
        long other = (long) otherWidth * height;
        return Math.abs(one - other) * 50 <= Math.max(one, other);
    }

    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.FULL_RESOLUTION_PHOTOS.get();
    }
}

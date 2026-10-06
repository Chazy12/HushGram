/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which size a feed photo loads at, and that Instagram's pick stays whenever the hook can't decide. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FullResolutionTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    /** A size as the server lists it, standing in for Instagram's. */
    private static class Size {
        final String url;
        final int width;
        final int height;

        Size(String url, int width, int height) {
            this.url = url;
            this.width = width;
            this.height = height;
        }
    }

    /** A size of another class than the pick, which the patch's cast wouldn't take. */
    private static final class OtherSize extends Size {
        OtherSize(String url, int width, int height) {
            super(url, width, height);
        }
    }

    private static final FullResolution.Sizes SIZES = new FullResolution.Sizes() {
        @Override
        public String url(Object size) {
            return ((Size) size).url;
        }

        @Override
        public int width(Object size) {
            return ((Size) size).width;
        }

        @Override
        public int height(Object size) {
            return ((Size) size).height;
        }
    };

    private final Object post = new Object();
    private final Size picked = new Size("p1080", 1080, 1350);
    private final Size large = new Size("p1440", 1440, 1800);
    private final Size small = new Size("p750", 750, 938);
    private final Size square = new Size("s1440", 1440, 1440);

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.FULL_RESOLUTION_PHOTOS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.FULL_RESOLUTION_PHOTOS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private Object photo(BooleanSupplier on, Object... sizes) {
        List<Object> listed = Arrays.asList(sizes);
        return FullResolution.photo(post, picked, on, media -> listed, SIZES);
    }

    /** On, the largest size of the post's own shape loads in place of Instagram's pick, and the hook says it ran. */
    @Test
    public void withTheSwitchOnTheLargestSizeOfTheSameShapeLoads() {
        assertSame(large, photo(ON, small, picked, large));
        assertSame(large, photo(ON, large, picked, small));
        assertTrue(String.join("\n", HookStatus.report()), HookStatus.report().toString().contains(FamilyNames.FULL_RESOLUTION));
    }

    /** With nothing larger listed, the pick stays: it already is the largest. */
    @Test
    public void aPickThatIsAlreadyTheLargestStays() {
        assertSame(picked, photo(ON, small, picked));
        assertSame(picked, photo(ON, picked));
    }

    /** A square crop of a tall photo is never picked, however many pixels it has, and nor is a wider shape. */
    @Test
    public void aCropOrAnotherShapeIsNeverPicked() {
        assertSame(picked, photo(ON, picked, square));
        assertSame(picked, photo(ON, picked, new Size("w2048", 2048, 1350)));
        assertSame(large, photo(ON, picked, square, large));
    }

    /** The server's rounding of a size keeps it the same shape, within 2 percent and no more. */
    @Test
    public void aSizeRoundedByAPixelIsStillTheSameShape() {
        Size rounded = new Size("p1440-rounded", 1440, 1799);
        assertSame(rounded, photo(ON, picked, rounded));
        assertTrue(FullResolution.sameShape(1080, 1349, 1440, 1800));
        assertTrue(FullResolution.sameShape(1440, 1800, 1080, 1350));
        assertFalse(FullResolution.sameShape(1440, 1440, 1080, 1350));
        assertFalse(FullResolution.sameShape(1440, 1860, 1080, 1350));
    }

    /** No size with a side over 2048 pixels loads, so one photo can't take too much memory. */
    @Test
    public void nothingOverTheLargestSideLoads() {
        Size huge = new Size("p2160", 2160, 2700);
        assertSame(large, photo(ON, picked, huge, large));
        assertSame(picked, photo(ON, picked, huge));
        Size edge = new Size("p2048", 1638, 2048);
        assertSame(edge, photo(ON, picked, large, edge));
        assertEquals(2048, FullResolution.MAX_SIDE);
    }

    /**
     * A pick that isn't one of the post's own sizes by address stays, since then it came from
     * somewhere else, a cover or a cached size, and the sizes may be another picture's.
     */
    @Test
    public void aPickThatIsntOneOfThePostsSizesStays() {
        assertSame(picked, photo(ON, small, large));
        assertSame(picked, photo(ON, new Size("p1080-other", 1080, 1350), large));
    }

    /** A size of another class than the pick is passed over, so what's answered always fits the pick's place. */
    @Test
    public void aSizeOfAnotherClassIsPassedOver() {
        assertSame(picked, photo(ON, picked, new OtherSize("o1440", 1440, 1800)));
        assertSame(large, photo(ON, picked, new OtherSize("o1440", 1440, 1800), large));
    }

    /**
     * Sizes that can't be read leave the pick: none listed, an empty list, or a pick with no address
     * or no size. A listed size without an address or a size is passed over.
     */
    @Test
    public void unreadableSizesLeaveThePick() {
        assertSame(picked, FullResolution.photo(post, picked, ON, media -> null, SIZES));
        assertSame(picked, FullResolution.photo(post, picked, ON, media -> Collections.emptyList(), SIZES));
        assertSame(large, photo(ON, null, picked, large));
        assertSame(picked, photo(ON, picked, new Size("p1440-flat", 1440, 0)));
        assertSame(picked, photo(ON, picked, new Size(null, 1440, 1800)));

        Size noAddress = new Size(null, 1080, 1350);
        assertSame(noAddress, FullResolution.photo(post, noAddress, ON, media -> Arrays.asList(noAddress, large), SIZES));
        Size noSize = new Size("p0", 0, 0);
        assertSame(noSize, FullResolution.photo(post, noSize, ON, media -> Arrays.asList(noSize, large), SIZES));
        assertNull(FullResolution.photo(post, null, ON, media -> Arrays.asList(picked, large), SIZES));
        assertSame(picked, FullResolution.photo(null, picked, ON, media -> Arrays.asList(picked, large), SIZES));
    }

    /** The switch starts off, and off, paused or asked before the settings are read, Instagram's pick loads. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsPick() {
        BooleanSupplier setting = FullResolution::switchedOn;
        Settings.FULL_RESOLUTION_PHOTOS.resetToDefault();
        assertEquals(Boolean.FALSE, Settings.FULL_RESOLUTION_PHOTOS.defaultValue);
        assertSame(picked, photo(setting, picked, large));

        Settings.FULL_RESOLUTION_PHOTOS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(picked, photo(setting, picked, large));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(picked, photo(setting, picked, large)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertSame(picked, photo(setting, picked, large)));

        assertSame(large, photo(setting, picked, large));
    }

    /** A switch or a read that throws keeps the pick and says the hook threw. */
    @Test
    public void aThrowingReadKeepsThePickAndIsReported() {
        assertSame(picked, photo(THROWS, picked, large));
        String missing = HookStatus.missing(FamilyNames.FULL_RESOLUTION).toString();
        assertTrue(missing, missing.contains("'" + FullResolution.STEP + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));

        HookStatus.clear();
        assertSame(picked, FullResolution.photo(post, picked, ON, media -> {
            throw new IllegalStateException("no sizes");
        }, SIZES));
        assertTrue(HookStatus.missing(FamilyNames.FULL_RESOLUTION).toString().contains(IllegalStateException.class.getName()));

        HookStatus.clear();
        assertSame(picked, FullResolution.photo(post, picked, ON, media -> Arrays.asList(picked, large), new FullResolution.Sizes() {
            @Override
            public String url(Object size) {
                throw new ClassCastException("not a size");
            }

            @Override
            public int width(Object size) {
                return 0;
            }

            @Override
            public int height(Object size) {
                return 0;
            }
        }));
        assertTrue(HookStatus.missing(FamilyNames.FULL_RESOLUTION).toString().contains(ClassCastException.class.getName()));
    }

    /** The hook as the patch writes it keeps the pick until the patch has filled the bridges it reads. */
    @Test
    public void theStockHookKeepsThePick() {
        assertSame(picked, FullResolution.photo(post, picked));
        assertNull(FullResolution.photo(post, null));
        assertSame(picked, FullResolution.photo(null, picked));
    }
}

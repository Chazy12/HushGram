/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.home

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.feed.FeedItemStandIns
import app.morphe.patches.instagram.feed.FeedItemStandIns.assertFilteredBeforeReturn
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.instagram.feed.reels.FILTER
import app.morphe.patches.instagram.feed.reels.filterParsedFeedItems
import app.morphe.patches.instagram.feed.suggested.emptiedFeedEndPatch
import app.morphe.patches.instagram.feed.suggested.hideSuggestedPostsPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide the home feed: every item Instagram's home feed parse helper answers goes through HomeFeed,
 * whatever its kind, and the feed end hook it shares with Hide suggested posts goes in once.
 */
class HideHomeFeedHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(HOME_FEED_FILTER.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HOME_FEED_FILTER is not in the extension: $declared", HOME_FEED_FILTER.substringAfter("->") in declared)
    }

    /** No kind is asked for, so an item whose enums name none of the feed's units still goes. */
    @Test
    fun theParseHelperAnswersThroughTheFilterWhateverTheKinds() {
        val context = PatchContexts.of(FeedItemStandIns.classes(kindNames = listOf("MEDIA")))

        context.filterHomeFeedItems()

        val patched = context.mutableClassDefBy(FeedItemStandIns.ITEM)
        assertFilteredBeforeReturn("helper", patched.methods.single { it.name == "A02" }, HOME_FEED_FILTER)
        assertEquals("the other static helper was touched", 2, patched.methods.single { it.name == "A01" }.instructions().size)
    }

    /** After Hide Reels in the feed, both filters answer at each return. */
    @Test
    fun afterHideReelsInTheFeedBothFiltersAnswer() {
        val context = PatchContexts.of(FeedItemStandIns.classes(kindNames = listOf("MEDIA", "AD", "CLIPS_NETEGO",
            "IMMERSIVE_SEGUE_ITEM", "VIBES_IN_FEED_UNIT", "HATCH_IMMERSIVE_IN_FEED_UNIT")))

        context.filterParsedFeedItems()
        context.filterHomeFeedItems()

        val helper = context.mutableClassDefBy(FeedItemStandIns.ITEM).methods.single { it.name == "A02" }
        assertFilteredBeforeReturn("helper", helper, FILTER, HOME_FEED_FILTER)
    }

    /** Two parse helpers fail the patch before either one changes. */
    @Test
    fun twoParseHelpersFailThePatchUnchanged() {
        val context = PatchContexts.of(FeedItemStandIns.classes(kindNames = listOf("MEDIA"), helpers = 2))
        val before = context.classDefBy(FeedItemStandIns.ITEM).methods.associate { it.name to it.instructions().size }

        assertThrows(PatchException::class.java) { context.filterHomeFeedItems() }

        val after = context.classDefBy(FeedItemStandIns.ITEM).methods.associate { it.name to it.instructions().size }
        assertEquals(before, after)
    }

    /** Both patches that empty Home lean on one feed end patch, so its hook goes in once. */
    @Test
    fun theFeedEndIsSharedWithHideSuggestedPosts() {
        assertTrue(emptiedFeedEndPatch in hideHomeFeedPatch.dependencies)
        assertTrue(emptiedFeedEndPatch in hideSuggestedPostsPatch.dependencies)
    }

    /** In each declared build the one parse helper answers every item through HomeFeed. */
    @Test
    fun eachDeclaredBuildFiltersTheParsedFeedItem() {
        for (fixture in FeedItemStandIns.fixtures()) {
            val context = PatchContexts.of(fixture.classes)

            context.filterHomeFeedItems()

            val before = fixture.helper
            val after = context.mutableClassDefBy(fixture.itemType).methods.single {
                it.name == before.name && it.parameterTypes.map(Any::toString) == before.parameterTypes.map(Any::toString)
            }
            val returns = before.instructions().count { it.opcode == Opcode.RETURN_OBJECT }
            assertEquals("${fixture.bundle.name}: helper size", before.instructions().size + 3 * returns, after.instructions().size)
            assertFilteredBeforeReturn(fixture.bundle.name, after, HOME_FEED_FILTER)
        }
    }
}

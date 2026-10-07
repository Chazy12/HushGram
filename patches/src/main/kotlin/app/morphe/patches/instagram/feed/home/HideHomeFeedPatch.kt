/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.home

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.feed.filterParsedFeedItems
import app.morphe.patches.instagram.feed.suggested.emptiedFeedEndPatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

private const val PATCH = "Hide the home feed"
internal const val HOME_FEED_FILTER =
    "$EXTENSION_PACKAGE/feed/HomeFeed;->filter(Ljava/lang/Object;)Ljava/lang/Object;"

@Suppress("unused")
val hideHomeFeedPatch = bytecodePatch(
    name = "Hide the home feed",
    description = "Empties your home feed on purpose, so Home shows the stories row and nothing under it. " +
        "Profiles, Explore and Reels still show posts.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch, emptiedFeedEndPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("homeFeed")
        filterHomeFeedItems()
        enableStatus("homeFeed")
    }
}

/**
 * Passes each item Instagram's home feed parse helper answers through HomeFeed, which answers null
 * for every one of them while the switch is on, whatever its kind. Every caller of the helper skips
 * a null item, and the stories row comes from a request of its own, so it stays. Once the feed is
 * empty, the shared feed end hook has Instagram draw its own empty feed card in place of a loading
 * row that would never finish.
 */
internal fun BytecodePatchContext.filterHomeFeedItems() = filterParsedFeedItems(PATCH, HOME_FEED_FILTER, emptyList())

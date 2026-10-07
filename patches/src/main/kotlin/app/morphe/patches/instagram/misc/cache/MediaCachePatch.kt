/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.cache

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/**
 * Turns on the extension's cache cleaner. It needs no hook of its own: the settings patch already
 * calls into the extension once Instagram's application starts, and from there MediaCache hears
 * each time Instagram goes to the background, through Android's own memory callbacks.
 */
@Suppress("unused")
val clearMediaCachePatch = bytecodePatch(
    name = "Clear the media cache",
    description = "Deletes the images and videos Instagram keeps in its cache when it goes to the background with " +
        "more than 500 MB of them, and adds a Clear now row that shows what it freed. Your sign-in, drafts and " +
        "settings stay.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        enableStatus("mediaCache")
    }
}

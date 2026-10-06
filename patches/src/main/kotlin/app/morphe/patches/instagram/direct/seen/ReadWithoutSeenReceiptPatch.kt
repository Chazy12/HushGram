/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

internal const val THREAD_SEEN_PATCH = "Read messages without the seen receipt"

/** Opt-in, one-sided chat receipt protection, independent of the view-once receipt patch. */
@Suppress("unused")
val readWithoutSeenReceiptPatch = bytecodePatch(
    name = "Read messages without the seen receipt",
    description = "Adds an off-by-default switch so opening a chat doesn't tell people you've seen their " +
        "messages. Unlike turning off read receipts in Instagram's settings, you still see when they've seen " +
        "yours. To let one chat know, long press it and tap Mark as read. View-once photos and videos have " +
        "their own patch.",
    default = false,
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("threadSeen")
        readWithoutSeenReceipt()
        enableStatus("threadSeen")
    }
}

/**
 * Resolves the receipt's handler and everything Mark as read needs before changing anything, then
 * holds the receipt back and offers Mark as read on a chat's long press.
 */
internal fun BytecodePatchContext.readWithoutSeenReceipt() {
    val seen = findThreadSeen()
    val marks = findMarkRead(seen)
    holdBackThreadSeen(seen)
    markReadByHand(marks)
}

/** [holdBackThreadSeen] on the handler this build resolves to, with nothing else hooked. */
internal fun BytecodePatchContext.holdBackThreadSeen() = holdBackThreadSeen(findThreadSeen())

/**
 * Completes the chat receipt's queued task through Instagram's own success callback before any
 * request is built. The chat still clears on this phone, since that happens before the queue runs,
 * and the receipt isn't retried after a restart. The extension is handed the receipt, so it can let
 * through the one for a chat marked read by hand.
 */
internal fun holdBackThreadSeen(found: ThreadSeenTargets) {
    found.handler.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, ${found.handler.parameterRegister(2)}
            invoke-static { v0 }, $HOLD_THREAD_SEEN
            move-result v0
            if-eqz v0, :instagram
            move-object/from16 v1, ${found.handler.parameterRegister(1)}
            const/4 v0, 0x0
            invoke-interface { v1, v0, v0 }, ${found.complete}
            return-void
        """,
        ExternalLabel("instagram", found.handler.getInstruction(0)),
    )
}

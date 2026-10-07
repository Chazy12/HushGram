/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags

internal const val LOCK_PATCH = "Lock your messages"
internal const val MESSAGES_LOCK = "$EXTENSION_PACKAGE/direct/MessagesLock;"
internal const val HIDE_NOTIFICATION = "$MESSAGES_LOCK->notification(Landroid/app/Notification;)Landroid/app/Notification;"
internal const val HOLD_BANNER = "$MESSAGES_LOCK->holdBanner()Z"

/** What androidx's NotificationManagerCompat.notify checks first: Instagram posts every notification through it. */
internal const val SIDE_CHANNEL = "android.support.useSideChannel"

/** What Instagram's in-app banner logs when no activity can show it, first thing in the method that shows one. */
internal const val NO_BANNER_ACTIVITY = "no foreground activity to render in-app notification"

internal val NOTIFY_PARAMETERS = listOf("Ljava/lang/String;", "I", "Landroid/app/Notification;")

/** notify(tag, id, notification): the instance method that checks [SIDE_CHANNEL]. */
internal object NotifyFingerprint : Fingerprint(
    returnType = "V",
    parameters = NOTIFY_PARAMETERS,
    strings = listOf(SIDE_CHANNEL),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/** The static method that shows the banner, (Context, notification, the banner's owner). */
internal object BannerFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(NO_BANNER_ACTIVITY),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 3 &&
            method.parameterTypes.first().toString() == "Landroid/content/Context;"
    },
)

@Suppress("unused")
val lockMessagesPatch = bytecodePatch(
    name = "Lock your messages",
    description = "Adds switches that keep your inbox and chats, or all of Instagram, covered until your " +
        "fingerprint, face or screen lock says it's you. They lock again when you leave Instagram or after " +
        "the time you pick, and message notifications say only that a message came.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("messagesLock")
        val targets = findLockTargets()
        hideNotificationText(targets.notify)
        holdBanner(targets.banner)
        enableStatus("messagesLock")
    }
}

internal class LockTargets(val notify: MutableMethod, val banner: MutableMethod)

private fun refuse(why: String): Nothing = throw PatchException("$LOCK_PATCH: $why")

/**
 * Both methods, proved before either changes: each must have no jump back to its first
 * instruction, where the hook goes, and the banner needs a local for its answer.
 */
internal fun BytecodePatchContext.findLockTargets(): LockTargets {
    val notify = uniqueMethod(LOCK_PATCH, "notification poster", NotifyFingerprint)
    val banner = uniqueMethod(LOCK_PATCH, "in-app banner", BannerFingerprint)
    if (0 in notify.jumpTargets()) refuse("something jumps back to the notification poster's first instruction")
    if (0 in banner.jumpTargets()) refuse("something jumps back to the in-app banner's first instruction")
    banner.requireLocals(LOCK_PATCH, 1)
    return LockTargets(notify, banner)
}

/** The notification goes through the extension first, which hands back a copy without the message while locked. */
internal fun hideNotificationText(notify: MutableMethod) {
    val notification = notify.parameterRegister(NOTIFY_PARAMETERS.indexOf("Landroid/app/Notification;"))
    notify.addInstructions(
        0,
        """
            invoke-static/range { $notification .. $notification }, $HIDE_NOTIFICATION
            move-result-object $notification
        """,
    )
}

/** The banner returns before it takes its lock or builds anything while the messages are locked. */
internal fun holdBanner(banner: MutableMethod) {
    banner.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HOLD_BANNER
            move-result v0
            if-eqz v0, :show
            return-void
        """,
        ExternalLabel("show", banner.getInstruction(0)),
    )
}

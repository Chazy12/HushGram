/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.confirm

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val REFRESH_PATCH = "Ask before a refresh"

internal const val REFRESH_CONFIRM = "$EXTENSION_PACKAGE/feed/RefreshConfirm;"
internal const val REFRESH_LISTENER =
    "$REFRESH_CONFIRM->listener(Landroid/view/View;Ljava/lang/Object;Landroid/view/animation/Animation\$AnimationListener;)Ljava/lang/Object;"
internal const val ANIMATION_LISTENER = "Landroid/view/animation/Animation\$AnimationListener;"
private const val ANIMATION = "Landroid/view/animation/Animation;"

/**
 * Pulling down to refresh a list waits for a question while the switch is on. Included in the
 * default selection with its switch off, so asking is the user's pick.
 */
@Suppress("unused")
val askBeforeRefreshPatch = bytecodePatch(
    name = "Ask before a refresh",
    description = "Asks before pulling down refreshes Home, Reels or another list, so a stray pull keeps what's on " +
        "screen. Refresh goes ahead, and Cancel stops the spinner. Its switch, under Feed, starts off.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("askBeforeRefresh")
        askBeforeRefresh()
        enableStatus("askBeforeRefresh")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$REFRESH_PATCH: $why")

/**
 * The end-of-pull animation's onAnimationEnd, the index of its read of the refresh listener, the
 * listener's type, and the registers holding the listener, the layout and the animation itself.
 */
internal class PullEndTarget(
    val method: MutableMethod,
    val read: Int,
    val listenerType: String,
    val held: Int,
    val layout: Int,
    val self: Int,
)

/**
 * Finds where a pull-down refresh layout's end-of-pull animation reads the layout's refresh
 * listener, and checks it before anything changes. The layout is the one class with a
 * setOnRefreshListener that only stores its listener in a field of its own and a
 * setRefreshing(boolean). The read is the one in an animation listener's onAnimationEnd, checked
 * for null right after and then called with no arguments, in registers the hook can reach, with
 * nothing jumping to the check and the animation itself still in its register there.
 */
internal fun BytecodePatchContext.findPullEnd(): PullEndTarget {
    val layouts = mutableListOf<FieldReference>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.listenerField()?.let { layouts += it }
    }
    val listener = layouts.singleOrNull()
        ?: refuse("expected one pull-down refresh layout that only stores its listener, found ${layouts.size}")

    val sites = mutableListOf<Pair<Method, Int>>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT) || ANIMATION_LISTENER !in classDef.interfaces) return@classDefForEach
        val end = classDef.methods.singleOrNull {
            it.name == "onAnimationEnd" && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(ANIMATION) &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: return@classDefForEach
        val code = end.implementation?.instructions?.toList().orEmpty()
        code.indices.filter { code[it].reads(listener) }.forEach { sites += end to it }
    }
    val (end, read) = sites.singleOrNull()
        ?: refuse("expected one end-of-pull animation reading ${listener.definingClass}->${listener.name}, found ${sites.size}")
    val code = end.implementation!!.instructions.toList()
    val load = code[read] as TwoRegisterInstruction
    val held = load.registerA
    val test = code.getOrNull(read + 1)
    if (test?.opcode != Opcode.IF_EQZ || (test as OneRegisterInstruction).registerA != held) {
        refuse("the end-of-pull animation doesn't check the refresh listener right after reading it")
    }
    val calls = code.getOrNull(read + 2)
    val called = (calls as? ReferenceInstruction)?.reference as? MethodReference
    if (calls?.opcode != Opcode.INVOKE_INTERFACE || called?.definingClass != listener.type || called.returnType != "V" ||
        called.parameterTypes.isNotEmpty() || (calls as? FiveRegisterInstruction)?.registerC != held
    ) {
        refuse("the end-of-pull animation doesn't call the refresh listener right after checking it")
    }
    val self = end.localRegisterCount()
    if (listOf(held, load.registerB, self).any { it > 15 }) {
        refuse("the end-of-pull animation keeps what the hook needs in a register it can't reach")
    }
    if (read + 1 in end.jumpTargets()) refuse("a jump lands on the end-of-pull animation's listener check")
    end.requireThisIntact(REFRESH_PATCH, listOf(read + 1))

    classDefByOrNull(REFRESH_CONFIRM)?.publicStatic(REFRESH_LISTENER)
        ?: refuse("the extension has no public static $REFRESH_LISTENER")
    val method = mutableClassDefBy(end.definingClass).methods.single {
        it.name == end.name && it.parameterTypes.map(Any::toString) == listOf(ANIMATION)
    }
    return PullEndTarget(method, read, listener.type, held, load.registerB, self)
}

/**
 * Hands the listener the end-of-pull animation just read to the extension, with the layout and the
 * animation itself, and puts the answer back in its place, ahead of Instagram's own null check.
 */
internal fun BytecodePatchContext.askBeforeRefresh() {
    val found = findPullEnd()
    found.method.addInstructions(
        found.read + 1,
        """
            invoke-static { v${found.layout}, v${found.held}, v${found.self} }, $REFRESH_LISTENER
            move-result-object v${found.held}
            check-cast v${found.held}, ${found.listenerType}
        """,
    )
}

/**
 * The field a pull-down refresh layout keeps its listener in: the class has a setRefreshing(boolean)
 * and one instance setOnRefreshListener whose whole body stores its argument in a field of the
 * class's own, of the argument's type. Null for any other class.
 */
private fun ClassDef.listenerField(): FieldReference? {
    val stores = methods.singleOrNull {
        it.name == "setOnRefreshListener" && it.returnType == "V" && it.parameterTypes.size == 1 &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: return null
    if (methods.none { it.name == "setRefreshing" && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf("Z") }) {
        return null
    }
    val code = stores.implementation?.instructions?.toList().orEmpty()
    if (code.size != 2 || code[0].opcode != Opcode.IPUT_OBJECT || code[1].opcode != Opcode.RETURN_VOID) return null
    val field = (code[0] as ReferenceInstruction).reference as FieldReference
    return field.takeIf { it.definingClass == type && it.type == stores.parameterTypes.single().toString() }
}

/** Whether this reads [field] into a register. */
private fun Instruction.reads(field: FieldReference): Boolean {
    if (opcode != Opcode.IGET_OBJECT) return false
    val read = (this as ReferenceInstruction).reference as FieldReference
    return read.definingClass == field.definingClass && read.name == field.name && read.type == field.type
}

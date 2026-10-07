/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lock your messages: every notification goes through the extension first, and the in-app banner
 * asks it before it shows. Anything the patch can't tell apart fails it before an instruction changes.
 */
class LockMessagesHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(MESSAGES_LOCK).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(HIDE_NOTIFICATION, HOLD_BANNER)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun bothHooksGoFirst() {
        val context = PatchContexts.of(listOf(poster(), banner()))
        context.lock()

        assertHooked("stand-ins", context, POSTER, BANNER)
    }

    @Test
    fun twoPostersFailThePatch() =
        refuses("notification poster", listOf(poster(), poster("Lfixture/OtherPoster;"), banner()))

    @Test
    fun aMissingBannerFailsThePatch() = refuses("in-app banner", listOf(poster()))

    /** A static poster, or a banner taking something other than a Context first, is something else. */
    @Test
    fun methodsOfAnotherShapeFailThePatch() {
        refuses("notification poster", listOf(poster(static = true), banner()))
        refuses("in-app banner", listOf(poster(), banner(first = "Ljava/lang/Object;")))
    }

    @Test
    fun aJumpBackToTheStartFailsThePatch() {
        refuses("jumps back to the notification poster", listOf(poster(loop = true), banner()))
        refuses("jumps back to the in-app banner", listOf(poster(), banner(loop = true)))
    }

    @Test
    fun aBannerWithoutALocalFailsThePatch() = refuses("needs 1", listOf(poster(), banner(registers = 3)))

    /** In each declared build: the one poster and the one banner, each asking the extension first. */
    @Test
    fun eachDeclaredBuildLocksMessages() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = (FixtureDex.classesHolding(bundle, SIDE_CHANNEL) + FixtureDex.classesHolding(bundle, NO_BANNER_ACTIVITY))
                    .distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val targets = context.findLockTargets()
                val poster = targets.notify.definingClass
                val banner = targets.banner.definingClass
                hideNotificationText(targets.notify)
                holdBanner(targets.banner)

                assertHooked(bundle.name, context, poster, banner)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun BytecodePatchContext.lock() {
        val targets = findLockTargets()
        hideNotificationText(targets.notify)
        holdBanner(targets.banner)
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.instructions().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) { context.lock() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.instructions().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    private fun assertHooked(what: String, context: BytecodePatchContext, poster: String, banner: String) {
        val notify = context.mutableClassDefBy(poster).methods.single { it.parameterTypes.map(CharSequence::toString) == NOTIFY_PARAMETERS }
        val code = notify.instructions()
        val notification = notify.implementation!!.registerCount - 1
        assertEquals("$what: the poster's call", HIDE_NOTIFICATION, (code[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the poster's call reads the notification", notification, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the copy", Opcode.MOVE_RESULT_OBJECT, code[1].opcode)
        assertEquals("$what: the copy goes back in the notification's register", notification, (code[1] as OneRegisterInstruction).registerA)

        val show = context.mutableClassDefBy(banner).methods.single { method -> method.instructions().any { text(it) == "\"$NO_BANNER_ACTIVITY\"" } }
        val shown = show.instructions()
        assertEquals("$what: the banner's call", HOLD_BANNER, (shown[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, shown[1].opcode)
        assertEquals("$what: the branch", Opcode.IF_EQZ, shown[2].opcode)
        assertEquals("$what: the branch's target", 4, (show.implementation!!.instructions.toList()[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$what: the early return", Opcode.RETURN_VOID, shown[3].opcode)
        for ((hook, method) in listOf(HIDE_NOTIFICATION to notify, HOLD_BANNER to show)) {
            assertEquals("$what: $hook calls", 1, method.instructions().count { (it as? ReferenceInstruction)?.reference?.toString() == hook })
        }
    }

    private fun text(instruction: Instruction): String = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        null -> instruction.opcode.name
        is StringReference -> "\"${reference.string}\""
        else -> "${instruction.opcode.name} $reference"
    }

    private companion object {
        const val POSTER = "Lfixture/Poster;"
        const val BANNER = "Lfixture/Banner;"

        /** Shaped like androidx's NotificationManagerCompat.notify(tag, id, notification). */
        fun poster(type: String = POSTER, static: Boolean = false, loop: Boolean = false): ClassDef {
            val code = listOf<Instruction>(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(SIDE_CHANNEL)),
                if (loop) ImmutableInstruction10t(Opcode.GOTO, -2) else ImmutableInstruction10x(Opcode.NOP),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            val access = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0)
            return clazz(type, "A01", NOTIFY_PARAMETERS, access, 6, code)
        }

        /** Shaped like Instagram's in-app banner: static (Context, notification, owner). */
        fun banner(first: String = "Landroid/content/Context;", registers: Int = 6, loop: Boolean = false): ClassDef {
            val code = listOf<Instruction>(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(NO_BANNER_ACTIVITY)),
                if (loop) ImmutableInstruction10t(Opcode.GOTO, -2) else ImmutableInstruction10x(Opcode.NOP),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            val access = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
            return clazz(BANNER, "A02", listOf(first, "Ljava/lang/Object;", "Ljava/lang/Object;"), access, registers, code)
        }

        fun clazz(type: String, name: String, parameters: List<String>, access: Int, registers: Int, code: List<Instruction>): ClassDef =
            ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V", access, null, null,
                        ImmutableMethodImplementation(registers, code, null, null),
                    ),
                ),
            )
    }
}

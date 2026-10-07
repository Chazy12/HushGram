/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.notifications

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every notification Instagram posts goes through NotificationGroups, which puts it in HushGram's
 * group while the switch is on and posts it exactly as built while it's off.
 */
class GroupNotificationsHookTest {
    private val untagged = NOTIFY_SHAPES[0]
    private val tagged = NOTIFY_SHAPES[1]

    private fun call(shape: String, definingClass: String = NOTIFICATION_MANAGER, name: String = "notify") =
        ImmutableMethodReference(
            definingClass, name,
            Regex("""L[^;]+;|I""").findAll(shape.substringBefore(')').drop(1)).map { it.value }.toList(),
            shape.substringAfter(')'),
        )

    /**
     * A class of [type] whose one static method posts twice the way Instagram's code does: v0 the
     * manager, v1 a tag, v2 an id and v3 the notification, untagged as a five-register call and
     * tagged as a range call. A cancel and a compat class's notify sit between and stay.
     */
    private fun poster(type: String): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            ImmutableMethod(
                type, "post", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(
                    4,
                    listOf(
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 0, 2, 3, 0, 0, call(untagged)),
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 2, 0, 0, 0,
                            ImmutableMethodReference(NOTIFICATION_MANAGER, "cancel", listOf("I"), "V")),
                        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 0, 2, 3, 0, 0,
                            call(untagged, "Landroidx/core/app/NotificationManagerCompat;")),
                        ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 0, 4, call(tagged)),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ),
                    null, null,
                ),
            ),
        ),
    )

    @Test
    fun eachPostGoesToItsStandInWithTheSameRegisters() {
        val instagram = "Lfixture/NotificationPoster;"
        val context = PatchContexts.of(listOf(poster(instagram)))

        assertEquals(2, context.groupNotifications())

        val code = context.mutableClassDefBy(instagram).methods.single().instructions()
        assertEquals(Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals(
            "$NOTIFICATION_GROUPS->notify(Landroid/app/NotificationManager;ILandroid/app/Notification;)V",
            (code[0] as ReferenceInstruction).reference.toString(),
        )
        assertEquals(listOf(0, 2, 3), code[0].registers())
        assertEquals("a cancel stays", "cancel", ((code[1] as ReferenceInstruction).reference as MethodReference).name)
        assertEquals("another class's notify stays", Opcode.INVOKE_VIRTUAL, code[2].opcode)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[3].opcode)
        assertEquals(
            "$NOTIFICATION_GROUPS->notify(Landroid/app/NotificationManager;Ljava/lang/String;ILandroid/app/Notification;)V",
            (code[3] as ReferenceInstruction).reference.toString(),
        )
        assertEquals(listOf(0, 1, 2, 3), code[3].registers())
    }

    /** The extension's own posts are the real ones the stand-ins make. Sent, each would call itself. */
    @Test
    fun theExtensionsOwnPostsStay() {
        val instagram = "Lfixture/NotificationPoster;"
        val context = PatchContexts.of(listOf(poster(instagram), poster(NOTIFICATION_GROUPS)))

        assertEquals(2, context.groupNotifications())

        val kept = context.mutableClassDefBy(NOTIFICATION_GROUPS).methods.single().instructions()
        assertEquals(listOf(untagged, tagged), kept.mapNotNull { it.notifyCall() })
    }

    @Test
    fun aBuildPostingNothingFailsThePatch() {
        val quiet = ImmutableClassDef(
            "Lfixture/Quiet;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, emptyList<Method>(),
        )
        assertThrows(PatchException::class.java) { PatchContexts.of(listOf(quiet)).groupNotifications() }
    }

    /**
     * Each stand-in the rewrite writes is a public static method of the NotificationGroups the
     * bundle ships, read from the compiled extension, so a parameter that compiles to another type
     * fails here and not in Instagram's notification code.
     */
    @Test
    fun everyCallSentHasAStandIn() {
        val declared = ExtensionDex.classDef(NOTIFICATION_GROUPS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$NOTIFICATION_GROUPS->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            .toSet()
        NOTIFY_SHAPES.forEach { shape ->
            assertTrue("NotificationGroups declares no ${notifyStandIn(shape)}: $declared", notifyStandIn(shape) in declared)
        }
    }

    /**
     * The declared build posts from nine methods without a tag and seven with one (Firebase's
     * display notifications among them). Every one of those calls goes to its stand-in on the same
     * registers, with the instruction count unchanged, and none is left behind.
     */
    @Test
    fun eachDeclaredBuildSendsEveryPost() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val callers = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.methodSection.none { it.definingClass == NOTIFICATION_MANAGER && it.name == "notify" }) {
                        return@forEach
                    }
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { method -> method.instructions().any { it.notifyCall() != null } }) {
                            callers += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val posting = callers.flatMap { it.methods }.filter { method -> method.instructions().any { it.notifyCall() != null } }
                val byShape = NOTIFY_SHAPES.associateWith { shape ->
                    posting.count { method -> method.instructions().any { it.notifyCall() == shape } }
                }
                assertEquals("${bundle.name}: methods posting by shape", mapOf(untagged to 9, tagged to 7), byShape)

                val context = PatchContexts.of(callers)
                val sent = context.groupNotifications()

                var calls = 0
                for (before in callers) {
                    val after = context.mutableClassDefBy(before.type).methods
                    for (original in before.methods) {
                        val was = original.instructions()
                        if (was.none { it.notifyCall() != null }) continue
                        val where = "${bundle.name}: ${original.definingClass}->${original.name}"
                        val now = after.single { it.sameSignatureAs(original) }.instructions()
                        assertEquals("$where: instruction count", was.size, now.size)
                        assertEquals("$where: posts left", emptyList<String>(), now.mapNotNull { it.notifyCall() })
                        was.forEachIndexed { index, instruction ->
                            val shape = instruction.notifyCall() ?: return@forEachIndexed
                            assertEquals("$where at $index", notifyStandIn(shape), (now[index] as ReferenceInstruction).reference.toString())
                            assertEquals("$where at $index: registers", instruction.registers(), now[index].registers())
                            calls++
                        }
                    }
                }
                assertEquals("${bundle.name}: posts sent", calls, sent)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    /** Parameters compared as text: dexlib2's lists of two kinds don't equal each other. */
    private fun Method.sameSignatureAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }
}

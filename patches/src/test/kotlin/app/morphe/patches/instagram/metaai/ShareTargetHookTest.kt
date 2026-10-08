/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hide Meta AI in the share sheet: the answer of the target builder's "hatch" check goes through the hook. */
class ShareTargetHookTest {
    private val builder = "Lfixture/ShareSheetTargets;"
    private val other = "Lfixture/HatchSettings;"
    private val parameters = listOf("Lfixture/ShareSheet;", "Ljava/lang/Object;")

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(SHARE_TARGET.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SHARE_TARGET is not in the extension: $declared", SHARE_TARGET.substringAfter("->") in declared)
    }

    /** Only the case's answer is asked about; the later check that moves a hatch target and other classes stay. */
    @Test
    fun theHatchCaseAsks() {
        val classes = classes()
        val context = PatchContexts.of(classes)

        context.holdShareTarget(context.findShareTargetCheck())

        val code = context.mutableClassDefBy(builder).methods.single().code()
        assertAsked("the stand-in", code)
        assertEquals("one ask", 1, code.count { it.referenceText() == SHARE_TARGET })
        assertTrue(
            "a class that isn't the builder was touched",
            context.mutableClassDefBy(other).methods.single().code().none { it.referenceText() == SHARE_TARGET },
        )
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(names = SHARE_ROW_NAMES - "whatsapp_status") to "0 methods hold",
            classes(builders = 2) to "2 methods hold",
            classes(cases = 0) to "compares 0 target names",
            classes(cases = 2) to "compares 2 target names",
            classes(enters = true) to "a branch enters",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(PatchException::class.java) { context.findShareTargetCheck() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.flatMap { owner -> context.mutableClassDefBy(owner.type).methods }
                .filter { method -> method.code().any { it.referenceText() == SHARE_TARGET } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build the case is the share sheet's switch arm for "hatch", whose no goes where
     * an unknown name does, and the hook sits between its answer and its test.
     */
    @Test
    fun eachDeclaredBuildAsksInItsHatchCase() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = FixtureDex.classesHolding(bundle, "whatsapp_status")
                val context = PatchContexts.of(holders)
                val site = context.findShareTargetCheck()
                val original = holders.single { it.type == site.type }.methods.single {
                    it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
                }.code()
                // 450's builder switches on each name's hash, and the case is the switch's arm for "hatch".
                val hatch = HATCH_TARGET.hashCode()
                val switches = original.indices.filter { index ->
                    original[index].opcode == Opcode.SPARSE_SWITCH &&
                        (original[target(original, index)] as SwitchPayload).switchElements.any { it.key == hatch }
                }
                assertEquals("${bundle.name}: switches with a hatch arm", 1, switches.size)
                val switch = switches.single()
                val arm = (original[target(original, switch)] as SwitchPayload).switchElements.single { it.key == hatch }
                assertEquals("${bundle.name}: the case is the hatch arm", site.moveResult - 2, at(original, address(original, switch) + arm.offset))
                // The case's no is where the switch sends a name it doesn't know.
                assertTrue("${bundle.name}: a goto follows the switch", original[switch + 1] is OffsetInstruction)
                assertEquals("${bundle.name}: a no skips the name", target(original, switch + 1), target(original, site.moveResult + 1))

                context.holdShareTarget(site)

                val code = context.mutableClassDefBy(site.type).methods.single {
                    it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
                }.code()
                assertAsked(bundle.name, code)
                assertEquals("${bundle.name}: one ask", 1, code.count { it.referenceText() == SHARE_TARGET })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** "hatch", the name compared with it, the answer, the ask on the answer's register, the answer back, then the test. */
    private fun assertAsked(what: String, code: List<Instruction>) {
        val at = code.indexOfFirst { it.referenceText() == SHARE_TARGET }
        assertTrue("$what: no ask", at >= 2)
        assertEquals("$what: the name check", "Ljava/lang/String;->equals(Ljava/lang/Object;)Z", code[at - 2].referenceText())
        assertEquals("$what: the case's string", HATCH_TARGET, code[at - 3].string())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[at - 1].opcode)
        val register = (code[at - 1] as OneRegisterInstruction).registerA
        val ask = code[at] as RegisterRangeInstruction
        assertEquals("$what: the ask takes the answer", listOf(register, 1), listOf(ask.startRegister, ask.registerCount))
        assertEquals("$what: the answer back", Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals("$what: in the same register", register, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: then the test", Opcode.IF_EQZ, code[at + 2].opcode)
        assertEquals("$what: of that register", register, (code[at + 2] as OneRegisterInstruction).registerA)
    }

    // ---- stand-ins shaped like Instagram 450's ------------------------------------------------

    /**
     * The share sheet's target builder holds its names and compares one with "hatch" in Meta AI's
     * case; a later check, which moves a hatch target already in the row, compares the other way
     * round. Another class names hatch in a setting of its own.
     */
    private fun classes(
        names: List<String> = SHARE_ROW_NAMES,
        builders: Int = 1,
        cases: Int = 1,
        enters: Boolean = false,
    ): List<ClassDef> {
        val strings = names.filter { it != HATCH_TARGET }.joinToString("\n") { "const-string v0, \"$it\"" }
        val case = """
            const-string v1, "$HATCH_TARGET"
            ${if (enters) ":compare" else ""}
            invoke-virtual { v2, v1 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v2
            if-eqz v2, :skip
        """
        val body = """
            $strings
            const/4 v1, 0x0
            const/4 v2, 0x0
            const/4 v3, 0x0
            ${if (enters) "if-eqz v3, :compare" else ""}
            ${List(cases) { case }.joinToString("\n")}
            const/4 v0, 0x0
            :skip
            const-string v1, "$HATCH_TARGET"
            invoke-virtual { v1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v1
            const/4 v0, 0x0
            return-object v0
        """
        val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
        val builderClasses = (0 until builders).map { copy ->
            val type = if (copy == 0) builder else "Lfixture/OtherShareSheetTargets;"
            classDef(type, listOf(method(type, "targets", parameters, "Ljava/util/ArrayList;", 6, body, flags)))
        }
        val otherClass = classDef(other, listOf(method(other, "name", emptyList(), "Ljava/lang/String;", 2, """
            const-string v0, "$HATCH_TARGET"
            return-object v0
        """)))
        return builderClasses + otherClass
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
    ): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    /** The index a branch, a goto or a switch's payload reference at [index] lands on. */
    private fun target(code: List<Instruction>, index: Int): Int =
        at(code, address(code, index) + (code[index] as OffsetInstruction).codeOffset)

    /** The code address of the instruction at [index]. */
    private fun address(code: List<Instruction>, index: Int): Int = code.take(index).sumOf { it.codeUnits }

    /** The index of the instruction at code [address]. */
    private fun at(code: List<Instruction>, address: Int): Int {
        var current = 0
        for (candidate in code.indices) {
            if (current == address) return candidate
            current += code[candidate].codeUnits
        }
        throw AssertionError("no instruction at $address")
    }
}

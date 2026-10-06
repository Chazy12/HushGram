/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.photos

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FullResolutionPhotosHookTest {
    private val contextType = "Landroid/content/Context;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val useCase = "Lfixture/FeedImageUseCase;"
    private val forScreen = "$MEDIA_EXT->forScreen($contextType$MEDIA)$EXTENDED_IMAGE_URL"
    private val parameters = listOf(contextType, session, MEDIA)

    /** The hook the patch writes is in the FullResolution the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FULL_RESOLUTION).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$PHOTO is not in the extension: $declared", PHOTO.substringAfter("->") in declared)
    }

    /**
     * Right after the size is kept: the call with the post and the size, the answer back in the
     * size's register, cast to a size, and then what came next before. The branch past the pick
     * still lands where it did, and the method's other code is unchanged.
     */
    @Test
    fun theSizeIsHandedOverRightAfterThePick() {
        val classes = classes()
        val before = classes.single().methods.single().code().size
        val patched = PatchContexts.of(classes)

        patched.load()

        val method = patched.mutableClassDefBy(useCase).methods.single()
        assertHooked("stand-in", method, media = 3, size = 4)
        assertEquals("the method grew by more than the hook", before + 3, method.code().size)
        val none = method.code().indexOfFirst { it.string() == NO_IMAGE_URL }
        val branch = method.implementation!!.instructions.first { it.opcode == Opcode.IF_EQZ } as BuilderOffsetInstruction
        assertEquals("the branch past the pick moved", none, branch.target.location.index)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val method = "feed photo address method logging that it found none in this Instagram build"
        val where = "$useCase->source"
        val cases = listOf(
            classes(methods = 0) to "expected exactly one $method, found none",
            classes(methods = 2) to "expected exactly one $method, found $where, $useCase->source2",
            classes(static = true) to "expected exactly one $method, found none",
            classes(calls = 0) to "expected $where to ask Media's helpers once for the size for a Context, found 0",
            classes(calls = 2) to "expected $where to ask Media's helpers once for the size for a Context, found 2",
            classes(keepsSize = false) to "$where doesn't keep the size it's handed",
            classes(sizeRegister = 3) to "$where keeps the size in the post's own register v3",
            classes(sizeRegister = 17) to "$where keeps the post in v3 and the size in v17, past what a plain invoke can name",
            classes(jumpPastPick = true) to "something in $where jumps in right after the size it's handed",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.load() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == PHOTO } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build the method picking a feed photo's address is found by its log line
     * alone, and the hook lands right after its one ask for the size for the screen.
     */
    @Test
    fun eachDeclaredBuildHandsOverTheFeedPhotosSize() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { method -> method.code().any { it.string() == NO_IMAGE_URL } }) {
                            holders += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val context = PatchContexts.of(holders)

                val site = context.findFullResolution()
                context.loadFullResolution(site)

                val method = context.mutableClassDefBy(site.definingClass).methods.single {
                    it.name == site.name && it.parameterTypes.map(CharSequence::toString) == parameters
                }
                assertTrue("${bundle.name}: the method is an instance one", !AccessFlags.STATIC.isSet(method.accessFlags))
                assertHooked(bundle.name, method, site.media, site.size)
                val written = holders.map { it.type }.distinct().flatMap { context.mutableClassDefBy(it).methods }
                    .filter { written -> written.code().any { it.referenceText() == PHOTO } }
                assertEquals("${bundle.name}: methods hooked", 1, written.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Once in the method: the static call to Media's helpers taking a Context and the post, the
     * move-result keeping the size, then the hook with the post and the size, its answer back in
     * the size's register and the cast to a size. Nothing jumps onto the hook.
     */
    private fun assertHooked(what: String, method: MutableMethod, media: Int, size: Int) {
        val code = method.code()
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == PHOTO })
        val hook = code.indexOfFirst { it.referenceText() == PHOTO }
        val pick = code[hook - 2]
        val picked = (pick as ReferenceInstruction).reference as MethodReference
        assertEquals("$what: what comes before", MEDIA_EXT, picked.definingClass)
        assertEquals("$what: what it's handed", listOf(contextType, MEDIA), picked.parameterTypes.map(CharSequence::toString))
        assertEquals("$what: what it answers", EXTENDED_IMAGE_URL, picked.returnType)
        assertEquals("$what: the post handed to the pick", media, (pick as FiveRegisterInstruction).registerD)
        assertEquals("$what: the kept size", Opcode.MOVE_RESULT_OBJECT, code[hook - 1].opcode)
        assertEquals("$what: the kept size's register", size, (code[hook - 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the call", Opcode.INVOKE_STATIC, code[hook].opcode)
        val call = code[hook] as FiveRegisterInstruction
        assertEquals("$what: the post and the size", listOf(2, media, size), listOf(call.registerCount, call.registerC, call.registerD))
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
        assertEquals("$what: the answer's register", size, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast", Opcode.CHECK_CAST, code[hook + 2].opcode)
        assertEquals("$what: the cast register", size, (code[hook + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast type", EXTENDED_IMAGE_URL, ((code[hook + 2] as ReferenceInstruction).reference as TypeReference).type)
        val jumps = method.implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>()
        assertTrue("$what: something jumps onto the hook", jumps.none { it.target.location.index in hook..hook + 2 })
    }

    private fun BytecodePatchContext.load() = loadFullResolution(findFullResolution())

    /**
     * A feed image use case shaped like Instagram 450's. Its one method takes the Context, the
     * session and the post, copies the post into v3, asks Media's helpers
     * for the size for the screen with the Context in v0 and keeps it in v4. A size goes on to the
     * end; none, or no picture at all, logs [NO_IMAGE_URL] first.
     */
    private fun classes(
        methods: Int = 1,
        static: Boolean = false,
        calls: Int = 1,
        keepsSize: Boolean = true,
        sizeRegister: Int = 4,
        jumpPastPick: Boolean = false,
    ): List<ClassDef> {
        fun source(name: String): Method {
            val ask = "invoke-static {v0, v3}, $forScreen"
            val keep = if (keepsSize) "move-result-object v$sizeRegister" else "nop"
            val asks = when (calls) {
                0 -> listOf("invoke-static {v3}, $MEDIA_EXT->cached($MEDIA)$EXTENDED_IMAGE_URL", "move-result-object v4")
                else -> List(calls) { listOf(ask, keep) }.flatten()
            }
            // Twenty locals, then this when there is one, so the Context is v20 or v21 and the post two on.
            val context = if (static) 20 else 21
            return method(
                name, 20, static,
                body = (
                    listOf(
                        "move-object/from16 v3, v${context + 2}",
                        "invoke-static {v3}, $MEDIA_EXT->hasPicture($MEDIA)Z",
                        "move-result v1",
                        "if-eqz v1, :none",
                        if (jumpPastPick) "if-nez v1, :after" else "nop",
                        "move-object/from16 v0, v$context",
                    ) + asks + listOf(
                        ":after",
                        "if-nez v4, :found",
                        ":none",
                        "const-string v0, \"$NO_IMAGE_URL\"",
                        "invoke-static {v0}, Lfixture/Log;->report(Ljava/lang/String;)V",
                        "const/4 v4, 0x0",
                        ":found",
                        "return-object v4",
                    )
                    ).filter { it != "nop" }.joinToString("\n"),
            )
        }
        val sources = (1..methods).map { source(if (it == 1) "source" else "source$it") }
        val padding = if (methods == 0) {
            listOf(method("unrelated", 2, static = false, body = "const/4 v0, 0x0\nreturn-object v0"))
        } else {
            emptyList()
        }
        return listOf(classDef(useCase, sources + padding))
    }

    private fun method(name: String, locals: Int, static: Boolean, body: String): Method {
        var flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        if (static) flags = flags or AccessFlags.STATIC.value
        val total = locals + (if (static) 0 else 1) + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                useCase, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "Ljava/lang/Object;", flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null, methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.string(): String? = (reference() as? StringReference)?.string
}

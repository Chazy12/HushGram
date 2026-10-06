/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.direct.seen.VisualSeenHookTest.Companion.snapshot
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22x
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import app.morphe.patches.instagram.direct.seen.ChatSeenFixture as F

class ThreadSeenHookTest {
    @Test fun theOptInPatchIsExcludedFromDefaultBuilds() {
        assertFalse(readWithoutSeenReceiptPatch.default)
    }

    @Test fun aHeldChatReceiptCompletesOnceAndNothingElseChanges() {
        val input = F.classes()
        val context = PatchContexts.of(input)
        val found = context.findThreadSeen()
        assertEquals(F.MUTATION, found.mutation)
        assertEquals(F.CREATOR, found.creator.definingClass)
        val before = input.associate { it.type to snapshot(it.methods) }
        val first = input.single { it.type == F.HANDLER }.methods.single { it.name == "send" }.visualCode().first()
        context.holdBackThreadSeen()
        val patched = context.mutableClassDefBy(F.HANDLER).methods.single { it.name == "send" }
        assertThreadGuard(patched, found.complete.toString(), first)
        for (candidate in input) {
            val methods = context.mutableClassDefBy(candidate.type).methods.filter { candidate.type != F.HANDLER || it.name != "send" }
            assertEquals("${candidate.type} changed", before[candidate.type]!!.filter { candidate.type != F.HANDLER || !it.first.contains("->send(") },
                snapshot(methods))
        }
        assertEquals(ThreadTrace(completed = 1, sent = 0), traceThreadGuard(patched, true))
        assertEquals(ThreadTrace(completed = 0, sent = 1), traceThreadGuard(patched, false))
    }

    /** A handler that casts its mutation parameter in place, with no copy first, is the same shape. */
    @Test fun aHandlerThatCastsItsParameterInPlaceIsAccepted() {
        val input = replace(F.classes(), F.HANDLER, "send") {
            it[0] = typed(Opcode.CHECK_CAST, 5, F.MUTATION)
            it[1] = ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 1, 5)
        }
        val context = PatchContexts.of(input)
        val found = context.findThreadSeen()
        context.holdBackThreadSeen()
        assertThreadGuard(context.mutableClassDefBy(F.HANDLER).methods.single { it.name == "send" }, found.complete.toString(),
            typed(Opcode.CHECK_CAST, 5, F.MUTATION))
    }

    @Test fun aMissingHandlerIsRefused() = refuses(F.classes().filter { it.type != F.HANDLER })
    @Test fun duplicateHandlersAreRefused() = refuses(F.classes() + F.handler("Lfixture/SecondChatSeenHandler;"))
    @Test fun aMissingExtensionIsRefused() = refuses(F.classes().filter { it.type != THREAD_SEEN })
    @Test fun aNonPublicCallbackIsRefused() = refuses(F.classes().map {
        if (it.type == F.CALLBACK) F.callback(AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value) else it
    })
    @Test fun aCallbackThatNeverFinishesWithTwoNullsIsRefused() = changed(F.CALLBACK, "finish") {
        it[2] = call(Opcode.INVOKE_INTERFACE, listOf(1, 0, 1), F.COMPLETE)
    }
    @Test fun aCallbackWhoseNullCanBeJumpedPastIsRefused() = changed(F.CALLBACK, "finish") {
        it.add(1, ImmutableInstruction21t(Opcode.IF_EQZ, 1, 0))
        it[1] = ImmutableInstruction21t(Opcode.IF_EQZ, 1, offset(it, 1, 3))
    }
    @Test fun theWrongMutationCastIsRefused() = changed(F.HANDLER, "send") {
        it[0] = ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 1, 4)
    }
    @Test fun aHandlerThatReadsAScratchRegisterFirstIsRefused() = changed(F.HANDLER, "send") {
        it.add(0, call(Opcode.INVOKE_STATIC, listOf(0), "Lfixture/Network;", "touch", listOf(OBJECT), "V"))
    }
    @Test fun theWrongClassNameSelectorIsRefused() = changed(F.BASE, "name") {
        it[0] = ImmutableInstruction22c(Opcode.INSTANCE_OF, 0, 1, ImmutableTypeReference("Lfixture/OtherMutation;"))
    }
    @Test fun aRegistryThatSwapsItsProvidersIsRefused() = changed(F.REGISTRY, "register") {
        it[9] = call(Opcode.INVOKE_DIRECT, listOf(3, 5, 1, 4, 0), F.DESCRIPTOR, "<init>", listOf(OBJECT, F.WRAPPER, F.WRAPPER, STRING), "V")
    }
    @Test fun aRegistryThatReloadsItsProviderIsRefused() = changed(F.REGISTRY, "register") {
        it.add(2, field(Opcode.SGET_OBJECT, 0, F.OTHER_PROVIDER))
    }
    @Test fun aRegistryThatNamesAnotherMutationIsRefused() = changed(F.REGISTRY, "register") {
        it[9] = call(Opcode.INVOKE_DIRECT, listOf(3, 5, 4, 1, 2), F.DESCRIPTOR, "<init>", listOf(OBJECT, F.WRAPPER, F.WRAPPER, STRING), "V")
    }
    @Test fun aHandlerInitializerThatStoresAnotherProviderIsRefused() = changed(F.HANDLER, "<clinit>") {
        it[0] = field(Opcode.SGET_OBJECT, 0, F.OTHER_PROVIDER)
    }
    @Test fun aProviderThatReturnsAnotherObjectIsRefused() = changed(F.PROVIDER, "get") {
        it[2] = ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1)
    }
    @Test fun aSenderWithoutItsQueueKeyIsRefused() = changed(F.CREATOR, "open") {
        it[0] = text(1, "mark_other_seen-")
    }
    @Test fun aSenderThatDispatchesAnotherMutationIsRefused() = changed(F.CREATOR, "open") {
        it[3] = call(Opcode.INVOKE_VIRTUAL, listOf(3, 1), F.MANAGER, "dispatch", listOf(F.BASE), "Z")
    }
    @Test fun aSenderThatLosesItsMutationIsRefused() = changed(F.CREATOR, "open") {
        it.add(3, ImmutableInstruction11n(Opcode.CONST_4, 0, 0))
    }

    private fun changed(type: String, name: String, change: (MutableList<Instruction>) -> Unit) = refuses(replace(F.classes(), type, name, change))
    private fun refuses(input: List<ClassDef>) {
        val context = PatchContexts.of(input)
        val before = input.associate { it.type to snapshot(it.methods) }
        val refusal = assertThrows(PatchException::class.java) { context.holdBackThreadSeen() }
        assertTrue(refusal.message, refusal.message!!.startsWith("$THREAD_SEEN_PATCH: "))
        input.forEach { assertEquals("${it.type} was edited before refusal", before[it.type], snapshot(context.mutableClassDefBy(it.type).methods)) }
    }

    companion object {
        /** The guard sits first, completes through the queue's callback when held, and otherwise runs [original]. */
        internal fun assertThreadGuard(method: Method, completion: String, original: Instruction) {
            val code = method.visualCode()
            assertEquals(1, code.count { it.visualReference()?.toString() == HOLD_THREAD_SEEN })
            assertEquals(listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
                Opcode.MOVE_OBJECT_FROM16, Opcode.CONST_4, Opcode.INVOKE_INTERFACE, Opcode.RETURN_VOID), code.take(7).map { it.opcode })
            assertTrue(code[0].namedRegisters().isEmpty())
            assertEquals(HOLD_THREAD_SEEN, code[0].visualReference().toString())
            assertEquals(method.parameterRegisterNumber(1), (code[3] as TwoRegisterInstruction).registerB)
            assertEquals(completion, code[5].visualReference().toString())
            assertEquals(listOf(1, 0, 0), code[5].namedRegisters())
            assertEquals(original.opcode, code[7].opcode)
            assertEquals(original.namedRegisters(), code[7].namedRegisters())
            assertEquals(original.visualReference()?.toString(), code[7].visualReference()?.toString())
            assertEquals("off branches directly to Instagram's original first instruction", setOf(3, 7), ControlFlow.of(method).normal[2].toSet())
        }

        internal data class ThreadTrace(val completed: Int, val sent: Int)

        /** Executes the injected instructions, with the queue's callback supplied by the harness. */
        internal fun traceThreadGuard(method: Method, held: Boolean): ThreadTrace {
            val code = method.visualCode()
            val flow = ControlFlow.of(method)
            val registers = mutableMapOf<Int, Any?>()
            val callback = Any()
            registers[method.parameterRegisterNumber(1)] = callback
            var at = 0
            var completed = 0
            while (at < 7) {
                val instruction = code[at]
                when (instruction.opcode) {
                    Opcode.INVOKE_STATIC -> assertEquals(HOLD_THREAD_SEEN, instruction.visualReference().toString())
                    Opcode.MOVE_RESULT -> registers[(instruction as OneRegisterInstruction).registerA] = held
                    Opcode.IF_EQZ -> if (registers[(instruction as OneRegisterInstruction).registerA] == false) {
                        at = flow.normal[at].single { it != at + 1 }
                        continue
                    }
                    Opcode.MOVE_OBJECT_FROM16 -> (instruction as TwoRegisterInstruction).let { registers[it.registerA] = registers[it.registerB] }
                    Opcode.CONST_4 -> registers[(instruction as OneRegisterInstruction).registerA] = null
                    Opcode.INVOKE_INTERFACE -> {
                        assertEquals(listOf(callback, null, null), instruction.namedRegisters().map { registers[it] })
                        completed++
                    }
                    Opcode.RETURN_VOID -> return ThreadTrace(completed, 0)
                    else -> error("unsupported guard instruction ${instruction.opcode}")
                }
                at++
            }
            assertEquals(7, at)
            return ThreadTrace(completed, 1)
        }
    }
}

/**
 * Stand-ins for the chat receipt's queue in the shapes 450 has: the handler copies its mutation
 * before casting it, and the registry loads its provider six instructions before the name.
 */
internal object ChatSeenFixture {
    const val HANDLER = "Lfixture/ChatSeenHandler;"
    const val BASE = "Lfixture/QueuedMutation;"
    const val MUTATION = "Lfixture/ChatSeenMutation;"
    const val CALLBACK = "Lfixture/QueueCallback;"
    const val ERROR = "Lfixture/QueueError;"
    const val TASK = "Lfixture/QueueTask;"
    const val PROVIDER = "Lfixture/ChatSeenProvider;"
    const val WRAPPER = "Lfixture/ProviderWrapper;"
    const val DESCRIPTOR = "Lfixture/QueueDescriptor;"
    const val REGISTRY = "Lfixture/QueueRegistry;"
    const val MANAGER = "Lfixture/QueueManager;"
    const val CREATOR = "Lfixture/ChatOpener;"
    const val PARSER = "Lfixture/QueueParser;"
    val COMPLETE = ImmutableMethodReference(CALLBACK, "complete", listOf(ERROR, STRING), "V")
    val OTHER_PROVIDER = ImmutableFieldReference("Lfixture/OtherHandler;", "provider", PROVIDER)

    fun classes(): List<ClassDef> = listOf(
        handler(), callback(), provider(), registry(), selector(), manager(), creator(), parser(),
        clazz(THREAD_SEEN, listOf(method(THREAD_SEEN, "hold", emptyList(), "Z", 1,
            listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0)), static = true))),
    )

    /** p0 is v2; p1 the task, p2 the callback and p3 the mutation are v3 to v5. */
    fun handler(type: String = HANDLER): ClassDef = clazz(type, listOf(
        method(type, "<clinit>", emptyList(), "V", 1, listOf(
            field(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(PROVIDER, "instance", PROVIDER)),
            field(Opcode.SPUT_OBJECT, 0, ImmutableFieldReference(type, "provider", PROVIDER)),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        ), static = true),
        method(type, "send", listOf(TASK, CALLBACK, BASE), "V", 6, listOf(
            ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 1, 5), typed(Opcode.CHECK_CAST, 1, MUTATION),
            text(0, THREAD_SEEN_QUERY), text(0, THREAD_SEEN_ROOT),
            call(Opcode.INVOKE_STATIC, listOf(1, 0), "Lfixture/Network;", "enqueue", listOf(BASE, STRING), "V"),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )),
    ))

    /** The queue's callback, whose own helper finishes a task with no error and no message. */
    fun callback(flags: Int = AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value): ClassDef =
        ImmutableClassDef(CALLBACK, flags, OBJECT, null, null, null, null, listOf(
            ImmutableMethod(CALLBACK, "complete", listOf(ERROR, STRING).map { ImmutableMethodParameter(it, null, null) }, "V",
                AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null),
            method(CALLBACK, "finish", listOf(OBJECT), "V", 2, listOf(
                typed(Opcode.CHECK_CAST, 1, CALLBACK), ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                call(Opcode.INVOKE_INTERFACE, listOf(1, 0, 0), COMPLETE), ImmutableInstruction10x(Opcode.RETURN_VOID),
            ), static = true),
        ))

    fun provider(): ClassDef = clazz(PROVIDER, listOf(method(PROVIDER, "get", listOf(USER_SESSION), OBJECT, 3, listOf(
        typed(Opcode.NEW_INSTANCE, 0, HANDLER),
        call(Opcode.INVOKE_DIRECT, listOf(0), OBJECT, "<init>", emptyList(), "V"),
        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
    ))))

    fun registry(): ClassDef = clazz(REGISTRY, listOf(method(REGISTRY, "register", emptyList(), "V", 8, listOf(
        field(Opcode.SGET_OBJECT, 5, ImmutableFieldReference("Lfixture/QueueConfig;", "instance", OBJECT)),
        field(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(HANDLER, "provider", PROVIDER)),
        typed(Opcode.NEW_INSTANCE, 4, WRAPPER),
        call(Opcode.INVOKE_DIRECT, listOf(4, 0), WRAPPER, "<init>", listOf(PROVIDER), "V"),
        field(Opcode.SGET_OBJECT, 2, OTHER_PROVIDER),
        typed(Opcode.NEW_INSTANCE, 1, WRAPPER),
        call(Opcode.INVOKE_DIRECT, listOf(1, 2), WRAPPER, "<init>", listOf(PROVIDER), "V"),
        text(0, THREAD_SEEN_MUTATION),
        typed(Opcode.NEW_INSTANCE, 3, DESCRIPTOR),
        call(Opcode.INVOKE_DIRECT, listOf(3, 5, 4, 1, 0), DESCRIPTOR, "<init>", listOf(OBJECT, WRAPPER, WRAPPER, STRING), "V"),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    ))))

    fun selector(): ClassDef {
        val code = mutableListOf<Instruction>(
            ImmutableInstruction22c(Opcode.INSTANCE_OF, 0, 1, ImmutableTypeReference(MUTATION)),
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 0), text(0, THREAD_SEEN_MUTATION), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            text(0, "send_other_marker"), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )
        code[1] = ImmutableInstruction21t(Opcode.IF_EQZ, 0, offset(code, 1, 4))
        return clazz(BASE, listOf(method(BASE, "name", emptyList(), STRING, 2, code)))
    }

    fun manager(): ClassDef = clazz(MANAGER, listOf(method(MANAGER, "dispatch", listOf(BASE), "Z", 3, listOf(
        text(0, DISPATCH_ANCHOR), ImmutableInstruction11n(Opcode.CONST_4, 0, 1), ImmutableInstruction11x(Opcode.RETURN, 0),
    ))))

    /** p0 is v2 and the manager v3. */
    fun creator(): ClassDef = clazz(CREATOR, listOf(method(CREATOR, "open", listOf(MANAGER), "V", 4, listOf(
        text(1, THREAD_SEEN_KEY), typed(Opcode.NEW_INSTANCE, 0, MUTATION),
        call(Opcode.INVOKE_DIRECT, listOf(0), BASE, "<init>", emptyList(), "V"),
        call(Opcode.INVOKE_VIRTUAL, listOf(3, 0), MANAGER, "dispatch", listOf(BASE), "Z"), ImmutableInstruction10x(Opcode.RETURN_VOID),
    ))))

    /** Restoring the persisted queue allocates the mutation too, without sending it. */
    fun parser(): ClassDef = clazz(PARSER, listOf(method(PARSER, "parse", emptyList(), OBJECT, 2, listOf(
        typed(Opcode.NEW_INSTANCE, 0, MUTATION), call(Opcode.INVOKE_DIRECT, listOf(0), BASE, "<init>", emptyList(), "V"),
        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
    ))))
}

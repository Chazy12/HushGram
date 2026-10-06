/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.direct.seen.ThreadSeenHookTest.Companion.ThreadTrace
import app.morphe.patches.instagram.direct.seen.ThreadSeenHookTest.Companion.assertThreadGuard
import app.morphe.patches.instagram.direct.seen.ThreadSeenHookTest.Companion.traceThreadGuard
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
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import app.morphe.patches.instagram.direct.seen.ChatSeenFixture as F
import app.morphe.patches.instagram.direct.seen.MarkReadFixture as M

class MarkReadHookTest {
    @Test fun markAsReadIsOfferedAndHandledFirstAndNothingElseChanges() {
        val input = M.classes()
        val context = PatchContexts.of(input)
        val seen = context.findThreadSeen()
        val found = context.findMarkRead(seen)
        assertEquals(M.READ.toString(), found.markAsRead.toString())
        assertEquals(M.ROWS, found.builder.definingClass)
        assertEquals(1, found.rows)
        assertEquals(M.ACTIONS, found.action.definingClass)
        assertEquals(listOf(0, 1, 2), listOf(found.chosen, found.thread, found.key))
        assertEquals(M.SESSION.toString(), found.session.toString())
        assertEquals(7, found.bridges.size)

        val before = input.associate { it.type to snapshot(it.methods) }
        val original = input.associate { it.type to it.methods.associate { method -> method.name to method.visualCode() } }
        val first = original.getValue(F.HANDLER).getValue("send").first()
        context.readWithoutSeenReceipt()

        val handler = context.mutableClassDefBy(F.HANDLER).methods.single { it.name == "send" }
        assertThreadGuard(handler, F.COMPLETE.toString(), first)
        assertEquals(ThreadTrace(completed = 1, sent = 0), traceThreadGuard(handler, true))
        assertEquals(ThreadTrace(completed = 0, sent = 1), traceThreadGuard(handler, false))

        val rows = context.mutableClassDefBy(M.ROWS).methods.single { it.name == "rows" }
        assertRowOffer(rows, 1, M.READ.toString(), original.getValue(M.ROWS).getValue("rows"))

        val act = context.mutableClassDefBy(M.ACTIONS).methods.single { it.name == "act" }
        assertTapGuard(act, M.READ.toString(), M.SESSION.toString(), original.getValue(M.ACTIONS).getValue("act"))
        assertEquals(TapTrace(listOf(CHOSEN, M.READ.toString(), SESSION, CHAT, KEY), handled = true), traceTapGuard(act, 0, 1, 2, true))
        assertEquals(TapTrace(listOf(CHOSEN, M.READ.toString(), SESSION, CHAT, KEY), handled = false), traceTapGuard(act, 0, 1, 2, false))

        val bridges = context.mutableClassDefBy(INSTAGRAM_CHATS).methods.associateBy { it.name }
        assertBridge(bridges, "threadId", listOf(Opcode.CHECK_CAST to THREAD_KEY, Opcode.IGET_OBJECT to M.THREAD_ID.toString(), Opcode.RETURN_OBJECT to null))
        assertBridge(bridges, "receiptKey", listOf(Opcode.CHECK_CAST to F.MUTATION, Opcode.INVOKE_VIRTUAL to M.RECEIPT_KEY.toString(),
            Opcode.MOVE_RESULT_OBJECT to null, Opcode.RETURN_OBJECT to null))
        assertBridge(bridges, "lastMessage", listOf(Opcode.CHECK_CAST to M.CHAT, Opcode.INVOKE_STATIC to M.LAST.toString(),
            Opcode.MOVE_RESULT_OBJECT to null, Opcode.RETURN_OBJECT to null))
        assertBridge(bridges, "messageId", listOf(Opcode.CHECK_CAST to M.MESSAGE, Opcode.INVOKE_VIRTUAL to M.MESSAGE_ID.toString(),
            Opcode.MOVE_RESULT_OBJECT to null, Opcode.RETURN_OBJECT to null))
        assertBridge(bridges, "senderId", listOf(Opcode.CHECK_CAST to M.MESSAGE, Opcode.IGET_OBJECT to M.SENDER_ID.toString(), Opcode.RETURN_OBJECT to null))
        assertBridge(bridges, "sendSeen", listOf(Opcode.CHECK_CAST to USER_SESSION, Opcode.CHECK_CAST to M.DETAIL,
            Opcode.INVOKE_STATIC to M.SEND.toString(), Opcode.RETURN_VOID to null))
        assertBridge(bridges, "markUnread", listOf(Opcode.CHECK_CAST to USER_SESSION, Opcode.CHECK_CAST to THREAD_KEY,
            Opcode.INVOKE_STATIC to M.UNREAD_CALL.toString(), Opcode.RETURN_VOID to null))
        assertEquals("each argument goes to Instagram's sender in its place", listOf(0, 1, 2, 3, 4), bridges.getValue("sendSeen").visualCode()[2].namedRegisters())
        assertEquals(listOf(0, 1, 2), bridges.getValue("markUnread").visualCode()[2].namedRegisters())

        val hooked = setOf("${F.HANDLER}->send(", "${M.ROWS}->rows(", "${M.ACTIONS}->act(")
        for (candidate in input) {
            val now = snapshot(context.mutableClassDefBy(candidate.type).methods)
            if (candidate.type == INSTAGRAM_CHATS) continue
            assertEquals("${candidate.type} changed", before.getValue(candidate.type).filterNot { (name, _) -> hooked.any(name::startsWith) },
                now.filterNot { (name, _) -> hooked.any(name::startsWith) })
        }
        assertEquals("one guard each", 1, context.callsTo(HOLD_THREAD_SEEN, input))
        assertEquals(1, context.callsTo(OFFER_MARK_READ, input))
        assertEquals(1, context.callsTo(MARK_READ, input))
    }

    @Test fun aMenuWithoutMarkAsReadIsRefused() = changed(M.MENU, "<clinit>", "$MENU_REFUSAL, found none") { it[6] = text(2, "MARK_AS_SEEN") }
    @Test fun aSecondMenuNamingBothIsRefused() = refuses(M.classes() + M.menu("Lfixture/OtherChatMenuAction;"), "$MENU_REFUSAL, found L")
    @Test fun markAsReadBuiltWithAnotherNameIsRefused() = changed(M.MENU, "<clinit>", "MARK_AS_READ isn't the name its constant is built with") {
        it[9] = call(Opcode.INVOKE_DIRECT, listOf(0, 1, 2), M.MENU, "<init>", listOf(STRING, "I"), "V")
    }
    @Test fun markAsReadStoredFromAnotherObjectIsRefused() = changed(M.MENU, "<clinit>", "MARK_AS_READ stores another object than it builds") {
        it[10] = field(Opcode.SPUT_OBJECT, 1, M.READ)
    }
    @Test fun markAsUnreadBuiltOnAnotherObjectIsRefused() = changed(M.MENU, "<clinit>", "MARK_AS_UNREAD is built on another object than it stores") {
        it[3] = ImmutableInstruction12x(Opcode.MOVE_OBJECT, 0, 1)
    }

    @Test fun aBuilderWithoutMarkAsUnreadIsRefused() = changed(M.ROWS, "rows", "$BUILDER_REFUSAL, found 0") { it[1] = field(Opcode.SGET_OBJECT, 0, M.READ) }
    @Test fun aBuilderAddingToAnotherListIsRefused() = changed(M.ROWS, "rows", "$BUILDER_REFUSAL, found 0") {
        it[2] = call(Opcode.INVOKE_INTERFACE, listOf(1, 0), M.LIST, "add", listOf(OBJECT), "Z")
    }
    @Test fun aSecondBuilderOfferingMarkAsUnreadIsRefused() = refuses(M.classes() + M.rows("Lfixture/OtherChatMenuRows;"), "$BUILDER_REFUSAL, found 2")
    @Test fun aBuilderThatReadsAScratchRegisterFirstIsRefused() = changed(M.ROWS, "rows", "${M.ROWS}->rows still reads v0 after instruction 0") {
        it.add(0, call(Opcode.INVOKE_STATIC, listOf(0), "Lfixture/Network;", "touch", listOf(OBJECT), "V"))
    }
    @Test fun aBuilderEnteredByAJumpIsRefused() = changed(M.ROWS, "rows", "a jump enters the chat menu builder at its first instruction") {
        it.add(3, ImmutableInstruction10t(Opcode.GOTO, 0))
        it[3] = ImmutableInstruction10t(Opcode.GOTO, offset(it, 3, 0))
        it[0] = ImmutableInstruction21t(Opcode.IF_NEZ, 3, offset(it, 0, 4))
    }

    @Test fun aMissingTapHandlerIsRefused() = refuses(M.classes().filter { it.type != M.ACTIONS }, "$HANDLER_REFUSAL, found 0")
    @Test fun aSecondTapHandlerIsRefused() =
        refuses(M.classes().map { if (it.type == M.ACTIONS) M.actions(second = true) else it }, "$HANDLER_REFUSAL, found 2")
    @Test fun aTapHandlerWithoutAChatKeyIsRefused() = refuses(M.classes().map {
        if (it.type == M.ACTIONS) M.actions(parameters = listOf(M.MENU, M.INBOX_CHAT, OBJECT)) else it
    }, "chat menu action handler doesn't take one chat key")
    @Test fun aTapHandlerWithoutTheChatIsRefused() = refuses(M.classes().map {
        if (it.type == M.ACTIONS) M.actions(parameters = listOf(M.MENU, OBJECT, THREAD_KEY)) else it
    }, "expected one chat the menu action handler is given, found 0")
    @Test fun aTapHandlerThatNeverTakesTheUnreadMarkOffIsRefused() =
        changed(M.ACTIONS, "act", "chat menu action handler never takes a chat's unread mark through Instagram's call") {
            it[2] = call(Opcode.INVOKE_STATIC, listOf(1, 8, 0), M.SENDER, "pin", listOf(USER_SESSION, THREAD_KEY, "Z"), "V")
        }
    @Test fun aTapHandlerKeepingTwoAccountsIsRefused() = refuses(M.classes().map { if (it.type == M.ACTIONS) M.actions(sessions = 2) else it },
        "expected one account the chat menu action handler keeps, found 2")
    @Test fun aTapHandlerThatReadsAScratchRegisterFirstIsRefused() = changed(M.ACTIONS, "act", "${M.ACTIONS}->act still reads v4 after instruction 0") {
        it.add(0, call(Opcode.INVOKE_STATIC, listOf(4), "Lfixture/Network;", "touch", listOf(OBJECT), "V"))
    }

    @Test fun aMissingMarkReadRouteIsRefused() = refuses(M.classes().filter { it.type != M.ROUTE }, "$ROUTE_REFUSAL, found none")
    @Test fun aSecondMarkReadRouteIsRefused() = refuses(M.classes() + M.route("Lfixture/OtherMarkReadRequests;"), "$ROUTE_REFUSAL, found L")
    @Test fun aRouteThatSendsTheIdsSwappedIsRefused() = changed(M.ROUTE, "handle", PICK_REFUSAL) {
        it[12] = call(Opcode.INVOKE_STATIC, listOf(7, 0, 8, 2, 5), M.SEND)
    }
    @Test fun aRouteThatLosesItsMessageIsRefused() = changed(M.ROUTE, "handle", PICK_REFUSAL) { it.add(8, ImmutableInstruction11n(Opcode.CONST_4, 4, 0)) }
    @Test fun aRouteThatMarksTheChatUnreadIsRefused() =
        changed(M.ROUTE, "handle", "Instagram's handler for marking a chat read doesn't hand its unread call false") {
            it[13] = ImmutableInstruction11n(Opcode.CONST_4, 0, 1)
        }
    @Test fun aRouteThatNeverSendsTheReceiptIsRefused() =
        changed(M.ROUTE, "handle", "expected one receipt sent by Instagram's handler for marking a chat read, found 0") {
            it[12] = call(Opcode.INVOKE_STATIC, listOf(7, 0, 8, 5, 2), M.SENDER, "log", listOf(USER_SESSION, M.DETAIL, STRING, STRING, STRING), "V")
        }

    @Test fun aKeyWithoutItsThreadIdLabelIsRefused() = changed(THREAD_KEY, "toString", "expected one chat key's thread id label, found 0") {
        it[2] = text(2, "DirectThreadKey{id='")
    }
    @Test fun aKeyThatSpellsSomethingElseAfterItsLabelIsRefused() =
        changed(THREAD_KEY, "toString", "chat key's toString doesn't write its own thread id after the label") {
            it[5] = call(Opcode.INVOKE_STATIC, listOf(2, 1, 4, 3, 0), M.TEXT, "join", List(5) { STRING }, STRING)
        }
    @Test fun aReceiptWithoutItsChatKeyIsRefused() =
        refuses(M.classes().map { if (it.type == F.MUTATION) M.mutation(withKey = false) else it }, "expected one receipt's chat key, found 0")
    @Test fun aMessageTheExtensionCantReachIsRefused() = refuses(M.classes().map { if (it.type == M.MESSAGE) M.message(AccessFlags.FINAL.value) else it },
        "${M.MESSAGE_ID} isn't public, so the extension can't reach it")
    @Test fun anUnreadCallTheExtensionCantReachIsRefused() =
        refuses(M.classes().map { if (it.type == M.SENDER) M.sender(unreadFlags = AccessFlags.STATIC.value) else it },
            "${M.UNREAD_CALL} isn't public, so the extension can't reach it")

    /** The hold isn't written either when Mark as read can't be, so a build gets both or neither. */
    @Test fun anInstanceSenderLeavesTheHoldUnwrittenToo() = refuses(M.classes().filter { it.type != M.SENDER } + F.creator() + M.sender(withSend = false),
        "live receipt sender doesn't take an account, a detail and three ids")
    @Test fun anExtensionWithoutTheTapHookIsRefused() = refuses(M.classes().map { if (it.type == THREAD_SEEN) M.extension(markRead = false) else it },
        "expected one extension's public static markRead, found 0")
    @Test fun anExtensionWithoutTheRowHookIsRefused() = refuses(M.classes().map { if (it.type == THREAD_SEEN) M.extension(offer = false) else it },
        "expected one extension's public static offerMarkRead, found 0")
    @Test fun aMissingBridgeIsRefused() = refuses(M.classes().map { if (it.type == INSTAGRAM_CHATS) M.bridges(without = "senderId") else it },
        "expected one extension's chat bridge senderId, found 0")
    @Test fun aMissingBridgeClassIsRefused() = refuses(M.classes().filter { it.type != INSTAGRAM_CHATS }, "extension has no $INSTAGRAM_CHATS")

    private fun changed(type: String, name: String, why: String, change: (MutableList<Instruction>) -> Unit) =
        refuses(M.edit(M.classes(), type, name, change), why)

    /** [input] is refused for [why], with nothing in it edited first. */
    private fun refuses(input: List<ClassDef>, why: String) {
        val context = PatchContexts.of(input)
        val before = input.associate { it.type to snapshot(it.methods) }
        val refusal = assertThrows(PatchException::class.java) { context.readWithoutSeenReceipt() }
        assertTrue(refusal.message, refusal.message!!.startsWith("$THREAD_SEEN_PATCH: "))
        assertTrue("refused for something other than \"$why\": ${refusal.message}", why in refusal.message!!)
        input.forEach { assertEquals("${it.type} was edited before refusal", before[it.type], snapshot(context.mutableClassDefBy(it.type).methods)) }
    }

    private fun app.morphe.patcher.patch.BytecodePatchContext.callsTo(reference: String, input: List<ClassDef>) =
        input.sumOf { candidate -> mutableClassDefBy(candidate.type).methods.sumOf { method -> method.visualCode().count { it.visualReference()?.toString() == reference } } }

    companion object {
        private const val MENU_REFUSAL = "expected exactly one chat menu action enum in this Instagram build"
        private const val BUILDER_REFUSAL = "expected one chat menu builder offering Mark as unread"
        private const val HANDLER_REFUSAL = "expected one chat menu action handler"
        private const val ROUTE_REFUSAL = "expected exactly one Instagram handler for marking a chat read in this Instagram build"
        private const val PICK_REFUSAL = "expected one message Instagram's handler for marking a chat read sends its receipt for, found 0"

        /** Stand-ins the tap trace hands the guard. */
        internal const val CHOSEN = "chosen row"
        internal const val SESSION = "account"
        internal const val CHAT = "chat"
        internal const val KEY = "chat key"

        /** The builder starts by offering Mark as read on its own list, then runs [original] unchanged. */
        internal fun assertRowOffer(method: Method, rows: Int, markAsRead: String, original: List<Instruction>) {
            val code = method.visualCode()
            assertEquals(listOf(Opcode.SGET_OBJECT, Opcode.INVOKE_STATIC), code.take(2).map { it.opcode })
            assertEquals(markAsRead, code[0].visualReference().toString())
            assertEquals(OFFER_MARK_READ, code[1].visualReference().toString())
            assertEquals(listOf(method.parameterRegisterNumber(rows), (code[0] as OneRegisterInstruction).registerA), code[1].namedRegisters())
            assertEquals(1, code.count { it.visualReference()?.toString() == OFFER_MARK_READ })
            assertEquals(shape(original), shape(code.drop(2)))
        }

        /** The tap handler starts with the guard, which returns when the extension handled the tap, then runs [original] unchanged. */
        internal fun assertTapGuard(method: Method, markAsRead: String, session: String, original: List<Instruction>) {
            val code = method.visualCode()
            assertEquals(listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.SGET_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT,
                Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
                Opcode.RETURN_VOID), code.take(10).map { it.opcode })
            assertEquals(markAsRead, code[1].visualReference().toString())
            assertEquals(session, code[3].visualReference().toString())
            assertEquals(MARK_READ, code[6].visualReference().toString())
            assertEquals(listOf(0, 1, 2, 3, 4), code[6].namedRegisters())
            assertEquals(1, code.count { it.visualReference()?.toString() == MARK_READ })
            assertEquals("not handled goes straight to Instagram's first instruction", setOf(9, 10), ControlFlow.of(method).normal[8].toSet())
            assertEquals(shape(original), shape(code.drop(10)))
        }

        internal data class TapTrace(val arguments: List<Any?>, val handled: Boolean)

        /**
         * Runs the guard with stand-ins in `this` and the row, chat and key parameters at [chosen],
         * [thread] and [key], and the extension answering [handled].
         */
        internal fun traceTapGuard(method: Method, chosen: Int, thread: Int, key: Int, handled: Boolean): TapTrace {
            val code = method.visualCode()
            val flow = ControlFlow.of(method)
            val self = Any()
            val registers = mutableMapOf<Int, Any?>(
                method.parameterRegisterNumber(0) - 1 to self,
                method.parameterRegisterNumber(chosen) to CHOSEN,
                method.parameterRegisterNumber(thread) to CHAT,
                method.parameterRegisterNumber(key) to KEY,
            )
            var arguments: List<Any?> = emptyList()
            var at = 0
            while (at < 10) {
                val instruction = code[at]
                when (instruction.opcode) {
                    Opcode.MOVE_OBJECT_FROM16 -> (instruction as TwoRegisterInstruction).let { registers[it.registerA] = registers[it.registerB] }
                    Opcode.SGET_OBJECT -> registers[(instruction as OneRegisterInstruction).registerA] = instruction.visualReference().toString()
                    Opcode.IGET_OBJECT -> (instruction as TwoRegisterInstruction).let {
                        assertEquals("the account comes from the handler itself", self, registers[it.registerB])
                        registers[it.registerA] = SESSION
                    }
                    Opcode.INVOKE_STATIC -> {
                        assertEquals(MARK_READ, instruction.visualReference().toString())
                        arguments = instruction.namedRegisters().map { registers[it] }
                    }
                    Opcode.MOVE_RESULT -> registers[(instruction as OneRegisterInstruction).registerA] = handled
                    Opcode.IF_EQZ -> if (registers[(instruction as OneRegisterInstruction).registerA] == false) {
                        at = flow.normal[at].single { it != at + 1 }
                        continue
                    }
                    Opcode.RETURN_VOID -> return TapTrace(arguments, true)
                    else -> error("unsupported guard instruction ${instruction.opcode}")
                }
                at++
            }
            assertEquals(10, at)
            return TapTrace(arguments, false)
        }

        /** The bridge's body is [body], ahead of the stub it replaces. */
        internal fun assertBridge(bridges: Map<String, Method>, name: String, body: List<Pair<Opcode, String?>>) {
            val code = bridges.getValue(name).visualCode()
            assertEquals(name, body, code.take(body.size).map { it.opcode to it.visualReference()?.toString() })
        }

        private val PAYLOADS = setOf(Opcode.PACKED_SWITCH_PAYLOAD, Opcode.SPARSE_SWITCH_PAYLOAD, Opcode.ARRAY_PAYLOAD)

        /**
         * Each instruction's opcode, reference and registers. A nop just ahead of a payload only aligns
         * it, and the assembler adds or drops one whenever code ahead of the payload changes length.
         */
        private fun shape(code: List<Instruction>) = code
            .filterIndexed { at, instruction -> instruction.opcode != Opcode.NOP || code.getOrNull(at + 1)?.opcode !in PAYLOADS }
            .map { Triple(it.opcode, it.visualReference()?.toString(), it.namedRegisters()) }
    }
}

/**
 * Stand-ins for a chat's long press menu in the shapes 450 has: the enum naming both read actions,
 * the builder that offers Mark as unread on one chat, the handler a tap on the menu runs, Instagram's
 * own handler for marking a chat read, and the extension's hooks and bridges. The chat receipt's
 * fixture comes along, with its live sender the static one 450 has.
 */
internal object MarkReadFixture {
    const val MENU = "Lfixture/ChatMenuAction;"
    const val ROWS = "Lfixture/ChatMenuRows;"
    const val ACTIONS = "Lfixture/ChatMenuActions;"
    const val CHAT = "Lfixture/Chat;"
    const val INBOX_CHAT = "Lfixture/InboxChat;"
    const val MESSAGES = "Lfixture/Messages;"
    const val MESSAGE = "Lfixture/Message;"
    const val DETAIL = "Lfixture/SeenDetail;"
    const val SENDER = "Lfixture/ChatSeenSender;"
    const val ROUTE = "Lfixture/MarkReadRequests;"
    const val THREADS = "Lfixture/ThreadStore;"
    const val TEXT = "Lfixture/Text;"
    const val LIST = "Ljava/util/List;"
    private const val ENUM = "Ljava/lang/Enum;"

    val READ = ImmutableFieldReference(MENU, "read", MENU)
    val UNREAD = ImmutableFieldReference(MENU, "unread", MENU)
    val SESSION = ImmutableFieldReference(ACTIONS, "session", USER_SESSION)
    val THREAD_ID = ImmutableFieldReference(THREAD_KEY, "id", STRING)
    val OTHER_TEXT = ImmutableFieldReference(THREAD_KEY, "v2", STRING)
    val SENDER_ID = ImmutableFieldReference(MESSAGE, "sender", STRING)
    private val MANAGER = ImmutableFieldReference(F.MANAGER, "instance", F.MANAGER)
    val SEND = ImmutableMethodReference(SENDER, "send", listOf(USER_SESSION, DETAIL, STRING, STRING, STRING), "V")
    val UNREAD_CALL = ImmutableMethodReference(SENDER, "unread", listOf(USER_SESSION, THREAD_KEY, "Z"), "V")
    val LAST = ImmutableMethodReference(MESSAGES, "last", listOf(CHAT), MESSAGE)
    val MESSAGE_ID = ImmutableMethodReference(MESSAGE, "id", emptyList(), STRING)
    val RECEIPT_KEY = ImmutableMethodReference(F.MUTATION, "key", emptyList(), THREAD_KEY)

    private const val PUBLIC = 0x1
    private val INTERFACE = AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value

    fun classes(): List<ClassDef> = F.classes().filter { it.type != F.CREATOR && it.type != THREAD_SEEN } + listOf(
        sender(), mutation(), menu(), rows(), actions(), chat(), inboxChat(), messages(), message(), detail(), key(), route(), extension(), bridges(),
    )

    /** The receipt's live sender, static as 450's is, and the call that takes a chat's unread mark off beside it. */
    fun sender(withSend: Boolean = true, unreadFlags: Int = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value): ClassDef = clazz(SENDER, listOfNotNull(
        method(SENDER, "send", listOf(USER_SESSION, DETAIL, STRING, STRING, STRING), "V", 9, listOf(
            text(1, THREAD_SEEN_KEY), typed(Opcode.NEW_INSTANCE, 0, F.MUTATION),
            call(Opcode.INVOKE_DIRECT, listOf(0), F.BASE, "<init>", emptyList(), "V"),
            field(Opcode.SGET_OBJECT, 3, MANAGER),
            call(Opcode.INVOKE_VIRTUAL, listOf(3, 0), F.MANAGER, "dispatch", listOf(F.BASE), "Z"),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        ), static = true).takeIf { withSend },
        ImmutableMethod(SENDER, "unread", listOf(USER_SESSION, THREAD_KEY, "Z").map(::parameter), "V", unreadFlags, null, null,
            ImmutableMethodImplementation(3, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null)),
    ))

    fun mutation(withKey: Boolean = true): ClassDef = ImmutableClassDef(F.MUTATION, PUBLIC, F.BASE, null, null, null, null, listOfNotNull(
        method(F.MUTATION, "key", emptyList(), THREAD_KEY, 2, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))).takeIf { withKey },
    ))

    /** Mark as unread is copied before it's built and Mark as read isn't, as 450 builds the two. */
    fun menu(type: String = MENU): ClassDef {
        val read = ImmutableFieldReference(type, READ.name, type)
        val unread = ImmutableFieldReference(type, UNREAD.name, type)
        val constant = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value, ENUM, null, null, null,
            listOf(read, unread).map { ImmutableField(type, it.name, type, constant, null, null, null) },
            listOf(method(type, "<clinit>", emptyList(), "V", 4, listOf(
                text(2, MARK_AS_UNREAD), ImmutableInstruction11n(Opcode.CONST_4, 1, 0), typed(Opcode.NEW_INSTANCE, 3, type),
                ImmutableInstruction12x(Opcode.MOVE_OBJECT, 0, 3),
                call(Opcode.INVOKE_DIRECT, listOf(0, 2, 1), type, "<init>", listOf(STRING, "I"), "V"),
                field(Opcode.SPUT_OBJECT, 3, unread),
                text(2, MARK_AS_READ), ImmutableInstruction11n(Opcode.CONST_4, 1, 1), typed(Opcode.NEW_INSTANCE, 0, type),
                call(Opcode.INVOKE_DIRECT, listOf(0, 2, 1), type, "<init>", listOf(STRING, "I"), "V"),
                field(Opcode.SPUT_OBJECT, 0, read),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ), static = true)))
    }

    /** v0 is the one local; the account, the rows and the flag are v1 to v3. */
    fun rows(type: String = ROWS): ClassDef {
        val code = mutableListOf<Instruction>(
            ImmutableInstruction21t(Opcode.IF_NEZ, 3, 0), field(Opcode.SGET_OBJECT, 0, UNREAD),
            call(Opcode.INVOKE_INTERFACE, listOf(2, 0), LIST, "add", listOf(OBJECT), "Z"), ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        code[0] = ImmutableInstruction21t(Opcode.IF_NEZ, 3, offset(code, 0, 3))
        return clazz(type, listOf(method(type, "rows", listOf(USER_SESSION, LIST, "Z"), "V", 4, code, static = true)))
    }

    /** v0 to v4 are locals, this is v5, and the row, the chat and its key are v6 to v8. */
    fun actions(parameters: List<String> = listOf(MENU, INBOX_CHAT, THREAD_KEY), second: Boolean = false, sessions: Int = 1): ClassDef {
        fun act(name: String) = method(ACTIONS, name, parameters, "V", 9, listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 5, SESSION),
            call(Opcode.INVOKE_STATIC, listOf(1, 8, 0), UNREAD_CALL), ImmutableInstruction10x(Opcode.RETURN_VOID),
        ))
        val fields = (0 until sessions).map { ImmutableField(ACTIONS, if (it == 0) SESSION.name else "account$it", USER_SESSION,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null) }
        return ImmutableClassDef(ACTIONS, PUBLIC, OBJECT, null, null, null, fields, listOfNotNull(act("act"), act("again").takeIf { second }))
    }

    fun chat(): ClassDef = ImmutableClassDef(CHAT, INTERFACE, OBJECT, null, null, null, null, null)
    fun inboxChat(): ClassDef = ImmutableClassDef(INBOX_CHAT, INTERFACE, OBJECT, listOf(CHAT), null, null, null, null)

    fun messages(): ClassDef = clazz(MESSAGES, listOf(method(MESSAGES, "last", listOf(CHAT), MESSAGE, 2, listOf(
        ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), static = true)))

    fun message(flags: Int = PUBLIC): ClassDef = ImmutableClassDef(MESSAGE, flags, OBJECT, null, null, null,
        listOf(ImmutableField(MESSAGE, SENDER_ID.name, STRING, AccessFlags.PUBLIC.value, null, null, null)),
        listOf(method(MESSAGE, "id", emptyList(), STRING, 2, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)))))

    fun detail(): ClassDef = ImmutableClassDef(DETAIL, PUBLIC or AccessFlags.FINAL.value, OBJECT, null, null, null, null, null)

    /** this is v5: the thread id and its second id are read into v4 and v3, and spelled after their labels. */
    fun key(): ClassDef = ImmutableClassDef(THREAD_KEY, PUBLIC or AccessFlags.FINAL.value, OBJECT, null, null, null,
        listOf(THREAD_ID, OTHER_TEXT).map { ImmutableField(THREAD_KEY, it.name, STRING, AccessFlags.PUBLIC.value, null, null, null) },
        listOf(method(THREAD_KEY, "toString", emptyList(), STRING, 6, listOf(
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 4, 5, THREAD_ID), ImmutableInstruction22c(Opcode.IGET_OBJECT, 3, 5, OTHER_TEXT),
            text(2, THREAD_KEY_TEXT), text(1, "', mThreadV2Id='"), text(0, "}"),
            call(Opcode.INVOKE_STATIC, listOf(2, 4, 1, 3, 0), TEXT, "join", List(5) { STRING }, STRING),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        ))))

    /** Instagram's own handler for marking a chat read. v0 to v5 are locals, this is v6, the account v7 and the thread id v8. */
    fun route(type: String = ROUTE): ClassDef = clazz(type, listOf(method(type, "handle", listOf(USER_SESSION, STRING), STRING, 9, listOf(
        text(0, MARK_READ_HANDLER), typed(Opcode.NEW_INSTANCE, 1, THREAD_KEY), ImmutableInstruction11n(Opcode.CONST_4, 2, 0),
        call(Opcode.INVOKE_DIRECT, listOf(1, 8, 2), THREAD_KEY, "<init>", listOf(STRING, LIST), "V"),
        call(Opcode.INVOKE_STATIC, listOf(1, 2), THREADS, "find", listOf(THREAD_KEY, OBJECT), INBOX_CHAT),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3),
        call(Opcode.INVOKE_STATIC, listOf(3), LAST), ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 4),
        call(Opcode.INVOKE_VIRTUAL, listOf(4), MESSAGE_ID), ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 5),
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 2, 4, SENDER_ID),
        ImmutableInstruction11n(Opcode.CONST_4, 0, 0), call(Opcode.INVOKE_STATIC, listOf(7, 0, 8, 5, 2), SEND),
        ImmutableInstruction11n(Opcode.CONST_4, 0, 0), call(Opcode.INVOKE_STATIC, listOf(7, 1, 0), UNREAD_CALL),
        text(0, MARK_READ_DONE), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
    ))))

    fun extension(offer: Boolean = true, markRead: Boolean = true): ClassDef = clazz(THREAD_SEEN, listOfNotNull(
        method(THREAD_SEEN, "hold", listOf(OBJECT), "Z", 2, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0)), static = true),
        method(THREAD_SEEN, "offerMarkRead", listOf(LIST, OBJECT), "V", 2, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), static = true)
            .takeIf { offer },
        method(THREAD_SEEN, "markRead", List(5) { OBJECT }, "Z", 6, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0)), static = true).takeIf { markRead },
    ))

    /** The extension's bridge stubs, as javac writes them: null back, or nothing done. */
    fun bridges(without: String? = null): ClassDef = clazz(INSTAGRAM_CHATS, listOf(
        "threadId" to (listOf(OBJECT) to STRING), "receiptKey" to (listOf(OBJECT) to OBJECT), "lastMessage" to (listOf(OBJECT) to OBJECT),
        "messageId" to (listOf(OBJECT) to STRING), "senderId" to (listOf(OBJECT) to STRING),
        "sendSeen" to (listOf(OBJECT, OBJECT, STRING, STRING, STRING) to "V"), "markUnread" to (listOf(OBJECT, OBJECT, "Z") to "V"),
    ).filter { it.first != without }.map { (name, shape) ->
        val (parameters, returns) = shape
        if (returns == "V") {
            method(INSTAGRAM_CHATS, name, parameters, returns, parameters.size, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), static = true)
        } else {
            method(INSTAGRAM_CHATS, name, parameters, returns, parameters.size + 1, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), static = true)
        }
    })

    /** [replace] that keeps the class's own flags, superclass, interfaces and fields. */
    fun edit(classes: List<ClassDef>, type: String, name: String, change: (MutableList<Instruction>) -> Unit): List<ClassDef> = classes.map { candidate ->
        if (candidate.type != type) candidate else ImmutableClassDef(candidate.type, candidate.accessFlags, candidate.superclass,
            candidate.interfaces, candidate.sourceFile, candidate.annotations, candidate.fields, candidate.methods.map { old ->
                if (old.name != name) old else old.visualCode().toMutableList().let { code ->
                    change(code)
                    ImmutableMethod(old.definingClass, old.name, old.parameters, old.returnType, old.accessFlags, old.annotations,
                        old.hiddenApiRestrictions, ImmutableMethodImplementation(old.implementation!!.registerCount, code, null, null))
                }
            })
    }

    private fun parameter(type: String) = com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter(type, null, null)
}

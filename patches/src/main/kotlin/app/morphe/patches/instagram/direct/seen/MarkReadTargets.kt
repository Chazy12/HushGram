/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireFreeAt
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val OFFER_MARK_READ = "$THREAD_SEEN->offerMarkRead(Ljava/util/List;Ljava/lang/Object;)V"
internal const val MARK_READ = "$THREAD_SEEN->markRead(" +
    "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"

/** The extension's bridges to Instagram's chats, whose bodies [markReadByHand] writes. */
internal const val INSTAGRAM_CHATS = "$EXTENSION_PACKAGE/direct/InstagramChats;"

/** The names of the two read actions in the enum behind a chat's long press menu, kept as written. */
internal const val MARK_AS_READ = "MARK_AS_READ"
internal const val MARK_AS_UNREAD = "MARK_AS_UNREAD"

/**
 * The log tag and the success step of Instagram's own handler for marking one chat read, which it
 * runs when a paired device asks.
 */
internal const val MARK_READ_HANDLER = "MarkThreadAsReadRequestHandler"
internal const val MARK_READ_DONE = "mark_thread_as_read_success"

/** A chat's key, which keeps its name, and how it spells itself, its thread id first. */
internal const val THREAD_KEY = "Lcom/instagram/model/direct/DirectThreadKey;"
internal const val THREAD_KEY_TEXT = "DirectThreadKey{mThreadId='"

private const val LIST = "Ljava/util/List;"
private const val LIST_ADD = "Ljava/util/List;->add(Ljava/lang/Object;)Z"
private const val ENUM = "Ljava/lang/Enum;"
private const val STRING_TYPE = "Ljava/lang/String;"

/** The enum behind a chat's long press menu: its initializer names both read actions. */
internal object ChatMenuActionsFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(MARK_AS_READ, MARK_AS_UNREAD),
    custom = { method, classDef -> method.name == "<clinit>" && classDef.superclass == ENUM },
)

/** Instagram's own handler for marking one chat read. */
internal object MarkReadRouteFingerprint : Fingerprint(
    strings = listOf(MARK_READ_HANDLER, MARK_READ_DONE),
)

/** One bridge: the stub in [INSTAGRAM_CHATS] and the body the patch writes into it. */
internal class ChatBridge(val stub: MutableMethod, val body: String)

internal data class MarkReadTargets(
    /** Instagram's own Mark as read, the enum constant the long press menu labels and passes on a tap. */
    val markAsRead: FieldReference,
    /** The builder that offers Mark as unread on a chat's long press, and its list of rows. */
    val builder: MutableMethod,
    val rows: Int,
    /** What a tap on a row of that menu runs: the row chosen, the chat, its key and the account it reads. */
    val action: MutableMethod,
    val chosen: Int,
    val thread: Int,
    val key: Int,
    val session: FieldReference,
    val bridges: List<ChatBridge>,
)

private fun refuse(why: String): Nothing = throw PatchException("$THREAD_SEEN_PATCH: $why")
private fun <T> List<T>.one(what: String): T = singleOrNull() ?: refuse("expected one $what, found $size")
private fun Instruction.call() = visualReference() as? MethodReference
private fun Instruction.field() = visualReference() as? FieldReference
private fun Instruction.type() = (visualReference() as? TypeReference)?.type
private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun Method.parameters() = parameterTypes.map(Any::toString)
private fun Method.static() = AccessFlags.STATIC.isSet(accessFlags)
private fun Method.public() = AccessFlags.PUBLIC.isSet(accessFlags)
private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
private val DIRECT_CALLS = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)

private fun Instruction.writes(register: Int): Boolean {
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return opcode.setsRegister() && (destination == register || opcode.setsWideRegister() && destination + 1 == register)
}

/** The message Instagram's handler marks a chat read up to, and the two things it reads off that message. */
private class Pick(val message: MethodReference, val id: MethodReference, val sender: FieldReference)

/**
 * Resolve everything a chat marked read by hand needs, before any edit: Instagram's own Mark as
 * read, which 450 offers only when several chats are picked, the builder that offers Mark as unread
 * on one chat's long press, where a tap on that menu lands, and the calls Instagram's own handler for
 * marking a chat read makes. Those calls go into the bridges' bodies: the receipt itself goes out
 * through [seen]'s live sender, the one the hold already guards, and its handler sends it.
 */
internal fun BytecodePatchContext.findMarkRead(seen: ThreadSeenTargets): MarkReadTargets {
    val classes = mutableListOf<ClassDef>()
    classDefForEach { classes += it }
    val byType = classes.associateBy { it.type }
    val methods = classes.asSequence().flatMap { it.methods.asSequence() }.filter { it.implementation != null }.toList()

    val menuInit = uniqueMethod(THREAD_SEEN_PATCH, "chat menu action enum", ChatMenuActionsFingerprint)
    val menu = byType[menuInit.definingClass] ?: refuse("chat menu action enum is missing")
    val markAsRead = enumConstant(menu, MARK_AS_READ)
    val markAsUnread = enumConstant(menu, MARK_AS_UNREAD)

    // Instagram's own handler for marking a chat read: the message it picks, and the receipt and
    // unread calls it makes with it.
    val route = uniqueMethod(THREAD_SEEN_PATCH, "Instagram handler for marking a chat read", MarkReadRouteFingerprint)
    val sender = seen.creator
    if (!sender.static() || !sender.public() || sender.returnType != "V" ||
        sender.parameters().let { it.size != 5 || it[0] != USER_SESSION || !it[1].startsWith("L") || it.drop(2) != List(3) { STRING_TYPE } }
    ) refuse("live receipt sender doesn't take an account, a detail and three ids")
    val pick = pickMessage(route, sender)
    val unread = unreadCall(route)

    val builderRef = rowBuilder(methods, markAsUnread)
    val builder = mutableMethod(builderRef)
    val rows = builder.parameters().indexOf(LIST)
    builder.requireLocals(THREAD_SEEN_PATCH, 1)
    if (0 in builder.jumpTargets()) refuse("a jump enters the chat menu builder at its first instruction")
    builder.requireFreeAt(THREAD_SEEN_PATCH, 0, listOf(0))
    if (builder.parameterRegisterNumber(rows) > 15) refuse("chat menu builder keeps its rows past v15")

    val actionRef = methods.filter { method ->
        !method.static() && method.name != "<init>" && method.returnType == "V" && method.parameters().count { it == menu.type } == 1
    }.one("chat menu action handler")
    val action = mutableMethod(actionRef)
    val parameters = action.parameters()
    val chosen = parameters.indexOf(menu.type)
    if (parameters.count { it == THREAD_KEY } != 1) refuse("chat menu action handler doesn't take one chat key")
    val key = parameters.indexOf(THREAD_KEY)
    val chat = pick.message.parameterTypes.single().toString()
    val thread = parameters.indices.filter { at ->
        parameters[at] == chat || byType[parameters[at]]?.let { type ->
            AccessFlags.INTERFACE.isSet(type.accessFlags) && chat in type.interfaces
        } == true
    }.one("chat the menu action handler is given")
    if (action.visualCode().none { it.call()?.key() == unread.key() }) {
        refuse("chat menu action handler never takes a chat's unread mark through Instagram's call")
    }
    val session = (byType[action.definingClass] ?: refuse("chat menu action class is missing")).instanceFields
        .filter { it.type == USER_SESSION }.toList().one("account the chat menu action handler keeps")
    action.requireLocals(THREAD_SEEN_PATCH, 5)
    if (0 in action.jumpTargets()) refuse("a jump enters the chat menu action handler at its first instruction")
    action.requireFreeAt(THREAD_SEEN_PATCH, 0, (0..4).toList())

    val keyClass = byType[THREAD_KEY] ?: refuse("$THREAD_KEY is missing")
    val threadId = threadIdField(keyClass)
    val receipt = byType[seen.mutation] ?: refuse("receipt mutation class is missing")
    val receiptKey = receipt.methods.filter {
        !it.static() && it.public() && it.parameterTypes.isEmpty() && it.returnType == THREAD_KEY
    }.one("receipt's chat key")

    requirePublic(byType, keyClass.type, threadId)
    requirePublic(byType, receipt.type, receiptKey)
    requirePublic(byType, pick.message.parameterTypes.single().toString(), null)
    requirePublic(byType, pick.message.definingClass, pick.message)
    requirePublic(byType, pick.id.definingClass, pick.id)
    requirePublic(byType, pick.sender.definingClass, pick.sender)
    requirePublic(byType, sender.parameterTypes[1].toString(), null)
    requirePublic(byType, unread.definingClass, unread)

    val extension = byType[THREAD_SEEN] ?: refuse("extension is missing")
    requireHook(extension, "offerMarkRead", listOf(LIST, JAVA_OBJECT_TYPE), "V")
    requireHook(extension, "markRead", List(5) { JAVA_OBJECT_TYPE }, "Z")
    val bridges = chatBridges(listOf(
        Triple("threadId", listOf(JAVA_OBJECT_TYPE), STRING_TYPE) to """
            check-cast p0, $THREAD_KEY
            iget-object p0, p0, $threadId
            return-object p0
        """,
        Triple("receiptKey", listOf(JAVA_OBJECT_TYPE), JAVA_OBJECT_TYPE) to """
            check-cast p0, ${receipt.type}
            invoke-virtual { p0 }, ${receiptKey.definingClass}->${receiptKey.name}()$THREAD_KEY
            move-result-object p0
            return-object p0
        """,
        Triple("lastMessage", listOf(JAVA_OBJECT_TYPE), JAVA_OBJECT_TYPE) to """
            check-cast p0, $chat
            invoke-static { p0 }, ${pick.message.key()}
            move-result-object p0
            return-object p0
        """,
        Triple("messageId", listOf(JAVA_OBJECT_TYPE), STRING_TYPE) to """
            check-cast p0, ${pick.id.definingClass}
            invoke-virtual { p0 }, ${pick.id.key()}
            move-result-object p0
            return-object p0
        """,
        Triple("senderId", listOf(JAVA_OBJECT_TYPE), STRING_TYPE) to """
            check-cast p0, ${pick.sender.definingClass}
            iget-object p0, p0, ${pick.sender}
            return-object p0
        """,
        Triple("sendSeen", listOf(JAVA_OBJECT_TYPE, JAVA_OBJECT_TYPE, STRING_TYPE, STRING_TYPE, STRING_TYPE), "V") to """
            check-cast p0, $USER_SESSION
            check-cast p1, ${sender.parameterTypes[1]}
            invoke-static { p0, p1, p2, p3, p4 }, ${sender.key()}
            return-void
        """,
        Triple("markUnread", listOf(JAVA_OBJECT_TYPE, JAVA_OBJECT_TYPE, "Z"), "V") to """
            check-cast p0, $USER_SESSION
            check-cast p1, $THREAD_KEY
            invoke-static { p0, p1, p2 }, ${unread.key()}
            return-void
        """,
    ))
    return MarkReadTargets(markAsRead, builder, rows, action, chosen, thread, key, session, bridges)
}

private const val JAVA_OBJECT_TYPE = "Ljava/lang/Object;"

private fun BytecodePatchContext.mutableMethod(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameters() == method.parameters() && it.returnType == method.returnType
    }

/**
 * The static field of [enum] that holds its constant named [name]: Instagram's initializer loads the
 * name, builds a new constant with it and stores that constant before building the next one.
 */
private fun enumConstant(enum: ClassDef, name: String): FieldReference {
    val init = enum.methods.filter { it.name == "<clinit>" }.one("initializer of the enum naming $name")
    val code = init.visualCode()
    val named = code.indices.filter { code[it].visualString() == name }.one("$name in its enum's initializer")
    val text = (code[named] as OneRegisterInstruction).registerA
    val built = (named + 1 until code.size).firstOrNull {
        code[it].opcode in DIRECT_CALLS && code[it].call()?.let { call -> call.definingClass == enum.type && call.name == "<init>" } == true
    } ?: refuse("$name is never built")
    val arguments = code[built].namedRegisters()
    if (arguments.getOrNull(1) != text) refuse("$name isn't the name its constant is built with")
    requireOrigin(THREAD_SEEN_PATCH, init, built, text, named, "$name's name")
    val stored = (built + 1 until code.size).firstOrNull { code[it].opcode == Opcode.SPUT_OBJECT }
        ?: refuse("$name is never stored")
    val field = code[stored].field()!!
    if (field.definingClass != enum.type || field.type != enum.type) refuse("$name is stored outside its enum")
    val constant = (code[stored] as OneRegisterInstruction).registerA
    val allocated = (named + 1 until built).filter {
        code[it].opcode == Opcode.NEW_INSTANCE && code[it].type() == enum.type
    }.one("allocation of $name")
    if ((code[allocated] as OneRegisterInstruction).registerA != constant) refuse("$name stores another object than it builds")
    requireOrigin(THREAD_SEEN_PATCH, init, stored, constant, allocated, "$name's constant")
    val receiver = arguments.first()
    if (receiver == constant) {
        requireOrigin(THREAD_SEEN_PATCH, init, built, receiver, allocated, "$name's constant")
    } else {
        val copied = (built - 1 downTo allocated + 1).firstOrNull { code[it].writes(receiver) }
        if (copied == null || code[copied].opcode !in OBJECT_MOVES || (code[copied] as TwoRegisterInstruction).registerB != constant) {
            refuse("$name is built on another object than it stores")
        }
        requireOrigin(THREAD_SEEN_PATCH, init, copied, constant, allocated, "$name's constant")
        requireOrigin(THREAD_SEEN_PATCH, init, built, receiver, copied, "$name's constant")
    }
    return field
}

/**
 * The builder that offers Mark as unread on a chat's long press: the one static method taking one
 * list that adds [markAsUnread] to it straight after loading it.
 */
private fun rowBuilder(methods: List<Method>, markAsUnread: FieldReference): Method = methods.filter { method ->
    if (!method.static() || method.returnType != "V" || method.parameters().count { it == LIST } != 1) return@filter false
    val rows = method.parameterRegisterNumber(method.parameters().indexOf(LIST))
    val code = method.visualCode()
    code.indices.any { at ->
        val next = code.getOrNull(at + 1)
        code[at].opcode == Opcode.SGET_OBJECT && code[at].field()?.toString() == markAsUnread.toString() &&
            next?.opcode == Opcode.INVOKE_INTERFACE && next.call()?.key() == LIST_ADD &&
            next.namedRegisters() == listOf(rows, (code[at] as OneRegisterInstruction).registerA)
    }
}.one("chat menu builder offering Mark as unread")

/**
 * The message [route] marks a chat read up to: a static call on the chat answering a message whose
 * id getter and sender field give the two ids [route] hands [sender], in that order.
 */
private fun pickMessage(route: Method, sender: Method): Pick {
    val code = route.visualCode()
    val sent = code.indices.filter { code[it].call()?.key() == sender.key() }.one("receipt sent by Instagram's handler for marking a chat read")
    val ids = code[sent].namedRegisters()
    if (ids.size != 5) refuse("Instagram's handler for marking a chat read sends its receipt with ${ids.size} arguments")
    val picks = code.indices.mapNotNull { at ->
        val get = code[at].takeIf { it.opcode == Opcode.INVOKE_STATIC }?.call() ?: return@mapNotNull null
        if (get.parameterTypes.size != 1 || !get.parameterTypes[0].startsWith("L") || !get.returnType.startsWith("L")) return@mapNotNull null
        val message = (code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction)?.registerA
            ?: return@mapNotNull null
        val idAt = (at + 2 until sent).firstOrNull { code[it].writes(message) || code[it].opcode == Opcode.INVOKE_VIRTUAL && code[it].namedRegisters() == listOf(message) }
            ?: return@mapNotNull null
        val id = code[idAt].takeIf { it.opcode == Opcode.INVOKE_VIRTUAL }?.call() ?: return@mapNotNull null
        if (id.parameterTypes.isNotEmpty() || id.returnType != STRING_TYPE) return@mapNotNull null
        if ((code.getOrNull(idAt + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction)?.registerA != ids[3]) {
            return@mapNotNull null
        }
        val senderAt = (idAt + 2 until sent).firstOrNull {
            code[it].writes(message) || code[it].opcode == Opcode.IGET_OBJECT && (code[it] as TwoRegisterInstruction).registerB == message
        } ?: return@mapNotNull null
        val from = code[senderAt].takeIf { it.opcode == Opcode.IGET_OBJECT }?.field() ?: return@mapNotNull null
        if (from.type != STRING_TYPE || (code[senderAt] as OneRegisterInstruction).registerA != ids[4]) return@mapNotNull null
        Triple(at, idAt, senderAt) to Pick(get, id, from)
    }
    val (found, pick) = picks.one("message Instagram's handler for marking a chat read sends its receipt for")
    requireOrigin(THREAD_SEEN_PATCH, route, found.second, (code[found.first + 1] as OneRegisterInstruction).registerA, found.first + 1, "marked message")
    requireOrigin(THREAD_SEEN_PATCH, route, found.third, (code[found.first + 1] as OneRegisterInstruction).registerA, found.first + 1, "marked message")
    return pick
}

/** The call [route] takes a chat's unread mark off with: Instagram's (account, chat key, boolean), handed false. */
private fun unreadCall(route: Method): MethodReference {
    val code = route.visualCode()
    val at = code.indices.filter { index ->
        code[index].opcode == Opcode.INVOKE_STATIC && code[index].call()?.let {
            it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(USER_SESSION, THREAD_KEY, "Z")
        } == true
    }.one("call Instagram's handler for marking a chat read takes the unread mark off with")
    val flag = code[at].namedRegisters()[2]
    val set = (at - 1 downTo 0).firstOrNull { code[it].writes(flag) }
    if (set == null || code[set].opcode != Opcode.CONST_4 || (code[set] as NarrowLiteralInstruction).narrowLiteral != 0) {
        refuse("Instagram's handler for marking a chat read doesn't hand its unread call false")
    }
    requireOrigin(THREAD_SEEN_PATCH, route, at, flag, set, "false handed to the unread call")
    return code[at].call()!!
}

/** The field a chat key's toString writes right after [THREAD_KEY_TEXT]: its own thread id. */
private fun threadIdField(key: ClassDef): FieldReference {
    val toString = key.methods.filter { it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == STRING_TYPE }
        .one("chat key's toString")
    val code = toString.visualCode()
    val labelAt = code.indices.filter { code[it].visualString() == THREAD_KEY_TEXT }.one("chat key's thread id label")
    val label = (code[labelAt] as OneRegisterInstruction).registerA
    val joined = (labelAt + 1 until code.size).firstOrNull {
        code[it].call() != null && code[it].namedRegisters().firstOrNull() == label
    } ?: refuse("chat key's toString never uses its thread id label")
    val id = code[joined].namedRegisters().getOrNull(1) ?: refuse("chat key's toString writes nothing after its thread id label")
    val read = (joined - 1 downTo 0).firstOrNull { code[it].writes(id) }?.takeIf { at ->
        code[at].opcode == Opcode.IGET_OBJECT && (code[at] as TwoRegisterInstruction).registerB == toString.localRegisterCount() &&
            code[at].field()?.let { it.definingClass == key.type && it.type == STRING_TYPE } == true
    } ?: refuse("chat key's toString doesn't write its own thread id after the label")
    requireOrigin(THREAD_SEEN_PATCH, toString, joined, id, read, "chat key's thread id")
    toString.requireThisIntact(THREAD_SEEN_PATCH, listOf(read))
    return code[read].field()!!
}

/** Refuses unless [type] is a public class and [member], when given, is public too, so the extension can reach it. */
private fun requirePublic(classes: Map<String, ClassDef>, type: String, member: Any?) {
    val owner = classes[type] ?: refuse("$type is missing")
    val flags = when (member) {
        null -> AccessFlags.PUBLIC.value
        is FieldReference -> owner.fields.firstOrNull { it.name == member.name && it.type == member.type }?.accessFlags
        is MethodReference -> owner.methods.firstOrNull { it.name == member.name && it.parameters() == member.parameterTypes.map(Any::toString) && it.returnType == member.returnType }?.accessFlags
        else -> null
    } ?: refuse("$type doesn't declare $member")
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || !AccessFlags.PUBLIC.isSet(flags)) {
        refuse("${member ?: type} isn't public, so the extension can't reach it")
    }
}

private fun requireHook(extension: ClassDef, name: String, parameters: List<String>, returns: String) {
    extension.methods.filter {
        it.name == name && it.parameters() == parameters && it.returnType == returns && it.public() && it.static()
    }.one("extension's public static $name")
}

/** Each bridge's stub in [INSTAGRAM_CHATS], with the body that goes into it. */
private fun BytecodePatchContext.chatBridges(bodies: List<Pair<Triple<String, List<String>, String>, String>>): List<ChatBridge> {
    val chats = classDefByOrNull(INSTAGRAM_CHATS) ?: refuse("extension has no $INSTAGRAM_CHATS")
    val stubs = mutableClassDefBy(chats).methods
    return bodies.map { (shape, body) ->
        val (name, parameters, returns) = shape
        val stub = stubs.filter {
            it.name == name && it.parameters() == parameters && it.returnType == returns &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        }.one("extension's chat bridge $name")
        ChatBridge(stub, body)
    }
}

/**
 * Offers Instagram's own Mark as read on a chat's long press while the switch is on, and on a tap
 * hands the extension the row, the chat, its key and the account before Instagram's own code for
 * the row runs, which does nothing for Mark as read. Then writes the bridges' bodies.
 */
internal fun markReadByHand(found: MarkReadTargets) {
    found.builder.addInstructions(
        0,
        """
            sget-object v0, ${found.markAsRead}
            invoke-static { ${found.builder.parameterRegister(found.rows)}, v0 }, $OFFER_MARK_READ
        """,
    )
    val action = found.action
    action.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, ${action.parameterRegister(found.chosen)}
            sget-object v1, ${found.markAsRead}
            move-object/from16 v2, p0
            iget-object v2, v2, ${found.session}
            move-object/from16 v3, ${action.parameterRegister(found.thread)}
            move-object/from16 v4, ${action.parameterRegister(found.key)}
            invoke-static { v0, v1, v2, v3, v4 }, $MARK_READ
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", action.getInstruction(0)),
    )
    found.bridges.forEach { it.stub.addInstructions(0, it.body) }
}

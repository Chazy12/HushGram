/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.share

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.BitSet

private const val PATCH = "Hide the Repost button"
internal const val HIDE_REPOSTS = "$EXTENSION_PACKAGE/share/RepostButton;->hide()Z"
internal const val REPOSTS_ELIGIBLE = "$EXTENSION_PACKAGE/share/RepostButton;->eligible(Ljava/lang/Boolean;)Ljava/lang/Boolean;"
internal const val REPOSTS_FEED_UFI = "$EXTENSION_PACKAGE/share/RepostButton;->feedUfi(Landroid/view/View;Landroid/view/View;)V"
internal const val REPOSTS_FEED_COMPONENT = "$EXTENSION_PACKAGE/share/RepostButton;->feedComponent()Z"
internal const val REPOSTS_FEED_RESTORE = "$EXTENSION_PACKAGE/share/RepostButton;->restoreFeedUfi(Landroid/view/View;Landroid/view/View;)V"
internal const val REPOSTS_FEED_STATE = "$EXTENSION_PACKAGE/share/RepostButton;->feedState(I)Z"

/**
 * The labels Feed's action-row state prints its Repost flags under in its toString: whether the
 * button shows, whether its count does, and whether it animates.
 */
internal const val REPOST_ENABLED_LABEL = ", isRepostButtonEnabled="
internal const val REPOST_COUNT_LABEL = ", shouldShowRepostCount="
internal const val REPOST_ANIMATE_LABEL = ", shouldAnimateRepostButton="

/** The post model, a kept name. */
internal const val MEDIA = "Lcom/instagram/feed/media/Media;"

/**
 * The field that says whether a post or reel can be reposted. Its name predates reposts; 449's
 * clips buttons call what they read from it isEligibleForRepostsProduction. Instagram's data trees
 * key a field by its name's hash.
 */
internal const val REPOSTS_FIELD = "enable_media_notes_production"
internal val REPOSTS_HASH = REPOSTS_FIELD.hashCode()

private const val BOOLEAN = "Ljava/lang/Boolean;"
private const val STRING_BUILDER = "Ljava/lang/StringBuilder;"
private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
private const val BOUNCY_UFI_BUTTON = "Lcom/instagram/ui/widget/bouncyufibutton/IgBouncyUfiButtonImageView;"
private const val UFI_COUNT = "Lcom/instagram/common/ui/base/IgTextView;"

/** Feed's inflated repost icon and count in Instagram 450, and the Repost button's label. */
internal const val REPOSTS_UFI_ICON_ID = 0x7f0b3614
internal const val REPOSTS_UFI_COUNT_ID = 0x7f0b3613
internal const val REPOSTS_LABEL_ID = 0x7f136e0d

/** How far before a tree read its hash may be loaded, for a branch or two in between. */
private const val HASH_REACH = 4

/**
 * Takes the Repost button off posts and reels. Off in the default selection: reposting is one of
 * Instagram's features, so leaving it out is the user's pick.
 */
@Suppress("unused")
val hideRepostButtonPatch = bytecodePatch(
    name = "Hide the Repost button",
    description = "Takes the Repost button and its count off posts and reels, so nothing gets reposted by mistake. " +
        "Share still sends a post or reel to someone.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("repostButton")
        // Proved before anything changes: it reads the state's own constructor as Instagram wrote it.
        val feedState = findFeedRepostState()
        val sites = findRepostSites()
        if (sites.reads.any { it.type == feedState.type && it.name == "<init>" }) {
            refuse("${feedState.type}'s constructor reads $REPOSTS_FIELD itself, so its writes and that read would move each other")
        }
        // The state goes first, so its own check of each write runs before this patch changes anything.
        hideFeedState(feedState)
        guardRepostGetter(sites.getter)
        sites.reads.groupBy { Triple(it.type, it.name, it.parameters) }.values.forEach(::filterRepostReads)
        hideFeedUfi(findFeedUfiSite())
        hideFeedComponent(findFeedRepostComponent())
        enableStatus("repostButton")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** A read of the field from a post's data tree: where its move-result-object is and the register it fills. */
internal class RepostRead(val type: String, val name: String, val parameters: List<String>, val at: Int, val register: Int)

/** The post model's getter of the field, and every tree read of it outside the model. */
internal class RepostSites(val getter: String, val reads: List<RepostRead>)

/** Feed's already-inflated UFI repost views and the point before the Share button is bound. */
internal class FeedUfiSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val insert: Int,
    val holder: Int,
    val icon: FieldReference,
    val count: FieldReference,
)

/**
 * Finds [MEDIA]'s one getter of [REPOSTS_FIELD]: an instance method taking nothing, answering a
 * Boolean, that loads the field's name and its hash and has a register to spare. Then every read
 * of the field from a data tree elsewhere: an interface call taking an int and answering a Boolean,
 * handed [REPOSTS_HASH] loaded just before it, its answer moved straight out. The model's own other
 * reads copy the field between its forms and are left alone. Fails when the getter isn't there,
 * there's more than one, or no button reads the field, since that's an update this patch hasn't
 * seen.
 */
internal fun BytecodePatchContext.findRepostSites(): RepostSites {
    val media = classDefByOrNull(MEDIA) ?: refuse("$MEDIA is missing")
    val getters = media.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() && method.returnType == BOOLEAN &&
            method.holdsString(REPOSTS_FIELD) && method.holdsHash()
    }
    val getter = getters.singleOrNull() ?: refuse("expected one getter of $REPOSTS_FIELD in $MEDIA, found ${getters.size}")
    val implementation = getter.implementation!!
    if (implementation.registerCount - 1 < 1) refuse("$MEDIA->${getter.name} has no register of its own for the guard")

    val reads = mutableListOf<RepostRead>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT) || classDef.type == MEDIA) return@classDefForEach
        classDef.methods.forEach { method -> reads += method.repostReads(classDef.type) }
    }
    if (reads.isEmpty()) refuse("nothing reads $REPOSTS_FIELD from a post's data tree")
    return RepostSites(getter.name, reads)
}

/**
 * Finds the Feed UFI binder that inflates `reposts_ufi_icon` and `reposts_ufi_count`, then inserts
 * before the next bouncy UFI button field. On 449 that next button is Share, so every native repost
 * icon/count update has already run and Share remains untouched.
 */
internal fun BytecodePatchContext.findFeedUfiSite(): FeedUfiSite {
    val sites = mutableListOf<FeedUfiSite>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            val iconId = code.indexOfLiteral(REPOSTS_UFI_ICON_ID)
            val countId = code.indexOfLiteral(REPOSTS_UFI_COUNT_ID)
            if (iconId < 0 || countId < 0) return@forEach
            val icon = code.fieldStoreAfter(iconId, BOUNCY_UFI_BUTTON) ?: return@forEach
            val count = code.fieldStoreAfter(countId, UFI_COUNT) ?: return@forEach
            if (icon.holder != count.holder) {
                refuse("${classDef.type}->${method.name} stores Feed UFI icon and count on different holders")
            }
            val insert = code.indexOfFirstBouncyReadAfter(maxOf(icon.at, count.at), icon.field)
            if (insert < 0) refuse("${classDef.type}->${method.name} has Feed UFI views but no following Share button read")
            sites += FeedUfiSite(
                classDef.type,
                method.name,
                method.parameterTypes.map(CharSequence::toString),
                insert,
                icon.holder,
                icon.field,
                count.field,
            )
        }
    }
    return sites.singleOrNull()
        ?: refuse("expected one Feed UFI repost binder, found ${sites.size}")
}

/**
 * The component-backed Feed row's Repost renderer, separate from the view binder, and where in it
 * the hook goes. On 449 the renderer is a method of its own and the hook goes first. 450's Redex
 * merges it with other Feed components into one method that picks its part by the component's
 * class, so the hook goes at the start of the one part that reaches the repost icon and label.
 */
internal class FeedRepostComponent(val method: Method, val at: Int)

internal fun BytecodePatchContext.findFeedRepostComponent(): FeedRepostComponent {
    val renders = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            if (!AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 1 &&
                method.parameterTypes.single().startsWith("L") && method.returnType.startsWith("L") &&
                method.holdsString("android.widget.Button") && code.indexOfLiteral(REPOSTS_UFI_ICON_ID) >= 0 &&
                code.indexOfLiteral(REPOSTS_LABEL_ID) >= 0) renders += method
        }
    }
    val render = renders.singleOrNull() ?: refuse("expected one Feed Repost component renderer, found ${renders.size}")
    val parts = mergedParts(render)
    if (parts.isEmpty()) return FeedRepostComponent(render, 0)
    val code = render.implementation!!.instructions.toList()
    val flow = ControlFlow.of(render)
    fun loads(id: Int) = code.indices.filter { (code[it] as? NarrowLiteralInstruction)?.narrowLiteral == id && code[it].opcode == Opcode.CONST }
    val icons = loads(REPOSTS_UFI_ICON_ID)
    val labels = loads(REPOSTS_LABEL_ID)
    val checks = parts.map { it - 2 }.toSet()
    val reposts = parts.filter { start ->
        val seen = mutableSetOf<Int>()
        val pending = java.util.ArrayDeque<Int>().apply { add(start) }
        while (pending.isNotEmpty()) {
            val at = pending.removeFirst()
            if (at in checks || !seen.add(at)) continue
            pending.addAll(flow.normal[at]); pending.addAll(flow.exceptional[at])
        }
        icons.any { it in seen } && labels.any { it in seen }
    }
    val at = reposts.singleOrNull()
        ?: refuse("expected one part of ${render.definingClass}->${render.name} drawing Repost, found ${reposts.size}")
    if (flow.normal.indices.any { from -> from != at - 1 && at in flow.normal[from] }) {
        refuse("${render.definingClass}->${render.name}'s Repost part is reached other than from its class check")
    }
    return FeedRepostComponent(render, at)
}

/**
 * Where each part of a Redex-merged method starts: right after an instance-of check of the
 * method's own receiver (or a copy made before the first check) and the if-eqz that skips the part.
 */
private fun mergedParts(method: Method): List<Int> {
    val implementation = method.implementation!!
    val code = implementation.instructions.toList()
    val receivers = mutableSetOf(method.localRegisterCount())
    for (instruction in code) {
        if (instruction.opcode == Opcode.INSTANCE_OF) break
        if (instruction.opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) &&
            (instruction as TwoRegisterInstruction).registerB in receivers) receivers += instruction.registerA
    }
    return code.indices.filter { at ->
        val check = code[at]
        check.opcode == Opcode.INSTANCE_OF && (check as TwoRegisterInstruction).registerB in receivers &&
            code.getOrNull(at + 1)?.let { skip -> skip.opcode == Opcode.IF_EQZ &&
                (skip as OneRegisterInstruction).registerA == check.registerA } == true
    }.map { it + 2 }
}

/** Native component rendering accepts null for an empty component; neither icon nor count mounts. */
internal fun BytecodePatchContext.hideFeedComponent(found: FeedRepostComponent) {
    val method = mutableClassDefBy(found.method.definingClass).methods.single {
        it.name == found.method.name && it.returnType == found.method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == found.method.parameterTypes.map(CharSequence::toString)
    }
    val register = if (found.at == 0) {
        method.requireLocals(PATCH, 1)
        0
    } else {
        // Only a local that no later instruction reads before writing it, low enough for const/4.
        val live = RegisterLiveness.of(method).liveInto(found.at)
        (0 until minOf(method.localRegisterCount(), 16)).firstOrNull { it !in live }
            ?: refuse("${method.definingClass}->${method.name} has no spare register at its Repost part")
    }
    // Off goes on to the part's own first instruction. An internal label would stay where the block
    // was assembled, which is only the part's start when the part opens the method.
    method.addInstructionsWithLabels(
        found.at,
        """
            invoke-static { }, $REPOSTS_FEED_COMPONENT
            move-result v$register
            if-eqz v$register, :draw
            const/4 v$register, 0x0
            return-object v$register
        """,
        ExternalLabel("draw", method.getInstruction(found.at)),
    )
}

/**
 * One write of a Repost flag in a constructor of Feed's action-row state: `iput-boolean` of [value]
 * into [holder]'s [field] at [at], and the register the hook's answer goes into. That's [value]
 * itself when nothing reads it after the write, and otherwise a spare one the write then stores.
 */
internal class FeedStateWrite(
    val parameters: List<String>,
    val at: Int,
    val value: Int,
    val holder: Int,
    val field: FieldReference,
    val answer: Int,
)

/** Feed's action-row state, the Repost flags it keeps, and every constructor write of them. */
internal class FeedRepostState(val type: String, val flags: List<FieldReference>, val writes: List<FeedStateWrite>)

/**
 * Feed draws each post's action row from an immutable state, and its toString prints
 * [REPOST_ENABLED_LABEL] followed by whether the row shows the Repost button. That flag is the
 * boolean field of the state's own class that toString loads from itself and appends right after
 * the label. It's final, so only the state's constructors set it, and [hideFeedState] passes every
 * one of those writes through [REPOSTS_FEED_STATE]. The row is drawn again from the same state, so
 * a button hidden only after a draw can come back on the next one (#69).
 *
 * The flags printed under [REPOST_COUNT_LABEL] and [REPOST_ANIMATE_LABEL] go the same way when each
 * is a final field of the state's own that its constructor sets once. On 450 the count is one, and
 * the animation is printed from a constant, so it's left out.
 *
 * Fails before any change when no class prints the label or more than one does, when what follows
 * the label isn't such a flag, when a method other than a constructor sets it, or when a write has
 * no register for the answer.
 */
internal fun BytecodePatchContext.findFeedRepostState(): FeedRepostState {
    val states = classesHolding(REPOST_ENABLED_LABEL).filter { classDef ->
        classDef.methods.any { it.isToString() && it.holdsString(REPOST_ENABLED_LABEL) }
    }
    val state = states.singleOrNull()
        ?: refuse("expected one Feed action-row state printing \"$REPOST_ENABLED_LABEL\", found ${states.size}")
    val toString = state.methods.single { it.isToString() }
    val enabled = toString.printedFlag(state.type, REPOST_ENABLED_LABEL)
    val writes = state.flagWrites(enabled).toMutableList()
    if (writes.isEmpty()) refuse("${state.type}'s constructor never sets what it prints as \"$REPOST_ENABLED_LABEL\"")
    val flags = mutableListOf(enabled)
    for (label in listOf(REPOST_COUNT_LABEL, REPOST_ANIMATE_LABEL)) {
        if (!toString.holdsString(label)) continue
        // A flag printed some other way, or set anywhere but once in a constructor, is left alone.
        val flag = try { toString.printedFlag(state.type, label) } catch (_: PatchException) { continue }
        val set = try { state.flagWrites(flag) } catch (_: PatchException) { continue }
        if (set.size == 1 && flags.none { it.sameAs(flag) }) {
            flags += flag
            writes += set
        }
    }
    return FeedRepostState(state.type, flags, writes)
}

/**
 * Right before each constructor write of a Repost flag, passes the value through
 * [REPOSTS_FEED_STATE], so a state built while the switch is on keeps the button and its count off
 * however often Feed draws the row from it. A label on the write moves onto the call, so a branch
 * to the write runs the call too.
 */
internal fun BytecodePatchContext.hideFeedState(found: FeedRepostState) {
    val state = mutableClassDefBy(found.type)
    val constructors = found.writes.groupBy { it.parameters }.map { (parameters, writes) ->
        state.methods.single { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == parameters } to writes
    }
    // Every write is checked before the first changes.
    for ((constructor, writes) in constructors) for (write in writes) {
        val store = constructor.getInstruction(write.at)
        val field = (store as? ReferenceInstruction)?.reference as? FieldReference
        if (store.opcode != Opcode.IPUT_BOOLEAN || field == null || !field.sameAs(write.field) ||
            (store as TwoRegisterInstruction).registerA != write.value
        ) refuse("${found.type}'s constructor changed at instruction ${write.at} before its Repost flags were hooked")
    }
    for ((constructor, writes) in constructors) for (write in writes.sortedByDescending { it.at }) {
        constructor.addInstructionsAtControlFlowLabel(
            write.at,
            """
                invoke-static { v${write.value} }, $REPOSTS_FEED_STATE
                move-result v${write.answer}
            """,
        )
        if (write.answer != write.value) {
            constructor.replaceInstruction(write.at + 2, "iput-boolean v${write.answer}, v${write.holder}, ${write.field}")
        }
    }
}

private fun Method.isToString() = name == "toString" && parameterTypes.isEmpty() &&
    returnType == "Ljava/lang/String;" && !AccessFlags.STATIC.isSet(accessFlags)

private fun FieldReference.sameAs(other: FieldReference) =
    definingClass == other.definingClass && name == other.name && type == other.type

/**
 * The flag this toString prints under [label]: the boolean field of [type] it loads from itself
 * and appends right after appending the label. Refuses, saying what it found instead.
 */
private fun Method.printedFlag(type: String, label: String): FieldReference {
    val code = implementation!!.instructions.toList()
    val loads = code.indices.filter { code[it].loadsString(label) }
    val load = loads.singleOrNull() ?: refuse("$type->toString loads \"$label\" ${loads.size} times")
    var at = load + 1
    if (code.getOrNull(at)?.appended("Ljava/lang/String;") != (code[load] as OneRegisterInstruction).registerA) {
        refuse("$type->toString doesn't append \"$label\" right after loading it")
    }
    at++
    if (code.getOrNull(at)?.opcode == Opcode.MOVE_RESULT_OBJECT) at++
    val value = code.getOrNull(at)?.appended("Z")
        ?: refuse("$type->toString prints something other than a boolean after \"$label\"")
    val flow = ControlFlow.of(this)
    val loaded = flow.writersReaching(at, value)
    val reading = loaded.singleOrNull()?.takeIf { it >= 0 }
        ?: refuse("$type->toString prints \"$label\" from a value it doesn't load in one place")
    val read = code[reading]
    val field = (read as? ReferenceInstruction)?.reference as? FieldReference
    if (read.opcode != Opcode.IGET_BOOLEAN || field == null || field.definingClass != type) {
        refuse("$type->toString prints \"$label\" from something other than a boolean field of its own")
    }
    if (!flow.holdsThis(reading, (read as TwoRegisterInstruction).registerB, localRegisterCount())) {
        refuse("$type->toString prints \"$label\" from an object other than itself")
    }
    return field
}

/**
 * Each write of [flag] in this class, which has to be a final boolean field of its own, so set only
 * by its constructors. Refuses when it isn't, when another method sets it all the same, or when a
 * write has no register for the hook's answer.
 */
private fun ClassDef.flagWrites(flag: FieldReference): List<FeedStateWrite> {
    val declared = fields.singleOrNull { it.name == flag.name && it.type == flag.type }
    if (flag.definingClass != type || flag.type != "Z" || declared == null ||
        AccessFlags.STATIC.isSet(declared.accessFlags) || !AccessFlags.FINAL.isSet(declared.accessFlags)
    ) refuse("$type's ${flag.name} isn't a final boolean field of its own")
    val writes = mutableListOf<FeedStateWrite>()
    for (method in methods) {
        val code = method.implementation?.instructions?.toList() ?: continue
        for ((at, instruction) in code.withIndex()) {
            if (instruction.opcode != Opcode.IPUT_BOOLEAN) continue
            val field = (instruction as ReferenceInstruction).reference as FieldReference
            if (!field.sameAs(flag)) continue
            if (method.name != "<init>") refuse("$type->${method.name} sets ${flag.name}, which only its constructor should")
            val store = instruction as TwoRegisterInstruction
            // The value's register takes the answer when nothing reads it afterwards, handlers included.
            val answer = if (method.readsAfter(at, store.registerA).isEmpty()) store.registerA
                else method.freeLocalsAt(PATCH, at, 1).single()
            writes += FeedStateWrite(
                method.parameterTypes.map(CharSequence::toString), at, store.registerA, store.registerB, field, answer,
            )
        }
    }
    return writes
}

private fun Instruction.loadsString(value: String) =
    (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
        ((this as ReferenceInstruction).reference as StringReference).string == value

/** The register this hands to `StringBuilder.append([parameter])`, or null when it's no such call. */
private fun Instruction.appended(parameter: String): Int? {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return null
    val called = (this as ReferenceInstruction).reference as MethodReference
    if (called.definingClass != STRING_BUILDER || called.name != "append" ||
        called.parameterTypes.map(CharSequence::toString) != listOf(parameter)
    ) return null
    return when (this) {
        is FiveRegisterInstruction -> registerD
        is RegisterRangeInstruction -> startRegister + 1
        else -> null
    }
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

/**
 * Every instruction whose write to [register] can be what instruction [at] reads, -1 standing for
 * what the register held when the method was called. Walks back along every path, branches,
 * switches and handlers included, to the nearest write. A handler sees the register as it was
 * before the instruction that threw, so that instruction's own write is walked past.
 */
private fun ControlFlow.writersReaching(at: Int, register: Int): Set<Int> {
    val normalFrom = Array(instructions.size) { mutableListOf<Int>() }
    val thrownFrom = Array(instructions.size) { mutableListOf<Int>() }
    for (from in instructions.indices) {
        normal[from].forEach { normalFrom[it] += from }
        exceptional[from].forEach { thrownFrom[it] += from }
    }
    val writers = sortedSetOf<Int>()
    val seen = BitSet()
    val pending = ArrayDeque<Int>()
    // Each pending instruction is one whose register before it runs is wanted.
    fun before(index: Int) {
        if (!seen[index]) { seen.set(index); pending += index }
    }
    before(at)
    while (pending.isNotEmpty()) {
        val index = pending.removeFirst()
        if (index == 0) writers += -1
        normalFrom[index].forEach { from -> if (instructions[from].writes(register)) writers += from else before(from) }
        thrownFrom[index].forEach(::before)
    }
    return writers
}

/**
 * Whether [register] holds `this`, which the method keeps in [self], when instruction [at] runs:
 * [self] untouched since the method started, or a copy made of it.
 */
private fun ControlFlow.holdsThis(at: Int, register: Int, self: Int, depth: Int = 0): Boolean {
    val writers = writersReaching(at, register)
    if (writers == setOf(-1)) return register == self
    val copy = writers.singleOrNull()?.takeIf { it >= 0 } ?: return false
    val move = instructions[copy]
    return depth < 4 && move.opcode in OBJECT_MOVES &&
        holdsThis(copy, (move as TwoRegisterInstruction).registerB, self, depth + 1)
}

private fun Method.holdsString(value: String) = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
} == true

private fun Method.holdsHash() = implementation?.instructions?.any {
    it is NarrowLiteralInstruction && it.opcode == Opcode.CONST && it.narrowLiteral == REPOSTS_HASH
} == true

private fun List<Instruction>.indexOfLiteral(value: Int) = indexOfFirst {
    it is NarrowLiteralInstruction && it.opcode == Opcode.CONST && it.narrowLiteral == value
}

private class FieldStore(val at: Int, val holder: Int, val field: FieldReference)

private fun List<Instruction>.fieldStoreAfter(start: Int, fieldType: String): FieldStore? {
    for (at in start + 1 until minOf(size, start + 12)) {
        val instruction = this[at]
        if (instruction.opcode != Opcode.IPUT_OBJECT || instruction !is TwoRegisterInstruction) continue
        val field = ((instruction as? ReferenceInstruction)?.reference as? FieldReference) ?: continue
        if (field.type == fieldType) return FieldStore(at, instruction.registerB, field)
    }
    return null
}

private fun List<Instruction>.indexOfFirstBouncyReadAfter(start: Int, skipped: FieldReference): Int {
    for (at in start + 1 until size) {
        val instruction = this[at]
        if (instruction.opcode != Opcode.IGET_OBJECT) continue
        val field = ((instruction as? ReferenceInstruction)?.reference as? FieldReference) ?: continue
        if (field.type == BOUNCY_UFI_BUTTON && field.toString() != skipped.toString()) return at
    }
    return -1
}

private fun Method.repostReads(type: String): List<RepostRead> {
    val code = implementation?.instructions?.toList() ?: return emptyList()
    val reads = mutableListOf<RepostRead>()
    for ((at, instruction) in code.withIndex()) {
        if (instruction.opcode != Opcode.INVOKE_INTERFACE && instruction.opcode != Opcode.INVOKE_INTERFACE_RANGE) continue
        val called = (instruction as ReferenceInstruction).reference as MethodReference
        if (called.returnType != BOOLEAN || called.parameterTypes.map(CharSequence::toString) != listOf("I")) continue
        // The tree, then the int it's asked for.
        val key = when (instruction) {
            is FiveRegisterInstruction -> instruction.registerD
            is RegisterRangeInstruction -> instruction.startRegister + 1
            else -> continue
        }
        if (!code.hashLoadedInto(key, at)) continue
        val result = code.getOrNull(at + 1)
        if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$type->$name reads $REPOSTS_FIELD and drops the answer")
        reads += RepostRead(type, name, parameterTypes.map(CharSequence::toString), at + 1, (result as OneRegisterInstruction).registerA)
    }
    return reads
}

/** Whether [REPOSTS_HASH] is loaded into [register] within [HASH_REACH] instructions before [at], and nothing else writes it between. */
private fun List<Instruction>.hashLoadedInto(register: Int, at: Int): Boolean {
    for (back in (at - 1) downTo maxOf(0, at - HASH_REACH)) {
        val instruction = this[back]
        val writes = instruction is OneRegisterInstruction && instruction !is FiveRegisterInstruction &&
            instruction.registerA == register && instruction.opcode.setsRegister()
        if (!writes) continue
        return instruction is NarrowLiteralInstruction && instruction.opcode == Opcode.CONST && instruction.narrowLiteral == REPOSTS_HASH
    }
    return false
}

/**
 * First thing in the getter, asks [HIDE_REPOSTS], and on a yes answers FALSE: the post can't be
 * reposted. Otherwise the getter reads the field as it did.
 */
internal fun BytecodePatchContext.guardRepostGetter(getter: String) {
    val method = mutableClassDefBy(MEDIA).methods.single {
        it.name == getter && it.parameterTypes.isEmpty() && it.returnType == BOOLEAN
    }
    method.addInstructions(
        0,
        """
            invoke-static { }, $HIDE_REPOSTS
            move-result v0
            if-eqz v0, :read
            sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
            return-object v0
            :read
            nop
        """,
    )
}

/**
 * Right after each of one method's tree reads moves its answer out, passes it through
 * [REPOSTS_ELIGIBLE]. A range call, so the answer's register may be any.
 */
internal fun BytecodePatchContext.filterRepostReads(reads: List<RepostRead>) {
    val first = reads.first()
    val method = mutableClassDefBy(first.type).methods.single {
        it.name == first.name && it.parameterTypes.map(CharSequence::toString) == first.parameters
    }
    for (read in reads.sortedByDescending { it.at }) {
        method.addInstructions(
            read.at + 1,
            """
                invoke-static/range { v${read.register} .. v${read.register} }, $REPOSTS_ELIGIBLE
                move-result-object v${read.register}
            """,
        )
    }
}

/** Hide the Feed UFI repost views after Instagram has rebound them for this row. */
internal fun BytecodePatchContext.hideFeedUfi(site: FeedUfiSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val registers = method.getFreeRegisterProvider(site.insert, 2, site.holder)
    val icon = registers.getFreeRegister()
    val count = registers.getFreeRegister()
    method.addInstructionsAtControlFlowLabel(
        site.insert,
        """
            iget-object v$icon, v${site.holder}, ${site.icon}
            iget-object v$count, v${site.holder}, ${site.count}
            invoke-static { v$icon, v$count }, $REPOSTS_FEED_UFI
        """,
    )
    // Native rebinding resets listeners/text, but not every icon's visibility. Remove only
    // our previous hide before it reads the holder, so native state always wins afterwards.
    val holderParameter = method.parameterTypes.indices.singleOrNull { method.parameterTypes[it] == site.icon.definingClass }
        ?: refuse("${site.type}->${site.name} has no unique Feed UFI holder parameter")
    method.requireLocals(PATCH, 3)
    val holder = method.parameterRegisterNumber(holderParameter)
    method.addInstructions(
        0,
        """
            move-object/from16 v0, v$holder
            iget-object v1, v0, ${site.icon}
            iget-object v2, v0, ${site.count}
            invoke-static { v1, v2 }, $REPOSTS_FEED_RESTORE
        """,
    )
}

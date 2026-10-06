/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.seen.MarkReadHookTest.Companion.CHAT
import app.morphe.patches.instagram.direct.seen.MarkReadHookTest.Companion.CHOSEN
import app.morphe.patches.instagram.direct.seen.MarkReadHookTest.Companion.KEY
import app.morphe.patches.instagram.direct.seen.MarkReadHookTest.Companion.SESSION
import app.morphe.patches.instagram.direct.seen.MarkReadHookTest.Companion.TapTrace
import app.morphe.patches.instagram.direct.seen.MarkReadHookTest.Companion.assertRowOffer
import app.morphe.patches.instagram.direct.seen.MarkReadHookTest.Companion.assertTapGuard
import app.morphe.patches.instagram.direct.seen.MarkReadHookTest.Companion.traceTapGuard
import app.morphe.patches.instagram.direct.seen.NativeThreadSeenTest.Companion.threadClasses
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.ThreadSeenHookTest.Companion.assertThreadGuard
import app.morphe.patches.instagram.direct.seen.VisualSeenHookTest.Companion.snapshot
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mark as read on each declared build's own dex: Instagram's own Mark as read goes on the one
 * chat menu builder that offers Mark as unread, the menu's tap handler hands it to the extension
 * first, and the bridges are written from Instagram's own handler for marking a chat read.
 */
class NativeMarkReadTest {
    @Test fun declaredBuildsOfferMarkAsReadOnOneChatAndHandleItsTapFirst() = fixtures { bundle ->
        val classes = markReadClasses(bundle)
        val context = PatchContexts.of(classes.values)
        val seen = context.findThreadSeen()
        val found = context.findMarkRead(seen)
        val menu = found.markAsRead.definingClass
        assertEquals("Mark as read is a constant of the menu's own enum", menu, found.markAsRead.type)
        assertEquals(menu, found.action.parameterTypes[found.chosen].toString())
        assertEquals(THREAD_KEY, found.action.parameterTypes[found.key].toString())
        assertTrue(AccessFlags.STATIC.isSet(found.builder.accessFlags))
        assertEquals("Ljava/util/List;", found.builder.parameterTypes[found.rows].toString())
        assertEquals(USER_SESSION, found.session.type)
        assertEquals(found.action.definingClass, found.session.definingClass)
        assertEquals(7, found.bridges.size)

        val first = seen.handler.visualCode().first()
        val rows = found.builder.visualCode()
        val act = found.action.visualCode()
        val hooked = listOf(seen.handler, found.builder, found.action).map { "${it.definingClass}->${it.name}(" }
        val before = classes.mapValues { snapshot(it.value.methods) }
        context.readWithoutSeenReceipt()

        assertThreadGuard(seen.handler, seen.complete.toString(), first)
        assertRowOffer(found.builder, found.rows, found.markAsRead.toString(), rows)
        assertTapGuard(found.action, found.markAsRead.toString(), found.session.toString(), act)
        for (handled in listOf(true, false)) {
            val trace = traceTapGuard(found.action, found.chosen, found.thread, found.key, handled)
            assertEquals(TapTrace(listOf(CHOSEN, found.markAsRead.toString(), SESSION, CHAT, KEY), handled), trace)
        }
        for ((type, original) in before) {
            if (type == INSTAGRAM_CHATS) continue
            val now = snapshot(context.mutableClassDefBy(type).methods)
            assertEquals("native $type changed", original.filterNot { (name, _) -> hooked.any(name::startsWith) },
                now.filterNot { (name, _) -> hooked.any(name::startsWith) })
        }
        assertBridgesCallInstagram(context.mutableClassDefBy(INSTAGRAM_CHATS).methods.toList(), classes)
    }

    @Test fun dexBackedInstructionReReadsResolveTheSameTargets() = fixtures { bundle ->
        val types = markReadClasses(bundle).keys - THREAD_SEEN - VISUAL_SEEN - INSTAGRAM_CHATS
        val context = PatchContexts.of(FixtureDex.classesAsRead(bundle, types).values +
            ExtensionDex.classDef(THREAD_SEEN) + ExtensionDex.classDef(INSTAGRAM_CHATS))
        val seen = context.findThreadSeen()
        val found = context.findMarkRead(seen)
        val rows = found.builder.visualCode()
        val act = found.action.visualCode()
        context.readWithoutSeenReceipt()
        assertRowOffer(found.builder, found.rows, found.markAsRead.toString(), rows)
        assertTapGuard(found.action, found.markAsRead.toString(), found.session.toString(), act)
    }

    companion object {
        private val cached = mutableMapOf<String, Map<String, ClassDef>>()

        /**
         * The chat receipt's classes, plus the chat menu's enum, the classes whose methods take it or
         * read it into a list, Instagram's handler for marking a chat read, every class those name,
         * and the extension's hooks and bridges.
         */
        private fun markReadClasses(bundle: File): Map<String, ClassDef> = cached.getOrPut(bundle.absolutePath) {
            val classes = threadClasses(bundle).toMutableMap()
            val menu = FixtureDex.classesHolding(bundle, MARK_AS_UNREAD).single { candidate ->
                candidate.superclass == "Ljava/lang/Enum;" && candidate.methods.any { method ->
                    method.name == "<clinit>" && method.visualCode().any { it.visualString() == MARK_AS_READ }
                }
            }
            classes[menu.type] = menu
            FixtureDex.classesHolding(bundle, MARK_READ_HANDLER).forEach { classes[it.type] = it }
            FixtureDex.forEach(bundle) { dex ->
                for (candidate in dex.classes) {
                    if (candidate.type in classes) continue
                    if (candidate.methods.any { it.takes(menu.type) || it.readsInto(menu.type) }) {
                        classes[candidate.type] = ImmutableClassDef.of(candidate)
                    }
                }
            }
            val seen = PatchContexts.of(classes.values).findThreadSeen()
            val named = mutableSetOf(seen.mutation, THREAD_KEY)
            for (candidate in classes.values.toList()) for (method in candidate.methods) {
                if (method.takes(menu.type)) named += method.parameterTypes.map(Any::toString)
                if (method.visualCode().none { it.visualString() == MARK_READ_HANDLER }) continue
                for (instruction in method.visualCode()) {
                    when (val reference = instruction.visualReference()) {
                        is MethodReference -> named += reference.parameterTypes.map(Any::toString) + reference.definingClass + reference.returnType
                        is FieldReference -> named += listOf(reference.definingClass, reference.type)
                    }
                }
            }
            classes += FixtureDex.classes(bundle, named.filter { it.startsWith("L") && it !in classes }.toSet())
            classes[INSTAGRAM_CHATS] = ImmutableClassDef.of(ExtensionDex.classDef(INSTAGRAM_CHATS))
            classes
        }

        private fun Method.takes(type: String) = name != "<init>" && parameterTypes.any { it.toString() == type }

        private fun Method.readsInto(type: String) = AccessFlags.STATIC.isSet(accessFlags) &&
            parameterTypes.any { it.toString() == "Ljava/util/List;" } &&
            visualCode().any { (it.visualReference() as? FieldReference)?.definingClass == type }

        /** Each bridge calls or reads only what the native classes declare, so its body links on a phone. */
        private fun assertBridgesCallInstagram(bridges: List<Method>, classes: Map<String, ClassDef>) {
            for (bridge in bridges.filter { it.name != "<init>" }) {
                val code = bridge.visualCode()
                val reaches = code.mapNotNull { it.visualReference() }.filter { it is MethodReference || it is FieldReference }
                assertTrue("${bridge.name} reaches nothing of Instagram's", reaches.isNotEmpty())
                for (reference in reaches) {
                    val declared = when (reference) {
                        is MethodReference -> classes[reference.definingClass]?.methods?.any { it.toString() == reference.toString() } == true
                        is FieldReference -> classes[reference.definingClass]?.fields?.any { it.toString() == reference.toString() } == true
                        else -> false
                    }
                    assertTrue("${bridge.name} names $reference, which its class doesn't declare", declared)
                }
            }
        }
    }
}

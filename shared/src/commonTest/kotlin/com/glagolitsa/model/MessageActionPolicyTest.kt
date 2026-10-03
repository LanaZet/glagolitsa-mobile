// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageActionPolicyTest {
    private val me = "user-me"
    private val peer = "user-peer"
    private val dm = Chat(id = "dm-1", title = "peer", type = ChatType.DIRECT, member_ids = listOf(me, peer))
    private val group = Chat(id = "g-1", title = "Team", type = ChatType.GROUP, member_ids = listOf(me, peer))

    @Test
    fun incomingDm_hasReplyCopySelectAndDelete() {
        val msg = Message(id = "m1", chat_id = dm.id, sender_id = peer, body = "hi")
        val actions = MessageActionPolicy.availableActions(msg, dm, me)
        assertTrue(MessageAction.REPLY_CHAT in actions)
        assertTrue(MessageAction.PIN in actions)
        assertTrue(MessageAction.ADD_TO_FAVORITES in actions)
        assertTrue(MessageAction.COPY in actions)
        assertTrue(MessageAction.SELECT in actions)
        assertTrue(MessageAction.DELETE in actions)
        assertFalse(MessageAction.REPLY_THREAD in actions)
        // Pin sits right after reply actions for discoverability in the tap menu.
        assertEquals(1, actions.indexOf(MessageAction.PIN))
        assertTrue(actions.indexOf(MessageAction.PIN) < actions.indexOf(MessageAction.ADD_TO_FAVORITES))
    }

    @Test
    fun sendingMessage_hidesFavoritesAndDeleteButAllowsCancel() {
        val msg = Message(
            id = "m-send",
            chat_id = dm.id,
            sender_id = me,
            body = "pending",
            status = MessageStatus.SENDING,
        )
        val actions = MessageActionPolicy.availableActions(msg, dm, me)
        assertFalse(MessageAction.PIN in actions)
        assertFalse(MessageAction.ADD_TO_FAVORITES in actions)
        assertFalse(MessageAction.DELETE in actions)
        assertTrue(MessageAction.CANCEL_SEND in actions)
        assertTrue(MessageAction.REPLY_CHAT in actions)
    }

    @Test
    fun addToFavorites_label() {
        assertEquals("Добавить в избранное", MessageActionPolicy.label(MessageAction.ADD_TO_FAVORITES))
    }

    @Test
    fun pin_label() {
        assertEquals("Закрепить", MessageActionPolicy.label(MessageAction.PIN))
        assertEquals("Открепить", MessageActionPolicy.label(MessageAction.UNPIN))
    }

    @Test
    fun pinnedMessage_showsUnpinInsteadOfPin() {
        val msg = Message(id = "m1", chat_id = dm.id, sender_id = peer, body = "hi")
        val actions = MessageActionPolicy.availableActions(msg, dm, me, isPinned = true)
        assertTrue(MessageAction.UNPIN in actions)
        assertFalse(MessageAction.PIN in actions)
    }

    @Test
    fun mediaTapMenu_hasOpenDownloadForwardDelete() {
        val msg = Message(id = "m-file", chat_id = dm.id, sender_id = peer, body = "📎 sample.pdf")
        val actions = MessageActionPolicy.availableMediaActions(
            message = msg,
            currentUserId = me,
            canDownload = true,
            canOpen = true,
        )
        assertEquals(
            listOf(
                MessageAction.OPEN,
                MessageAction.DOWNLOAD,
                MessageAction.FORWARD,
                MessageAction.DELETE,
            ),
            actions,
        )
        assertEquals("Открыть", MessageActionPolicy.label(MessageAction.OPEN))
    }

    @Test
    fun attachmentViewerMenu_hasForwardReplyDownloadPin() {
        val msg = Message(id = "m1", chat_id = dm.id, sender_id = peer, body = "📎 clip.mp4")
        assertEquals(
            listOf(
                MessageAction.FORWARD,
                MessageAction.REPLY_CHAT,
                MessageAction.DOWNLOAD,
                MessageAction.PIN,
            ),
            MessageActionPolicy.availableAttachmentViewerActions(
                message = msg,
                canDownload = true,
                isPinned = false,
            ),
        )
    }

    @Test
    fun attachmentViewerMenu_sendingHidesForwardAndPin() {
        val msg = Message(
            id = "m-send",
            chat_id = dm.id,
            sender_id = me,
            body = "📎 loop.gif",
            status = MessageStatus.SENDING,
        )
        assertEquals(
            listOf(MessageAction.REPLY_CHAT, MessageAction.DOWNLOAD),
            MessageActionPolicy.availableAttachmentViewerActions(
                message = msg,
                canDownload = true,
                isPinned = false,
            ),
        )
    }

    @Test
    fun attachmentViewerMenu_pinnedShowsUnpinAndOmitsDownloadWhenUnavailable() {
        val msg = Message(id = "m1", chat_id = dm.id, sender_id = peer, body = "📎 photo.jpg")
        assertEquals(
            listOf(
                MessageAction.FORWARD,
                MessageAction.REPLY_CHAT,
                MessageAction.UNPIN,
            ),
            MessageActionPolicy.availableAttachmentViewerActions(
                message = msg,
                canDownload = false,
                isPinned = true,
            ),
        )
    }

    @Test
    fun imageMessage_hasDownloadAction() {
        val msg = Message(id = "m-img", chat_id = dm.id, sender_id = peer, body = "📎 photo")
        val actions = MessageActionPolicy.availableActions(
            message = msg,
            chat = dm,
            currentUserId = me,
            hasImageAttachment = true,
        )
        assertTrue(MessageAction.DOWNLOAD in actions)
        assertEquals("Загрузить", MessageActionPolicy.label(MessageAction.DOWNLOAD))
    }

    @Test
    fun sentMessage_hasSingleFavoriteActionAndForward() {
        val msg = Message(id = "m-fav", chat_id = dm.id, sender_id = peer, body = "share me")
        val actions = MessageActionPolicy.availableActions(msg, dm, me)

        assertTrue(MessageAction.FORWARD in actions)
        assertEquals(1, actions.count { it == MessageAction.ADD_TO_FAVORITES })
    }

    @Test
    fun ownGroupMessage_hasThreadAndDelete() {
        val msg = Message(id = "m2", chat_id = group.id, sender_id = me, body = "hello", status = MessageStatus.SENT)
        val actions = MessageActionPolicy.availableActions(msg, group, me)
        assertTrue(MessageAction.REPLY_THREAD in actions)
        assertTrue(MessageAction.DELETE in actions)
        assertFalse(MessageAction.RETRY in actions)
    }

    @Test
    fun failedOwn_hasRetry() {
        val msg = Message(id = "m3", chat_id = dm.id, sender_id = me, body = "x", status = MessageStatus.FAILED)
        val actions = MessageActionPolicy.availableActions(msg, dm, me)
        assertTrue(MessageAction.RETRY in actions)
        assertEquals("Переслать", MessageActionPolicy.label(MessageAction.RETRY))
    }

    @Test
    fun sendingOwn_hasRetryToResend() {
        val msg = Message(id = "m3s", chat_id = dm.id, sender_id = me, body = "x", status = MessageStatus.SENDING)
        val actions = MessageActionPolicy.availableActions(msg, dm, me)
        assertTrue(MessageAction.RETRY in actions)
        assertEquals("Отменить отправку", MessageActionPolicy.label(MessageAction.CANCEL_SEND))
    }

    @Test
    fun system_hasNoActions() {
        val msg = Message(id = "m4", chat_id = group.id, sender_id = "system", body = "joined")
        assertTrue(MessageActionPolicy.availableActions(msg, group, me, isSystemMessage = true).isEmpty())
    }

    @Test
    fun delete_isDestructive() {
        assertTrue(MessageActionPolicy.isDestructive(MessageAction.DELETE))
        assertTrue(MessageActionPolicy.isDestructive(MessageAction.CANCEL_SEND))
        assertFalse(MessageActionPolicy.isDestructive(MessageAction.COPY))
    }

    @Test
    fun canDeleteSelected_sentMessagesFromAnySender() {
        val own = Message(id = "a", chat_id = dm.id, sender_id = me, body = "1")
        val other = Message(id = "b", chat_id = dm.id, sender_id = peer, body = "2")
        val map = mapOf(own.id to own, other.id to other)
        assertTrue(MessageActionPolicy.canDeleteSelected(setOf("a"), map))
        assertTrue(MessageActionPolicy.canDeleteSelected(setOf("a", "b"), map))
    }

    @Test
    fun reactionToggle_replacesAndRemoves() {
        val empty = emptyList<MessageReaction>()
        val withHeart = empty.toggleMine("❤️", me)
        assertEquals(1, withHeart.size)
        val withThumb = withHeart.toggleMine("👍", me)
        assertEquals(1, withThumb.size)
        assertEquals("👍", withThumb.single().emoji)
        val cleared = withThumb.toggleMine("👍", me)
        assertTrue(cleared.isEmpty())
    }

    @Test
    fun reactionChips_aggregate() {
        val reactions = listOf(
            MessageReaction("❤️", me),
            MessageReaction("❤️", peer),
            MessageReaction("👍", peer),
        )
        val chips = reactions.toChips(me)
        assertEquals(2, chips.size)
        val heart = chips.first { it.emoji == "❤️" }
        assertEquals(2, heart.count)
        assertTrue(heart.reactedByMe)
    }
}

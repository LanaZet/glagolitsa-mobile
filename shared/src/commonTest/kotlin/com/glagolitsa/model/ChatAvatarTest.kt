// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatAvatarTest {
    @Test
    fun conversationIconUrl_trimsBlank() {
        assertNull(Chat(id = "c1", title = "News", type = ChatType.CHANNEL).conversationIconUrl)
        assertNull(
            Chat(id = "c1", title = "News", type = ChatType.CHANNEL, avatar_url = "  ").conversationIconUrl,
        )
        assertEquals(
            "data:image/png;base64,xx",
            Chat(
                id = "c1",
                title = "News",
                type = ChatType.CHANNEL,
                avatar_url = " data:image/png;base64,xx ",
            ).conversationIconUrl,
        )
    }

    @Test
    fun e2eAttachments_allowedInDmAndGroup_notChannel() {
        assertTrue(Chat(id = "dm", title = "bob", type = ChatType.DIRECT).supportsE2eAttachments)
        assertTrue(Chat(id = "g", title = "team", type = ChatType.GROUP).supportsE2eAttachments)
        assertFalse(Chat(id = "ch", title = "news", type = ChatType.CHANNEL).supportsE2eAttachments)
    }

    @Test
    fun channelResponse_toChat_copiesAvatar() {
        val chat = ChannelResponse(
            id = "ch-1",
            title = "News",
            avatar_url = "data:image/jpeg;base64,icon",
        ).toChat(creatorId = "owner")
        assertEquals("data:image/jpeg;base64,icon", chat.conversationIconUrl)
        assertEquals("owner", chat.creator_id)
    }
}

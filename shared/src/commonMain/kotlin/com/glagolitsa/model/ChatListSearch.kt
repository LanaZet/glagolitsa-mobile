// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import com.glagolitsa.auth.RegistrationValidation

/**
 * Pure search/filter rules for the Chats tab search field.
 *
 * Patterns from industry leaders (unit-testable, no Compose/IO):
 * - **Signal**: ContactSearch-style — query normalize + local match + rank
 * - **Element X**: RoomList filter — title substring, type sections (DM/group/channel)
 * - **Telegram**: chat list filter by title/preview; people search is a second section
 * - **Mattermost**: filter pipeline (query → type → visibility) before UI bind
 *
 * UI owns debounce; this object owns *what* matches and *how* results are ordered.
 */
object ChatListSearch {

    /** Remote people search only after this many normalized chars (Telegram/Signal UX). */
    const val REMOTE_PEOPLE_MIN_CHARS = 2

    const val PEOPLE_RESULT_LIMIT = 8

    enum class ChatFilter {
        All,
        KnownSenders,
        Unread,
        Blocked,
        Personal,
        Groups,
        Channels,
    }

    fun normalizeQuery(raw: String): String = raw.trim().lowercase()

    fun shouldSearchPeople(query: String): Boolean =
        normalizeQuery(query).isNotEmpty()

    fun shouldQueryRemotePeople(query: String): Boolean =
        RegistrationValidation.normalizeUsername(query).length >= REMOTE_PEOPLE_MIN_CHARS

    /**
     * Remote public-channel directory (same min length as people remote).
     * Private groups/DMs are never queried here — membership list only.
     */
    fun shouldSearchPublicChannels(query: String): Boolean =
        shouldQueryRemotePeople(query)

    /** Public channel is discoverable by every authenticated user (Telegram public channel / Matrix public room). */
    fun isPubliclyDiscoverable(chat: Chat): Boolean =
        chat.isChannel &&
            chat.visibility == ChatVisibility.PUBLIC &&
            !chat.slug.isNullOrBlank()

    /** Private group/channel must not appear in global discover for non-members. */
    fun isPrivateConversation(chat: Chat): Boolean =
        chat.visibility == ChatVisibility.PRIVATE ||
            (chat.isChannel && chat.visibility != ChatVisibility.PUBLIC)

    fun matchesTextQuery(chat: Chat, query: String): Boolean {
        val q = normalizeQuery(query)
        if (q.isEmpty()) return true
        return chat.title.lowercase().contains(q) ||
            chat.last_message?.lowercase()?.contains(q) == true ||
            chat.slug?.lowercase()?.contains(q) == true ||
            chat.description?.lowercase()?.contains(q) == true
    }

    /**
     * Chats section: filter chip + free-text over title/preview/slug.
     * [chats] is the caller's membership list only.
     */
    fun filterChats(
        chats: List<Chat>,
        query: String,
        filter: ChatFilter,
        unreadCounts: Map<String, Int> = emptyMap(),
    ): List<Chat> {
        return chats
            .filter { it.matchesChatFilter(filter, unreadCounts) }
            .filter { matchesTextQuery(it, query) }
    }

    /**
     * Merge joined chats with remote public-channel discover hits (Element directory / Telegram global).
     * - Public discover hits always visible to any user when query matches.
     * - Private remote hits are dropped unless already joined.
     * - Dedupes by id; joined chat wins (has unread/preview).
     */
    fun mergeJoinedAndPublicDiscover(
        joinedChats: List<Chat>,
        publicDiscoverHits: List<Chat>,
        query: String,
        filter: ChatFilter,
        unreadCounts: Map<String, Int> = emptyMap(),
    ): List<Chat> {
        val local = filterChats(joinedChats, query, filter, unreadCounts)
        if (normalizeQuery(query).isEmpty()) return local

        // Discover section only makes sense for All / Channels filters.
        if (filter != ChatFilter.All && filter != ChatFilter.Channels) {
            return local
        }

        val joinedIds = joinedChats.map { it.id }.toSet()
        val discover = publicDiscoverHits
            .filter { isPubliclyDiscoverable(it) }
            .filter { matchesTextQuery(it, query) }
            .filter { it.id !in joinedIds }
            .filter {
                when (filter) {
                    ChatFilter.Channels -> it.isChannel
                    else -> true
                }
            }

        // Joined first (Telegram: your chats above global), then discover.
        return local + discover
    }

    /**
     * Whether this chat may be shown to [viewerId] in search results.
     * Public channels: everyone. Others: only if already a member (in joined set).
     */
    fun isVisibleInSearchToUser(
        chat: Chat,
        viewerId: String?,
        joinedChatIds: Set<String>,
    ): Boolean {
        if (isPubliclyDiscoverable(chat)) return true
        return chat.id in joinedChatIds
    }

    fun Chat.matchesChatFilter(
        filter: ChatFilter,
        unreadCounts: Map<String, Int>,
    ): Boolean = when (filter) {
        ChatFilter.All,
        ChatFilter.KnownSenders -> true
        ChatFilter.Unread -> (unreadCounts[id] ?: 0) > 0
        ChatFilter.Blocked -> false
        ChatFilter.Personal -> isDirectMessage
        ChatFilter.Groups -> type == ChatType.GROUP
        ChatFilter.Channels -> type == ChatType.CHANNEL
    }

    /**
     * People section: drop self and users who already have an open DM in the list
     * (Telegram: don't re-offer existing 1:1 as "new person").
     */
    fun filterPeopleForDisplay(
        people: List<User>,
        currentUserId: String?,
        existingDmPartnerIds: Set<String>,
    ): List<User> =
        people.filter { user ->
            user.id != currentUserId && user.id !in existingDmPartnerIds
        }

    /**
     * Local cache match — Signal ContactSearch style (prefix username OR display contains).
     */
    fun matchLocalPeople(
        knownUsers: Collection<User>,
        query: String,
        currentUserId: String?,
    ): List<User> {
        val normalized = RegistrationValidation.normalizeUsername(query)
        if (normalized.isEmpty()) return emptyList()
        return knownUsers
            .filter { user ->
                user.id != currentUserId &&
                    (
                        user.username.startsWith(normalized) ||
                            user.displayLabel().lowercase().contains(normalized)
                        )
            }
            .sortedBy { it.username }
    }

    /**
     * Merge local + remote, de-dupe, rank: exact username first, then shorter names
     * (Element/Telegram autocomplete rank).
     */
    fun mergeAndRankPeople(
        localHits: List<User>,
        remoteHits: List<User>,
        query: String,
        limit: Int = PEOPLE_RESULT_LIMIT,
    ): List<User> {
        val normalized = RegistrationValidation.normalizeUsername(query)
        if (normalized.isEmpty()) return emptyList()
        return (localHits + remoteHits)
            .distinctBy { it.id }
            .sortedWith(
                compareBy<User> { it.username != normalized }
                    .thenBy { it.username.length }
                    .thenBy { it.username },
            )
            .take(limit)
    }

    /**
     * Full people pipeline for a query given local cache + optional remote results.
     * When [remoteHits] is null and query is short, only local is used.
     */
    fun resolvePeopleResults(
        knownUsers: Collection<User>,
        query: String,
        currentUserId: String?,
        remoteHits: List<User>? = null,
        existingDmPartnerIds: Set<String> = emptySet(),
        limit: Int = PEOPLE_RESULT_LIMIT,
    ): List<User> {
        val local = matchLocalPeople(knownUsers, query, currentUserId)
        val merged = if (remoteHits == null) {
            local.distinctBy { it.id }.take(limit)
        } else {
            mergeAndRankPeople(local, remoteHits, query, limit)
        }
        return filterPeopleForDisplay(merged, currentUserId, existingDmPartnerIds)
    }
}

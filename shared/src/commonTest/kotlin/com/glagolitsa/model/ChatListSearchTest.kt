// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Chats-tab search matrix (Signal ContactSearch + Element RoomList + Telegram sections).
 */
class ChatListSearchTest {

    private val me = User(id = "me", username = "alice")
    private val bob = User(id = "bob", username = "bob", display_name = "Bob Builder")
    private val carol = User(id = "carol", username = "carol", display_name = "Carol")
    private val dave = User(id = "dave", username = "davey")

    private val dmBob = Chat(
        id = "dm-bob",
        title = "bob",
        type = ChatType.DIRECT,
        last_message = "привет",
        member_ids = listOf(me.id, bob.id),
    )
    private val groupDev = Chat(
        id = "g-dev",
        title = "Dev Team",
        type = ChatType.GROUP,
        last_message = "deploy done",
    )
    private val channelNews = Chat(
        id = "ch-news",
        title = "Glagolitsa News",
        type = ChatType.CHANNEL,
        slug = "glag_news",
        last_message = "release notes",
    )
    private val allChats = listOf(dmBob, groupDev, channelNews)

    // --- Chat list filter (Element RoomList / Mattermost type filters) ---

    @Test
    fun filterChats_blankQuery_returnsAllForAllFilter() {
        val result = ChatListSearch.filterChats(allChats, query = "  ", filter = ChatListSearch.ChatFilter.All)
        assertEquals(3, result.size)
    }

    @Test
    fun filterChats_byTitleSubstring_caseInsensitive() {
        val result = ChatListSearch.filterChats(allChats, query = "DEV", filter = ChatListSearch.ChatFilter.All)
        assertEquals(listOf(groupDev.id), result.map { it.id })
    }

    @Test
    fun filterChats_byLastMessagePreview() {
        val result = ChatListSearch.filterChats(allChats, query = "привет", filter = ChatListSearch.ChatFilter.All)
        assertEquals(listOf(dmBob.id), result.map { it.id })
    }

    @Test
    fun filterChats_byChannelSlug() {
        val result = ChatListSearch.filterChats(allChats, query = "glag_news", filter = ChatListSearch.ChatFilter.All)
        assertEquals(listOf(channelNews.id), result.map { it.id })
    }

    @Test
    fun filterChats_personalOnly() {
        val result = ChatListSearch.filterChats(
            allChats,
            query = "",
            filter = ChatListSearch.ChatFilter.Personal,
        )
        assertEquals(listOf(dmBob.id), result.map { it.id })
    }

    @Test
    fun filterChats_groupsOnly() {
        val result = ChatListSearch.filterChats(
            allChats,
            query = "",
            filter = ChatListSearch.ChatFilter.Groups,
        )
        assertEquals(listOf(groupDev.id), result.map { it.id })
    }

    @Test
    fun filterChats_channelsOnly() {
        val result = ChatListSearch.filterChats(
            allChats,
            query = "",
            filter = ChatListSearch.ChatFilter.Channels,
        )
        assertEquals(listOf(channelNews.id), result.map { it.id })
    }

    @Test
    fun filterChats_unreadAndQuery() {
        val unread = mapOf(groupDev.id to 2, dmBob.id to 0)
        val result = ChatListSearch.filterChats(
            allChats,
            query = "dev",
            filter = ChatListSearch.ChatFilter.Unread,
            unreadCounts = unread,
        )
        assertEquals(listOf(groupDev.id), result.map { it.id })
    }

    @Test
    fun filterChats_noMatch_empty() {
        val result = ChatListSearch.filterChats(allChats, query = "zzzz", filter = ChatListSearch.ChatFilter.All)
        assertTrue(result.isEmpty())
    }

    // --- People search policy (Signal min chars + Telegram dual section) ---

    @Test
    fun shouldQueryRemotePeople_requiresTwoChars() {
        assertFalse(ChatListSearch.shouldQueryRemotePeople("a"))
        assertFalse(ChatListSearch.shouldQueryRemotePeople(" A "))
        assertTrue(ChatListSearch.shouldQueryRemotePeople("al"))
        assertTrue(ChatListSearch.shouldQueryRemotePeople("bob"))
    }

    @Test
    fun shouldSearchPeople_falseWhenBlank() {
        assertFalse(ChatListSearch.shouldSearchPeople("   "))
        assertTrue(ChatListSearch.shouldSearchPeople("b"))
    }

    @Test
    fun matchLocalPeople_prefixAndDisplayName() {
        val known = listOf(me, bob, carol, dave)
        val byPrefix = ChatListSearch.matchLocalPeople(known, "bo", me.id)
        assertEquals(listOf(bob.id), byPrefix.map { it.id })

        val byDisplay = ChatListSearch.matchLocalPeople(known, "builder", me.id)
        assertEquals(listOf(bob.id), byDisplay.map { it.id })
    }

    @Test
    fun matchLocalPeople_excludesSelf() {
        val hits = ChatListSearch.matchLocalPeople(listOf(me, bob), "ali", me.id)
        assertTrue(hits.none { it.id == me.id })
    }

    @Test
    fun mergeAndRankPeople_exactUsernameFirstThenShorter() {
        val exact = User(id = "1", username = "ann")
        val longer = User(id = "2", username = "anna")
        val other = User(id = "3", username = "annette")
        val ranked = ChatListSearch.mergeAndRankPeople(
            localHits = listOf(longer, other),
            remoteHits = listOf(exact),
            query = "ann",
        )
        assertEquals(listOf("ann", "anna", "annette"), ranked.map { it.username })
    }

    @Test
    fun mergeAndRankPeople_dedupesById() {
        val ranked = ChatListSearch.mergeAndRankPeople(
            localHits = listOf(bob),
            remoteHits = listOf(bob.copy(display_name = "Remote Bob")),
            query = "bob",
        )
        assertEquals(1, ranked.size)
        assertEquals(bob.id, ranked.single().id)
    }

    @Test
    fun filterPeopleForDisplay_hidesExistingDmPartners() {
        val shown = ChatListSearch.filterPeopleForDisplay(
            people = listOf(bob, carol, dave),
            currentUserId = me.id,
            existingDmPartnerIds = setOf(bob.id),
        )
        assertEquals(listOf(carol.id, dave.id), shown.map { it.id })
    }

    @Test
    fun resolvePeopleResults_shortQuery_localOnly() {
        val resolved = ChatListSearch.resolvePeopleResults(
            knownUsers = listOf(me, bob, carol),
            query = "b",
            currentUserId = me.id,
            remoteHits = null,
            existingDmPartnerIds = emptySet(),
        )
        assertEquals(listOf(bob.id), resolved.map { it.id })
    }

    @Test
    fun resolvePeopleResults_withRemote_andExistingDmHidden() {
        val remoteOnly = User(id = "eve", username = "eve")
        val resolved = ChatListSearch.resolvePeopleResults(
            knownUsers = listOf(me, bob, carol),
            query = "e",
            currentUserId = me.id,
            remoteHits = listOf(remoteOnly, bob),
            existingDmPartnerIds = setOf(bob.id),
        )
        // "e" matches nothing local for bob/carol by prefix of username "e"...
        // carol doesn't start with e; eve does via remote. bob hidden as existing DM.
        assertTrue(resolved.any { it.id == remoteOnly.id })
        assertFalse(resolved.any { it.id == bob.id })
    }

    // --- Public channel discover (Telegram global / Element room directory) ---

    private val publicNews = Chat(
        id = "pub-1",
        title = "City News",
        type = ChatType.CHANNEL,
        visibility = ChatVisibility.PUBLIC,
        slug = "city_news",
        description = "Daily headlines",
    )
    private val privateChannel = Chat(
        id = "priv-1",
        title = "Staff Only",
        type = ChatType.CHANNEL,
        visibility = ChatVisibility.PRIVATE,
        slug = null,
    )
    private val publicSports = Chat(
        id = "pub-2",
        title = "Sports Hub",
        type = ChatType.CHANNEL,
        visibility = ChatVisibility.PUBLIC,
        slug = "sports_hub",
    )

    @Test
    fun publiclyDiscoverable_requiresPublicChannelWithSlug() {
        assertTrue(ChatListSearch.isPubliclyDiscoverable(publicNews))
        assertFalse(ChatListSearch.isPubliclyDiscoverable(privateChannel))
        assertFalse(
            ChatListSearch.isPubliclyDiscoverable(
                publicNews.copy(slug = null),
            ),
        )
        assertFalse(ChatListSearch.isPubliclyDiscoverable(dmBob))
        assertFalse(ChatListSearch.isPubliclyDiscoverable(groupDev))
    }

    @Test
    fun anyUser_canFindPublicChannel_bySlugOrTitle_evenIfNotJoined() {
        // Stranger: no channels in membership list.
        val joinedEmpty = emptyList<Chat>()
        val discoverHits = listOf(publicNews, publicSports, privateChannel)

        val bySlug = ChatListSearch.mergeJoinedAndPublicDiscover(
            joinedChats = joinedEmpty,
            publicDiscoverHits = discoverHits,
            query = "city_news",
            filter = ChatListSearch.ChatFilter.All,
        )
        assertEquals(listOf(publicNews.id), bySlug.map { it.id })

        val byTitle = ChatListSearch.mergeJoinedAndPublicDiscover(
            joinedChats = joinedEmpty,
            publicDiscoverHits = discoverHits,
            query = "sports",
            filter = ChatListSearch.ChatFilter.All,
        )
        assertEquals(listOf(publicSports.id), byTitle.map { it.id })

        val byDescription = ChatListSearch.mergeJoinedAndPublicDiscover(
            joinedChats = joinedEmpty,
            publicDiscoverHits = discoverHits,
            query = "headlines",
            filter = ChatListSearch.ChatFilter.All,
        )
        assertEquals(listOf(publicNews.id), byDescription.map { it.id })
    }

    @Test
    fun privateChannel_neverInDiscover_forNonMember() {
        val result = ChatListSearch.mergeJoinedAndPublicDiscover(
            joinedChats = emptyList(),
            publicDiscoverHits = listOf(privateChannel, publicNews),
            query = "staff",
            filter = ChatListSearch.ChatFilter.All,
        )
        assertTrue(result.none { it.id == privateChannel.id })
        assertTrue(result.isEmpty())
    }

    @Test
    fun privateChannel_visibleOnlyWhenJoined() {
        val stranger = ChatListSearch.isVisibleInSearchToUser(
            chat = privateChannel,
            viewerId = "stranger",
            joinedChatIds = emptySet(),
        )
        assertFalse(stranger)

        val member = ChatListSearch.isVisibleInSearchToUser(
            chat = privateChannel,
            viewerId = "member",
            joinedChatIds = setOf(privateChannel.id),
        )
        assertTrue(member)

        // Public always visible even without membership.
        assertTrue(
            ChatListSearch.isVisibleInSearchToUser(
                chat = publicNews,
                viewerId = "anyone",
                joinedChatIds = emptySet(),
            ),
        )
    }

    @Test
    fun multiUser_samePublicChannel_appearsForEveryViewer() {
        val viewers = listOf("user-a", "user-b", "user-c")
        val hits = listOf(publicNews)
        viewers.forEach { viewer ->
            val result = ChatListSearch.mergeJoinedAndPublicDiscover(
                joinedChats = emptyList(), // none of them joined yet
                publicDiscoverHits = hits,
                query = "city",
                filter = ChatListSearch.ChatFilter.Channels,
            )
            assertEquals(
                listOf(publicNews.id),
                result.map { it.id },
                "viewer $viewer must find public channel",
            )
            assertTrue(
                ChatListSearch.isVisibleInSearchToUser(publicNews, viewer, emptySet()),
            )
        }
    }

    @Test
    fun joinedPublicChannel_notDuplicatedWhenAlsoInDiscover() {
        val joined = listOf(publicNews.copy(last_message = "already open"))
        val discover = listOf(publicNews.copy(last_message = null))
        val result = ChatListSearch.mergeJoinedAndPublicDiscover(
            joinedChats = joined,
            publicDiscoverHits = discover,
            query = "city",
            filter = ChatListSearch.ChatFilter.All,
        )
        assertEquals(1, result.size)
        assertEquals("already open", result.single().last_message)
    }

    @Test
    fun discover_skippedForPersonalFilter() {
        val result = ChatListSearch.mergeJoinedAndPublicDiscover(
            joinedChats = emptyList(),
            publicDiscoverHits = listOf(publicNews),
            query = "city",
            filter = ChatListSearch.ChatFilter.Personal,
        )
        assertTrue(result.isEmpty())
    }

    /**
     * Regression: Bob creates private group "testing" — stranger must NOT see it in search.
     * Real-world bug report: unit tests passed while product search looked "broken"
     * because pure tests inject discover hits and never assert group isolation.
     */
    @Test
    fun privateGroup_testing_notDiscoverableToStranger_evenIfListedInDiscoverPayload() {
        val bobsPrivateGroup = Chat(
            id = "g-testing",
            title = "testing",
            type = ChatType.GROUP,
            visibility = ChatVisibility.PRIVATE,
            member_ids = listOf("bob"),
        )
        // Malicious/broken API might return a group in discover — client must drop it.
        val result = ChatListSearch.mergeJoinedAndPublicDiscover(
            joinedChats = emptyList(),
            publicDiscoverHits = listOf(bobsPrivateGroup, publicNews),
            query = "testing",
            filter = ChatListSearch.ChatFilter.All,
        )
        assertTrue(result.none { it.id == bobsPrivateGroup.id })
        assertFalse(ChatListSearch.isPubliclyDiscoverable(bobsPrivateGroup))
        assertFalse(
            ChatListSearch.isVisibleInSearchToUser(
                chat = bobsPrivateGroup,
                viewerId = "test",
                joinedChatIds = emptySet(),
            ),
        )
        // Member still sees it via joined list (title match).
        val asMember = ChatListSearch.mergeJoinedAndPublicDiscover(
            joinedChats = listOf(bobsPrivateGroup),
            publicDiscoverHits = emptyList(),
            query = "testing",
            filter = ChatListSearch.ChatFilter.All,
        )
        assertEquals(listOf(bobsPrivateGroup.id), asMember.map { it.id })
    }

    @Test
    fun publicChannel_testing_visibleToEveryUserWhenInDiscoverHits() {
        val publicTesting = Chat(
            id = "ch-testing",
            title = "testing",
            type = ChatType.CHANNEL,
            visibility = ChatVisibility.PUBLIC,
            slug = "testing",
        )
        listOf("test", "alice", "stranger").forEach { viewer ->
            val hits = ChatListSearch.mergeJoinedAndPublicDiscover(
                joinedChats = emptyList(),
                publicDiscoverHits = listOf(publicTesting),
                query = "testing",
                filter = ChatListSearch.ChatFilter.All,
            )
            assertEquals(
                listOf(publicTesting.id),
                hits.map { it.id },
                "viewer=$viewer must find public channel testing",
            )
        }
    }
}

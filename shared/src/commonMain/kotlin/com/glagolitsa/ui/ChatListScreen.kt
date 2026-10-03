// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.glagolitsa.ui
import kotlin.math.PI

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glagolitsa.model.Chat
import com.glagolitsa.model.ChatDeletePolicy
import com.glagolitsa.model.ChatListSearch
import com.glagolitsa.model.User
import com.glagolitsa.model.avatarLabel
import com.glagolitsa.model.displayLabel
import com.glagolitsa.model.formatMessageTime
import com.glagolitsa.model.isOnlineLike
import com.glagolitsa.model.listAvatarLabel
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.session.SessionStore
import com.glagolitsa.ui.components.AppEmptyState
import com.glagolitsa.ui.components.avatarFrameShadow
import com.glagolitsa.ui.components.avatarGlassBezel
import com.glagolitsa.ui.components.convexSurface
import com.glagolitsa.ui.chat.ChatListSwipeDeletePolicy
import com.glagolitsa.ui.chat.ConversationRemoveConfirmDialog
import com.glagolitsa.ui.chat.ConversationIconEditor
import com.glagolitsa.ui.layout.ChatLayout
import com.glagolitsa.ui.profile.ProfileAvatar
import com.glagolitsa.ui.profile.presenceRingColor
import com.glagolitsa.ui.theme.GlagolitsaColors
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.chat_list_create_icon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// UI filter mirrors [ChatListSearch.ChatFilter] for Chats-tab chips/menu.
private enum class ChatListFilter {
    All,
    KnownSenders,
    Unread,
    Blocked,
    Personal,
    Groups,
    Channels,
}

private const val CHAT_LIST_PRESENCE_REFRESH_INTERVAL_MS = 15_000L

private fun ChatListFilter.toSearchFilter(): ChatListSearch.ChatFilter = when (this) {
    ChatListFilter.All -> ChatListSearch.ChatFilter.All
    ChatListFilter.KnownSenders -> ChatListSearch.ChatFilter.KnownSenders
    ChatListFilter.Unread -> ChatListSearch.ChatFilter.Unread
    ChatListFilter.Blocked -> ChatListSearch.ChatFilter.Blocked
    ChatListFilter.Personal -> ChatListSearch.ChatFilter.Personal
    ChatListFilter.Groups -> ChatListSearch.ChatFilter.Groups
    ChatListFilter.Channels -> ChatListSearch.ChatFilter.Channels
}

private data class ChatFilterMenuItem(
    val filter: ChatListFilter?,
    val label: String,
    val enabled: Boolean = true,
)

@Composable
fun ChatListScreen(
    repository: MessengerRepository,
    onChatSelected: (Chat) -> Unit,
    onCreateChannel: () -> Unit = {},
    bottomContentPadding: Dp = 0.dp,
) {
    val scope = rememberCoroutineScope()
    val currentUserId = SessionStore.user.value?.id
    val chats by repository.observeChats().collectAsState(initial = emptyList())
    val knownUserProfiles by repository.knownUserProfiles.collectAsState()
    val unreadCounts by repository.unreadCounts.collectAsState()
    val presenceMap by repository.presence.partnerPresence.collectAsState()
    var selectedFilter by remember { mutableStateOf(ChatListFilter.All) }
    var searchQuery by remember { mutableStateOf("") }
    var peopleSearchMode by remember { mutableStateOf(false) }
    var peopleResults by remember { mutableStateOf<List<User>>(emptyList()) }
    var peopleSearching by remember { mutableStateOf(false) }
    var openingUserId by remember { mutableStateOf<String?>(null) }
    var peopleSearchError by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(true) }
    var createMenuExpanded by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showJoinByLink by remember { mutableStateOf(false) }
    var joinLink by remember { mutableStateOf("") }
    var joinError by remember { mutableStateOf<String?>(null) }
    var joiningByLink by remember { mutableStateOf(false) }
    var groupTitle by remember { mutableStateOf("") }
    var groupAvatarUrl by remember { mutableStateOf<String?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var publicDiscoverHits by remember { mutableStateOf<List<Chat>>(emptyList()) }
    var publicDiscoverError by remember { mutableStateOf<String?>(null) }
    var chatPendingDelete by remember { mutableStateOf<Chat?>(null) }
    var listActionError by remember { mutableStateOf<String?>(null) }

    val quickFilters = remember {
        listOf(
            ChatListFilter.Unread to "Непроч.",
            ChatListFilter.Personal to "Личные",
            ChatListFilter.Groups to "Группы",
            ChatListFilter.Channels to "Каналы",
        )
    }
    val filterMenuItems = remember {
        listOf(
            ChatFilterMenuItem(ChatListFilter.All, "Все сообщения"),
            ChatFilterMenuItem(ChatListFilter.KnownSenders, "Известные отправители"),
            ChatFilterMenuItem(null, "Неизвестные отправители", enabled = false),
            ChatFilterMenuItem(ChatListFilter.Blocked, "Заблокированные"),
            ChatFilterMenuItem(null, "Спам", enabled = false),
            ChatFilterMenuItem(null, "Недавно удаленные", enabled = false),
        )
    }

    LaunchedEffect(repository) {
        syncing = true
        runCatching { repository.syncChats() }
        syncing = false
    }

    LaunchedEffect(chats) {
        val partnerIds = chats
            .filter { it.isDirectMessage }
            .mapNotNull { repository.dmPartnerFor(it.id) }
            .distinct()
        if (partnerIds.isNotEmpty()) {
            // Signal/Mattermost: load partner cards (incl. avatar) for list discs.
            runCatching { repository.ensureUserProfiles(partnerIds) }
                .onFailure { repository.recoverAuthFailure(it) }
            while (true) {
                runCatching { repository.presence.fetchUsers(partnerIds) }
                    .onFailure { repository.recoverAuthFailure(it) }
                delay(CHAT_LIST_PRESENCE_REFRESH_INTERVAL_MS)
            }
        }
    }

    val visibleChats = remember(chats, selectedFilter, searchQuery, unreadCounts, publicDiscoverHits) {
        ChatListSearch.mergeJoinedAndPublicDiscover(
            joinedChats = chats,
            publicDiscoverHits = publicDiscoverHits,
            query = searchQuery,
            filter = selectedFilter.toSearchFilter(),
            unreadCounts = unreadCounts,
        )
    }

    val dmPartnerIds = remember(chats) {
        chats
            .filter { it.isDirectMessage }
            .mapNotNull { repository.dmPartnerFor(it.id) }
            .toSet()
    }

    val peopleToShow = remember(peopleResults, dmPartnerIds, currentUserId) {
        ChatListSearch.filterPeopleForDisplay(
            people = peopleResults,
            currentUserId = currentUserId,
            existingDmPartnerIds = dmPartnerIds,
        )
    }

    LaunchedEffect(searchQuery) {
        val query = searchQuery.trim()
        peopleSearchError = null
        publicDiscoverError = null
        if (!ChatListSearch.shouldSearchPeople(query)) {
            peopleResults = emptyList()
            publicDiscoverHits = emptyList()
            peopleSearching = false
            return@LaunchedEffect
        }
        if (!ChatListSearch.shouldQueryRemotePeople(query)) {
            peopleResults = runCatching { repository.searchPeople(query) }.getOrDefault(emptyList())
            publicDiscoverHits = emptyList()
            peopleSearching = false
            return@LaunchedEffect
        }
        delay(200)
        peopleSearching = true
        peopleResults = runCatching { repository.searchPeople(query) }
            .onFailure { peopleSearchError = "Не удалось найти пользователей" }
            .getOrDefault(emptyList())
        // Global directory = public channels only (not private groups Bob creates without you).
        publicDiscoverHits = runCatching { repository.searchPublicChannels(query) }
            .onFailure {
                publicDiscoverError = "Поиск публичных каналов недоступен"
                publicDiscoverHits = emptyList()
            }
            .getOrDefault(emptyList())
        peopleSearching = false
    }

    fun openDirectMessage(user: User) {
        if (openingUserId != null) return
        repository.rememberUsers(listOf(user))
        openingUserId = user.id
        scope.launch {
            runCatching { repository.openDM(user.id) }
                .onSuccess { chat ->
                    searchQuery = ""
                    peopleSearchMode = false
                    peopleResults = emptyList()
                    publicDiscoverHits = emptyList()
                    publicDiscoverError = null
                    onChatSelected(chat)
                }
                .onFailure {
                    peopleSearchError = "Не удалось открыть чат"
                }
            openingUserId = null
        }
    }

    /** Open joined chat, or join public channel from discover then open. */
    fun openChatFromSearch(chat: Chat) {
        val joinedIds = chats.map { it.id }.toSet()
        if (chat.id in joinedIds) {
            onChatSelected(chat)
            return
        }
        val slug = chat.slug
        if (ChatListSearch.isPubliclyDiscoverable(chat) && !slug.isNullOrBlank()) {
            scope.launch {
                runCatching { repository.joinChannelBySlug(slug) }
                    .onSuccess { joined ->
                        searchQuery = ""
                        publicDiscoverHits = emptyList()
                        onChatSelected(joined)
                    }
                    .onFailure {
                        peopleSearchError = "Не удалось вступить в канал"
                    }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GlagolitsaColors.Background950),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = ChatLayout.listHorizontalPadding,
                    vertical = ChatLayout.listVerticalPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(ChatLayout.listSectionSpacing),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                ChatListHeader(
                    createMenuExpanded = createMenuExpanded,
                    onCreateMenuExpandedChange = { createMenuExpanded = it },
                    onCreateGroup = {
                        createMenuExpanded = false
                        groupTitle = ""
                        createError = null
                        groupAvatarUrl = null
                        showCreateGroupDialog = true
                    },
                    onCreateChannel = {
                        createMenuExpanded = false
                        onCreateChannel()
                    },
                    onJoinByLink = {
                        createMenuExpanded = false
                        joinLink = ""
                        joinError = null
                        showJoinByLink = true
                    },
                )

                ChatSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = if (peopleSearchMode) "Найти @username" else "Поиск чатов и людей",
                    filterMenuItems = filterMenuItems,
                    selectedFilter = selectedFilter,
                    onFilterSelected = { selectedFilter = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(
                    ChatLayout.listFilterSpacing,
                    Alignment.CenterHorizontally,
                ),
            ) {
                quickFilters.forEach { (filter, label) ->
                    FilterChip(
                        text = label,
                        selected = filter == selectedFilter,
                        onClick = { selectedFilter = filter },
                    )
                }
            }

            when {
                syncing && chats.isEmpty() && searchQuery.isBlank() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = GlagolitsaColors.AccentRed,
                            strokeWidth = 2.dp,
                        )
                    }
                }

                visibleChats.isEmpty() && peopleToShow.isEmpty() && !peopleSearching && peopleSearchError == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChatListEmptyState(
                            hasChats = chats.isNotEmpty(),
                            searchQuery = searchQuery,
                            selectedFilter = selectedFilter,
                            peopleSearchMode = peopleSearchMode,
                        )
                    }
                }

                else -> {
                    listActionError?.let { error ->
                        Text(
                            text = error,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = GlagolitsaColors.AccentRed,
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = bottomContentPadding + 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (visibleChats.isNotEmpty()) {
                            if (searchQuery.isNotBlank()) {
                                item(key = "section-chats") {
                                    SearchSectionHeader(title = "Чаты")
                                }
                            }
                            items(
                                visibleChats,
                                key = { "chat-${it.id}" },
                                contentType = {
                                    when {
                                        it.isChannel -> "channel"
                                        it.isGroup -> "group"
                                        else -> "dm"
                                    }
                                },
                            ) { chat ->
                                val partnerId = if (chat.isDirectMessage) repository.dmPartnerFor(chat.id) else null
                                val presenceColor = partnerId?.let { presenceMap[it]?.presenceRingColor() }
                                val avatarUrl = if (chat.isDirectMessage) {
                                    partnerId?.let { id ->
                                        knownUserProfiles[id]?.avatar_url
                                            ?: repository.avatarUrlForUser(id)
                                    }
                                } else {
                                    chat.conversationIconUrl
                                }
                                ChatListRow(
                                    chat = chat,
                                    avatarUrl = avatarUrl,
                                    unreadCount = unreadCounts[chat.id] ?: 0,
                                    presenceColor = presenceColor,
                                    onClick = { openChatFromSearch(chat) },
                                    onDeleteRequest = { chatPendingDelete = chat },
                                )
                            }
                        } else if (searchQuery.isNotBlank() && publicDiscoverError != null) {
                            item(key = "discover-error") {
                                Text(
                                    text = publicDiscoverError.orEmpty(),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GlagolitsaColors.AccentRed,
                                )
                            }
                        } else if (
                            searchQuery.isNotBlank() &&
                            !peopleSearching &&
                            publicDiscoverError == null &&
                            searchQuery.trim().length >= 2
                        ) {
                            item(key = "chats-empty-hint") {
                                Text(
                                    text = "Приватные группы видны только участникам. В глобальном поиске — публичные каналы и @username.",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GlagolitsaColors.TextTertiary,
                                )
                            }
                        }

                        if (searchQuery.isNotBlank()) {
                            item(key = "section-people") {
                                SearchSectionHeader(
                                    title = "Люди",
                                    trailing = if (peopleSearching) "…" else null,
                                )
                            }
                            if (peopleSearchError != null) {
                                item(key = "people-error") {
                                    Text(
                                        text = peopleSearchError.orEmpty(),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GlagolitsaColors.AccentRed,
                                    )
                                }
                            }
                            items(peopleToShow, key = { "user-${it.id}" }) { user ->
                                val presenceColor = presenceMap[user.id]?.presenceRingColor()
                                UserSearchRow(
                                    user = user,
                                    presenceColor = presenceColor,
                                    loading = openingUserId == user.id,
                                    onClick = { openDirectMessage(user) },
                                )
                            }
                            if (!peopleSearching && peopleToShow.isEmpty() && peopleSearchError == null && searchQuery.length >= 2) {
                                item(key = "people-empty") {
                                    Text(
                                        text = "Пользователи не найдены",
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GlagolitsaColors.TextTertiary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showCreateGroupDialog) {
            CreateGroupDialog(
                title = groupTitle,
                onTitleChange = { groupTitle = it },
                avatarUrl = groupAvatarUrl,
                onAvatarPicked = { groupAvatarUrl = it },
                error = createError,
                loading = creatingGroup,
                onDismiss = {
                    if (!creatingGroup) {
                        showCreateGroupDialog = false
                        groupAvatarUrl = null
                    }
                },
                onCreate = {
                    if (creatingGroup || groupTitle.trim().isEmpty()) return@CreateGroupDialog
                    creatingGroup = true
                    createError = null
                    scope.launch {
                        runCatching {
                            repository.createGroup(
                                title = groupTitle.trim(),
                                avatarUrl = groupAvatarUrl,
                            )
                        }
                            .onSuccess { chat ->
                                showCreateGroupDialog = false
                                groupAvatarUrl = null
                                creatingGroup = false
                                onChatSelected(chat)
                            }
                            .onFailure {
                                createError = it.message ?: "Не удалось создать группу"
                                creatingGroup = false
                            }
                    }
                },
            )
        }

        if (showJoinByLink) {
            JoinByLinkDialog(
                value = joinLink,
                error = joinError,
                loading = joiningByLink,
                onValueChange = { joinLink = it },
                onDismiss = {
                    if (!joiningByLink) showJoinByLink = false
                },
                onJoin = {
                    if (joiningByLink || joinLink.isBlank()) return@JoinByLinkDialog
                    joiningByLink = true
                    joinError = null
                    scope.launch {
                        runCatching { repository.openInviteLink(joinLink) }
                            .onSuccess { opened ->
                                joiningByLink = false
                                showJoinByLink = false
                                onChatSelected(opened)
                            }
                            .onFailure {
                                joinError = it.message ?: "Не удалось вступить"
                                joiningByLink = false
                            }
                    }
                },
            )
        }

        chatPendingDelete?.let { chat ->
            val deleteAction = ChatDeletePolicy.action(chat, currentUserId)
            ConversationRemoveConfirmDialog(
                chat = chat,
                action = deleteAction,
                onDismiss = { chatPendingDelete = null },
                onConfirm = {
                    chatPendingDelete = null
                    listActionError = null
                    scope.launch {
                        runCatching { repository.removeConversation(chat) }
                            .onFailure {
                                listActionError = it.message ?: "Не удалось удалить чат"
                            }
                    }
                },
            )
        }
    }
}

@Composable
private fun CreateGroupDialog(
    title: String,
    onTitleChange: (String) -> Unit,
    avatarUrl: String?,
    onAvatarPicked: (String) -> Unit,
    error: String?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(GlagolitsaColors.Background900)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Новая группа",
                style = MaterialTheme.typography.titleLarge,
                color = GlagolitsaColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            ConversationIconEditor(
                avatarUrl = avatarUrl,
                fallbackLabel = title.ifBlank { "Гр" },
                onPicked = onAvatarPicked,
                enabled = !loading,
                size = 80.dp,
                hint = "Иконка из галереи",
            )
            com.glagolitsa.ui.components.GlassTextField(
                value = title,
                onValueChange = onTitleChange,
                label = "Название",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            error?.let {
                Text(it, color = GlagolitsaColors.AccentRed, style = MaterialTheme.typography.bodySmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "Отмена",
                    color = GlagolitsaColors.TextSecondary,
                    modifier = Modifier
                        .clickable(enabled = !loading, onClick = onDismiss)
                        .padding(12.dp),
                )
                Text(
                    text = if (loading) "…" else "Создать",
                    color = if (title.trim().isNotEmpty() && !loading) {
                        GlagolitsaColors.AccentRed
                    } else {
                        GlagolitsaColors.TextTertiary
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(
                            enabled = title.trim().isNotEmpty() && !loading,
                            onClick = onCreate,
                        )
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun JoinByLinkDialog(
    value: String,
    error: String?,
    loading: Boolean,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onJoin: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(GlagolitsaColors.Background900)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Вступить по ссылке",
                style = MaterialTheme.typography.titleLarge,
                color = GlagolitsaColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Группы и каналы приватные. Вставьте ссылку glagolitsa.app/c/… или glagolitsa.app/join/…",
                style = MaterialTheme.typography.bodySmall,
                color = GlagolitsaColors.TextTertiary,
            )
            com.glagolitsa.ui.components.GlassTextField(
                value = value,
                onValueChange = onValueChange,
                label = "Ссылка или токен",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            error?.let {
                Text(it, color = GlagolitsaColors.AccentRed, style = MaterialTheme.typography.bodySmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "Отмена",
                    color = GlagolitsaColors.TextSecondary,
                    modifier = Modifier
                        .clickable(enabled = !loading, onClick = onDismiss)
                        .padding(12.dp),
                )
                Text(
                    text = if (loading) "…" else "Вступить",
                    color = if (value.isNotBlank() && !loading) {
                        GlagolitsaColors.AccentRed
                    } else {
                        GlagolitsaColors.TextTertiary
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(
                            enabled = value.isNotBlank() && !loading,
                            onClick = onJoin,
                        )
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun ChatListEmptyState(
    hasChats: Boolean,
    searchQuery: String,
    selectedFilter: ChatListFilter,
    peopleSearchMode: Boolean = false,
) {
    val title = when {
        !hasChats && searchQuery.isBlank() -> "Пока нет чатов"
        searchQuery.isNotBlank() -> "Ничего не найдено"
        peopleSearchMode -> "Найти пользователя"
        selectedFilter == ChatListFilter.Unread -> "Непрочитанных нет"
        selectedFilter == ChatListFilter.Blocked -> "Заблокированных нет"
        selectedFilter == ChatListFilter.Channels -> "Каналов пока нет"
        else -> "Ничего не найдено"
    }
    val message = when {
        !hasChats && searchQuery.isBlank() -> "Личные диалоги и группы появятся здесь после синхронизации."
        searchQuery.isNotBlank() ->
            "Глобально ищутся люди и публичные каналы. Приватные группы видны только участникам."
        peopleSearchMode -> "Введите минимум 2 символа, например marco."
        selectedFilter == ChatListFilter.Unread -> "Новые сообщения появятся в этом фильтре."
        selectedFilter == ChatListFilter.Blocked -> "Заблокированные диалоги появятся в этом фильтре."
        selectedFilter == ChatListFilter.Channels -> "Создайте канал через «+» → «Новый канал» (публичный — со slug)."
        else -> "В этом фильтре сейчас нет подходящих чатов."
    }

    AppEmptyState(
        title = title,
        message = message,
    )
}

@Composable
private fun ChatSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    filterMenuItems: List<ChatFilterMenuItem>,
    selectedFilter: ChatListFilter,
    onFilterSelected: (ChatListFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outerShape = RoundedCornerShape(18.dp)
    val inputShape = RoundedCornerShape(17.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    var filterMenuExpanded by remember { mutableStateOf(false) }
    val light = !GlagolitsaColors.IsDark
    val lightInk = Color(0xFF6F604B)
    val inputBorderColor = if (focused) {
        GlagolitsaColors.TextSecondary.copy(alpha = 0.24f)
    } else if (light) {
        Color(0xFFD8C9AE).copy(alpha = 0.72f)
    } else {
        GlagolitsaColors.Surface700.copy(alpha = 0.58f)
    }
    val outerFill = if (light) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFBF6ED),
                Color(0xFFF3E9D9),
                Color(0xFFE9DCC6),
            ),
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                GlagolitsaColors.Background950,
                GlagolitsaColors.Background900,
                GlagolitsaColors.Surface800,
            ),
        )
    }
    val inputFill = if (light) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color(0xFFE6DDCE),
                0.22f to Color(0xFFF0E7D8),
                0.72f to Color(0xFFFBF6ED),
                1f to Color(0xFFF6EFE4),
            ),
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                GlagolitsaColors.Background900,
                GlagolitsaColors.Background950,
            ),
        )
    }

    Row(
        modifier = modifier
            .height(72.dp)
            .clip(outerShape)
            .background(outerFill)
            .drawBehind {
                val corner = 18.dp.toPx()
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.White.copy(alpha = if (light) 0.30f else 0.05f),
                            0.42f to Color.Transparent,
                            1f to if (light) {
                                lightInk.copy(alpha = 0.06f)
                            } else {
                                Color.Black.copy(alpha = 0.18f)
                            },
                        ),
                    ),
                    cornerRadius = CornerRadius(corner, corner),
                )
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.White.copy(alpha = if (light) 0.24f else 0.045f),
                            0.5f to Color.Transparent,
                            1f to if (light) {
                                lightInk.copy(alpha = 0.10f)
                            } else {
                                Color.Black.copy(alpha = 0.26f)
                            },
                        ),
                    ),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(width = 0.8.dp.toPx()),
                )
            }
            .border(
                1.dp,
                if (light) Color(0xFFE4D7BF).copy(alpha = 0.82f) else GlagolitsaColors.Surface700.copy(alpha = 0.58f),
                outerShape,
            )
            .padding(start = 8.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .clip(inputShape)
                .background(inputFill)
                .drawBehind {
                    val corner = 17.dp.toPx()
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to if (light) {
                                    lightInk.copy(alpha = 0.14f)
                                } else {
                                    Color.Black.copy(alpha = 0.42f)
                                },
                                0.48f to if (light) {
                                    lightInk.copy(alpha = 0.035f)
                                } else {
                                    Color.Black.copy(alpha = 0.13f)
                                },
                                1f to Color.Transparent,
                            ),
                            endY = size.height * 0.58f,
                        ),
                        cornerRadius = CornerRadius(corner, corner),
                    )
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0f to if (light) {
                                    lightInk.copy(alpha = 0.08f)
                                } else {
                                    Color.Black.copy(alpha = 0.25f)
                                },
                                0.58f to Color.Transparent,
                                1f to Color.White.copy(alpha = if (light) 0.34f else 0.025f),
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, size.height),
                        ),
                        cornerRadius = CornerRadius(corner, corner),
                    )
                }
                .border(1.dp, inputBorderColor, inputShape)
                .padding(start = 24.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ChatSearchIcon(
                tint = GlagolitsaColors.TextSecondary,
                modifier = Modifier.size(27.dp),
            )

            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = GlagolitsaColors.TextPrimary,
                    fontWeight = FontWeight.Normal,
                ),
                interactionSource = interactionSource,
                cursorBrush = SolidColor(GlagolitsaColors.TextPrimary),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.titleMedium,
                                color = GlagolitsaColors.TextTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }

        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.06f),
                                Color.Transparent,
                            ),
                            radius = size.minDimension * 0.62f,
                        ),
                    )
                }
                .clickable(onClick = { filterMenuExpanded = true })
                .semantics {
                    role = Role.Button
                    contentDescription = "Фильтр чатов"
                }
                .padding(1.dp),
            contentAlignment = Alignment.Center,
        ) {
            ChatSearchFilterIcon(
                tint = if (filterMenuExpanded) GlagolitsaColors.OrnamentGold else GlagolitsaColors.TextSecondary,
                modifier = Modifier.size(31.dp),
            )
            DropdownMenu(
                expanded = filterMenuExpanded,
                onDismissRequest = { filterMenuExpanded = false },
                modifier = Modifier.background(GlagolitsaColors.SurfaceFloating),
            ) {
                filterMenuItems.forEach { item ->
                    val isSelected = item.filter == selectedFilter
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = item.label,
                                color = when {
                                    !item.enabled -> GlagolitsaColors.TextTertiary.copy(alpha = 0.52f)
                                    isSelected -> GlagolitsaColors.OrnamentGold
                                    else -> GlagolitsaColors.TextPrimary
                                },
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        enabled = item.enabled && item.filter != null,
                        onClick = {
                            item.filter?.let(onFilterSelected)
                            filterMenuExpanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatSearchIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 2.2.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        val radius = size.minDimension * 0.31f
        val center = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(
            color = Color.Black.copy(alpha = 0.45f),
            radius = radius,
            center = center + Offset(1.dp.toPx(), 1.dp.toPx()),
            style = stroke,
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.45f),
            start = Offset(size.width * 0.64f, size.height * 0.64f) + Offset(1.dp.toPx(), 1.dp.toPx()),
            end = Offset(size.width * 0.86f, size.height * 0.86f) + Offset(1.dp.toPx(), 1.dp.toPx()),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = tint,
            radius = radius,
            center = center,
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.64f, size.height * 0.64f),
            end = Offset(size.width * 0.86f, size.height * 0.86f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ChatSearchFilterIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.9.dp.toPx()
        val shadowOffset = Offset(1.dp.toPx(), 1.dp.toPx())
        val shadow = Color.Black.copy(alpha = 0.45f)

        fun drawFilter(color: Color, offset: Offset = Offset.Zero) {
            val left = size.width * 0.18f
            val right = size.width * 0.82f
            val topY = size.height * 0.28f
            val midY = size.height * 0.48f
            val bottomY = size.height * 0.68f
            drawLine(
                color = color,
                start = Offset(left, topY) + offset,
                end = Offset(right, topY) + offset,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(left + size.width * 0.1f, midY) + offset,
                end = Offset(right - size.width * 0.12f, midY) + offset,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(left + size.width * 0.22f, bottomY) + offset,
                end = Offset(right - size.width * 0.26f, bottomY) + offset,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(size.width * 0.66f, topY - size.height * 0.12f) + offset,
                end = Offset(size.width * 0.66f, topY + size.height * 0.12f) + offset,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(size.width * 0.42f, midY - size.height * 0.12f) + offset,
                end = Offset(size.width * 0.42f, midY + size.height * 0.12f) + offset,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(size.width * 0.56f, bottomY - size.height * 0.12f) + offset,
                end = Offset(size.width * 0.56f, bottomY + size.height * 0.12f) + offset,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }

        drawFilter(shadow, shadowOffset)
        drawFilter(tint)
    }
}

@Composable
private fun ChatListHeader(
    createMenuExpanded: Boolean,
    onCreateMenuExpandedChange: (Boolean) -> Unit,
    onCreateGroup: () -> Unit,
    onCreateChannel: () -> Unit,
    onJoinByLink: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Чаты",
            style = MaterialTheme.typography.headlineSmall,
            color = GlagolitsaColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset(y = (-1).dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        // Apple HIG: trailing compose / + opens a menu of create actions.
        Box {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onCreateMenuExpandedChange(true) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Создать"
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("chat-list-create"),
            ) {
                Image(
                    painter = painterResource(Res.drawable.chat_list_create_icon),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                )
            }
            DropdownMenu(
                expanded = createMenuExpanded,
                onDismissRequest = { onCreateMenuExpandedChange(false) },
                modifier = Modifier.background(GlagolitsaColors.SurfaceFloating),
            ) {
                DropdownMenuItem(
                    text = { Text("Новая группа", color = GlagolitsaColors.TextPrimary) },
                    onClick = onCreateGroup,
                )
                DropdownMenuItem(
                    text = { Text("Новый канал", color = GlagolitsaColors.TextPrimary) },
                    onClick = onCreateChannel,
                )
                DropdownMenuItem(
                    text = { Text("Вступить по ссылке", color = GlagolitsaColors.TextPrimary) },
                    onClick = onJoinByLink,
                )
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(
    title: String,
    trailing: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = GlagolitsaColors.TextTertiary,
            fontWeight = FontWeight.SemiBold,
        )
        trailing?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = GlagolitsaColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun UserSearchRow(
    user: User,
    presenceColor: Color?,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) GlagolitsaColors.SurfacePressed else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !loading,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = "Написать @${user.username}"
            }
            .padding(horizontal = 4.dp, vertical = 10.dp)
            .heightIn(min = ChatLayout.listCardMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(ChatLayout.listAvatarSize),
            contentAlignment = Alignment.Center,
        ) {
            ProfileAvatar(
                avatarUrl = user.avatar_url,
                fallbackLabel = user.avatarLabel(),
                size = ChatLayout.listAvatarSize,
                presenceColor = presenceColor,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "@${user.username}",
                style = MaterialTheme.typography.titleMedium,
                color = GlagolitsaColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = user.displayLabel().takeIf { it != user.username } ?: "Написать сообщение"
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = GlagolitsaColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = GlagolitsaColors.AccentRed,
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun ChatListRow(
    chat: Chat,
    avatarUrl: String?,
    unreadCount: Int,
    presenceColor: Color?,
    onClick: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val preview = chat.last_message?.takeIf { it.isNotBlank() } ?: "Пока нет сообщений"
    val timeLabel = formatMessageTime(chat.last_message_at ?: chat.created_at)
    val hasUnread = unreadCount > 0
    val density = LocalDensity.current
    val maxThresholdPx = with(density) { ChatListSwipeDeletePolicy.THRESHOLD_MAX_DP.dp.toPx() }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDeleteRequest()
            }
            false
        },
        positionalThreshold = { totalDistance ->
            ChatListSwipeDeletePolicy.positionalThresholdPx(totalDistance, maxThresholdPx)
        },
    )

    SwipeToDismissBox(
        modifier = Modifier.fillMaxWidth(),
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlagolitsaColors.AccentRedContainer.copy(alpha = 0.72f))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = "Удалить",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (pressed) GlagolitsaColors.SurfacePressed else GlagolitsaColors.Background950,
                )
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onDeleteRequest,
                )
                .semantics {
                    role = Role.Button
                    contentDescription = "Открыть чат ${chat.title}"
                }
                .padding(horizontal = 4.dp, vertical = 10.dp)
                .heightIn(min = ChatLayout.listCardMinHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatListAvatar(
                chat = chat,
                avatarUrl = avatarUrl,
                presenceColor = presenceColor,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = chat.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = GlagolitsaColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (timeLabel.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = timeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasUnread) GlagolitsaColors.FilterChipActive else GlagolitsaColors.TextTertiary,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = preview,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasUnread) {
                            GlagolitsaColors.TextSecondary
                        } else {
                            GlagolitsaColors.TextTertiary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hasUnread) {
                        Spacer(modifier = Modifier.width(8.dp))
                        UnreadBadge(count = unreadCount)
                    }
                }
            }
        }
    }
}

/**
 * Chat-list avatar.
 * Unread: right-side badge on the row.
 * Presence: ring around the face via [ProfileAvatar] (same as chat top bar / search).
 */
@Composable
private fun ChatListAvatar(
    chat: Chat,
    avatarUrl: String?,
    presenceColor: Color?,
) {
    val ringGradient = remember {
        Brush.radialGradient(
            colors = listOf(GlagolitsaColors.AvatarGradientTop, GlagolitsaColors.AvatarGradientBottom),
        )
    }
    val avatarSize = ChatLayout.listAvatarSize
    // Extra room so ProfileAvatar presence ring is not clipped by the decorative frame.
    val frameSize = avatarSize + 10.dp

    Box(
        modifier = Modifier
            .size(frameSize)
            .avatarFrameShadow(),
        contentAlignment = Alignment.Center,
    ) {
        if (chat.isDirectMessage || !avatarUrl.isNullOrBlank()) {
            ProfileAvatar(
                avatarUrl = avatarUrl,
                fallbackLabel = chat.listAvatarLabel(),
                size = avatarSize,
                presenceColor = if (chat.isDirectMessage) presenceColor else null,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(avatarSize + 4.dp)
                    .avatarGlassBezel(rim = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(CircleShape)
                        .background(brush = ringGradient),
                    contentAlignment = Alignment.Center,
                ) {
                    GroupSnowflakeIcon(
                        tint = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * Unread count pill: solid gold, round, convex (outer shadow + light top sheen).
 * No dark bottom wash — digit stays crisp on the full face.
 */
@Composable
private fun UnreadBadge(count: Int) {
    val label = if (count > 99) "99+" else count.toString()
    val badgeSize = 22.dp
    val shape = CircleShape
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = badgeSize, minHeight = badgeSize)
            .then(
                if (label.length == 1) {
                    Modifier.size(badgeSize)
                } else {
                    Modifier.height(badgeSize)
                },
            )
            .convexSurface(
                shape = shape,
                cornerRadius = badgeSize / 2,
                baseColor = GlagolitsaColors.FilterChipActive,
            )
            .padding(horizontal = if (label.length == 1) 0.dp else 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = GlagolitsaColors.TextOnGold,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun FilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(ChatLayout.listFilterRadius)
    val textColor = if (selected) {
        GlagolitsaColors.TextPrimary
    } else {
        GlagolitsaColors.TextSecondary.copy(alpha = 0.90f)
    }

    Box(
        modifier = modifier
            .glossyFilterChipSurface(
                selected = selected,
                pressed = pressed,
                shape = shape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                role = Role.Tab
                contentDescription = "Фильтр: $text"
            }
            .padding(horizontal = ChatLayout.listFilterPaddingH),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 14.sp,
                lineHeight = 16.sp,
            ),
            color = textColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}

private fun Modifier.glossyFilterChipSurface(
    selected: Boolean,
    pressed: Boolean,
    shape: Shape,
): Modifier {
    val light = !GlagolitsaColors.IsDark
    val surfaceGradient = if (light) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = if (selected) 0.56f else 0.48f),
                0.18f to Color(0xFFF7F0E5),
                0.58f to Color(0xFFECE1CF),
                1f to Color(0xFFDCCFB8),
            ),
        )
    } else {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = if (selected) 0.090f else 0.060f),
                0.16f to GlagolitsaColors.Surface600.copy(alpha = if (selected) 0.66f else 0.52f),
                0.58f to GlagolitsaColors.Surface700.copy(alpha = 0.80f),
                1f to GlagolitsaColors.Background950.copy(alpha = 0.96f),
            ),
        )
    }

    return this
        .offset(y = if (pressed) 1.dp else 0.dp)
        .height(ChatLayout.listFilterHeight)
        .drawBehindFilterChipCastShadow()
        .shadow(
            elevation = if (light) {
                if (selected) 5.dp else 4.dp
            } else {
                if (selected) 10.dp else 8.dp
            },
            shape = shape,
            ambientColor = if (light) Color(0xFF6F604B).copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.36f),
            spotColor = if (light) Color(0xFF6F604B).copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.48f),
        )
        .clip(shape)
        .background(surfaceGradient)
        .drawBehindFilterChipGloss(selected = selected, pressed = pressed)
}

private fun Modifier.drawBehindFilterChipCastShadow(): Modifier = drawBehind {
    val radius = ChatLayout.listFilterRadius.toPx()
    val corner = CornerRadius(radius, radius)
    val light = !GlagolitsaColors.IsDark
    val shadow = if (light) Color(0xFF6F604B) else Color.Black

    drawRoundRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.62f to shadow.copy(alpha = if (light) 0.035f else 0.12f),
                1f to shadow.copy(alpha = if (light) 0.090f else 0.24f),
            ),
        ),
        topLeft = Offset(0f, if (light) 3.dp.toPx() else 5.dp.toPx()),
        cornerRadius = corner,
    )
    drawRoundRect(
        color = shadow.copy(alpha = if (light) 0.045f else 0.12f),
        topLeft = Offset(0f, if (light) 6.dp.toPx() else 9.dp.toPx()),
        cornerRadius = corner,
    )
}

private fun Modifier.drawBehindFilterChipGloss(
    selected: Boolean,
    pressed: Boolean,
): Modifier = drawBehind {
    val radius = ChatLayout.listFilterRadius.toPx()
    val corner = CornerRadius(radius, radius)
    val light = !GlagolitsaColors.IsDark
    val shadow = if (light) Color(0xFF6F604B) else Color.Black

    drawRoundRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(
                    alpha = if (light) {
                        if (selected) 0.46f else 0.38f
                    } else {
                        if (selected) 0.28f else 0.20f
                    },
                ),
                0.16f to Color.White.copy(
                    alpha = if (light) {
                        if (selected) 0.24f else 0.18f
                    } else {
                        if (selected) 0.13f else 0.08f
                    },
                ),
                0.52f to Color.Transparent,
                1f to Color.Transparent,
            ),
        ),
        cornerRadius = corner,
        style = Stroke(width = 1.dp.toPx()),
    )
    drawRoundRect(
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(
                    alpha = if (light) {
                        if (selected) 0.24f else 0.18f
                    } else {
                        if (selected) 0.16f else 0.10f
                    },
                ),
                0.32f to Color.Transparent,
                1f to shadow.copy(
                    alpha = if (light) {
                        if (pressed) 0.12f else 0.075f
                    } else {
                        if (pressed) 0.54f else 0.40f
                    },
                ),
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        ),
        cornerRadius = corner,
        style = Stroke(width = 1.4.dp.toPx()),
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.42f to Color.Transparent,
                0.74f to shadow.copy(
                    alpha = if (light) {
                        if (pressed) 0.045f else 0.025f
                    } else {
                        if (pressed) 0.12f else 0.07f
                    },
                ),
                1f to shadow.copy(
                    alpha = if (light) {
                        if (pressed) 0.08f else 0.055f
                    } else {
                        if (pressed) 0.24f else 0.16f
                    },
                ),
            ),
        ),
        cornerRadius = corner,
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.50f to Color.Transparent,
                0.82f to shadow.copy(
                    alpha = if (light) {
                        if (selected) 0.12f else 0.09f
                    } else {
                        if (selected) 0.42f else 0.36f
                    },
                ),
                1f to shadow.copy(
                    alpha = if (light) {
                        if (selected) 0.22f else 0.16f
                    } else {
                        if (selected) 0.78f else 0.68f
                    },
                ),
            ),
        ),
        cornerRadius = corner,
        style = Stroke(width = 1.5.dp.toPx()),
    )
}

@Composable
private fun GroupSnowflakeIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(size.width, size.height) * 0.34f
        repeat(6) { branch ->
            val angle = (branch * 60).toDouble() * PI / 180.0
            val endX = cx + (cos(angle) * radius).toFloat()
            val endY = cy + (sin(angle) * radius).toFloat()
            drawLine(
                color = tint,
                start = Offset(cx, cy),
                end = Offset(endX, endY),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
            val midX = cx + (cos(angle) * radius * 0.55f).toFloat()
            val midY = cy + (sin(angle) * radius * 0.55f).toFloat()
            val perpAngle = angle + PI / 2
            val arm = radius * 0.22f
            drawLine(
                color = tint,
                start = Offset(
                    midX - (cos(perpAngle) * arm).toFloat(),
                    midY - (sin(perpAngle) * arm).toFloat(),
                ),
                end = Offset(
                    midX + (cos(perpAngle) * arm).toFloat(),
                    midY + (sin(perpAngle) * arm).toFloat(),
                ),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        }
    }
}

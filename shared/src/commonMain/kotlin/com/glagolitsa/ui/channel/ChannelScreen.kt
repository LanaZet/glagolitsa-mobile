// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.channel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glagolitsa.media.OPEN_MEDIA_GALLERY_EDGE
import com.glagolitsa.media.OpenMediaImageLoader
import com.glagolitsa.model.ChannelMediaRef
import com.glagolitsa.model.Chat
import com.glagolitsa.model.listAvatarLabel
import com.glagolitsa.model.Message
import com.glagolitsa.model.channelCoverRef
import com.glagolitsa.model.channelMediaRefs
import com.glagolitsa.model.channelPostText
import com.glagolitsa.model.isMeaningfulChannelPost
import com.glagolitsa.repository.MessengerRepository
import com.glagolitsa.session.SessionStore
import com.glagolitsa.ui.chat.ChatSettingsInviteUi
import com.glagolitsa.ui.components.AppTopBarTextAction
import com.glagolitsa.ui.components.screenTopSafeArea
import com.glagolitsa.ui.profile.ProfileAvatar
import com.glagolitsa.ui.theme.GlagolitsaColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class ChannelViewMode { Feed, Gallery }

private data class LightboxState(
    val media: List<ChannelMediaRef>,
    val startIndex: Int,
)

/**
 * Channel UI with messenger-style gallery optimization:
 * - LazyVerticalGrid only composes visible cells
 * - thumbs preferred (server thumb_file_id) + client subsample decode
 * - LRU memory + disk cache + max 3 concurrent downloads
 * - neighbor prefetch + progressive lightbox
 */
@Composable
fun ChannelScreen(
    chat: Chat,
    repository: MessengerRepository,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenThread: (Message) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val imageLoader = rememberOpenMediaLoader(repository)
    val gridState = rememberLazyGridState()
    var mode by remember { mutableStateOf(ChannelViewMode.Feed) }
    var posts by remember { mutableStateOf<List<Message>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var lightbox by remember { mutableStateOf<LightboxState?>(null) }
    val me = SessionStore.user.value?.id
    // Optimistic: creator (or unknown creator while we own the chat shell) can compose immediately.
    var canPublish by remember(chat.id, me) {
        mutableStateOf(
            ChatSettingsInviteUi.isCreator(chat, me) ||
                (chat.isChannel && chat.creator_id.isNullOrBlank() && me != null),
        )
    }
    var publishCheckDone by remember { mutableStateOf(false) }
    var offlineHint by remember { mutableStateOf(false) }

    LaunchedEffect(chat.id, me) {
        publishCheckDone = false
        val allowed = runCatching { repository.canPublishChannelPosts(chat.id) }.getOrElse {
            // Keep optimistic composer for likely owners if role check fails.
            ChatSettingsInviteUi.isCreator(chat, me) || chat.creator_id.isNullOrBlank()
        }
        canPublish = allowed
        publishCheckDone = true
    }

    fun humanizeChannelError(raw: String?, fallback: String): String {
        val msg = raw.orEmpty()
        return when {
            msg.contains("timeout", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true) ->
                "Сервер не ответил вовремя. Нажмите «Повторить» — пост мог уже сохраниться."
            msg.contains("Unable to resolve host", ignoreCase = true) ||
                msg.contains("Failed to connect", ignoreCase = true) ||
                msg.contains("Connection refused", ignoreCase = true) ->
                "Нет связи с сервером. Проверьте, что сервер запущен."
            msg.isBlank() -> fallback
            else -> msg
        }
    }

    fun applyPostsPage(pageMessages: List<Message>) {
        posts = pageMessages
            .filter { it.isMeaningfulChannelPost() }
            .sortedByDescending { it.created_at.orEmpty() }
    }

    /**
     * @param quiet when true, keep current posts visible on failure (refresh after publish).
     */
    fun reload(quiet: Boolean = false) {
        scope.launch {
            if (!quiet || posts.isEmpty()) {
                loading = true
            }
            if (!quiet) {
                error = null
            }
            runCatching {
                when (mode) {
                    ChannelViewMode.Feed -> repository.listChannelPosts(chat.id)
                    ChannelViewMode.Gallery -> repository.listChannelGallery(chat.id)
                }
            }.onSuccess { page ->
                // Drop empty shells (no text, no media) — they only showed reaction chrome.
                applyPostsPage(page.messages)
                error = null
                offlineHint = false
            }.onFailure {
                // Do not wipe an already-visible feed on a flaky refresh.
                if (posts.isEmpty()) {
                    error = humanizeChannelError(it.message, "Не удалось загрузить")
                    offlineHint = true
                } else if (!quiet) {
                    error = humanizeChannelError(it.message, "Не удалось обновить ленту")
                }
            }
            loading = false
        }
    }

    LaunchedEffect(chat.id, mode) {
        reload(quiet = false)
    }

    // Prefetch cover thumbs for visible ±1 cells (Telegram-style warm cache).
    LaunchedEffect(mode, posts, imageLoader) {
        if (mode != ChannelViewMode.Gallery || posts.isEmpty()) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.map { it.index } }
            .map { visible ->
                visible
                    .flatMap { i -> listOf(i - 1, i, i + 1) }
                    .distinct()
                    .filter { it in posts.indices }
            }
            .distinctUntilChanged()
            .collect { indices ->
                val ids = indices.mapNotNull { idx ->
                    posts[idx].channelCoverRef()?.displayFileId(preferThumb = true)
                }
                imageLoader.prefetchAll(ids, OPEN_MEDIA_GALLERY_EDGE)
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GlagolitsaColors.Background950)
            .screenTopSafeArea(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTopBarTextAction(text = "Назад", onClick = onBack)
            Spacer(modifier = Modifier.width(12.dp))
            ProfileAvatar(
                avatarUrl = chat.conversationIconUrl,
                fallbackLabel = chat.listAvatarLabel(),
                size = 36.dp,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp),
            ) {
                Text(
                    text = chat.title.ifBlank { "Канал" },
                    style = MaterialTheme.typography.titleMedium,
                    color = GlagolitsaColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                chat.slug?.takeIf { it.isNotBlank() }?.let { slug ->
                    Text(
                        text = "@$slug",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlagolitsaColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AppTopBarTextAction(text = "Меню", onClick = onOpenSettings)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SegmentChip(
                label = "Лента",
                selected = mode == ChannelViewMode.Feed,
                onClick = { mode = ChannelViewMode.Feed },
            )
            SegmentChip(
                label = "Галерея",
                selected = mode == ChannelViewMode.Gallery,
                onClick = { mode = ChannelViewMode.Gallery },
            )
        }

        // Main content: always weight(1f) so bottom composer stays visible (empty feed too).
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && posts.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            if (offlineHint) "Нет сети или сервер недоступен" else "Ошибка загрузки",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = {
                            offlineHint = false
                            reload()
                        }) { Text("Повторить") }
                    }
                }
                posts.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            if (mode == ChannelViewMode.Gallery) "Пока нет работ" else "Пока нет постов",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when {
                                mode == ChannelViewMode.Gallery -> "Когда авторы добавят фото, они появятся здесь"
                                canPublish -> "Внизу — форма «Новый пост»: текст и «Опубликовать»"
                                else -> "Авторы канала ещё ничего не выложили"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                mode == ChannelViewMode.Feed -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(posts, key = { it.id }, contentType = { "channel-post" }) { post ->
                        ChannelPostCard(
                            post = post,
                            imageLoader = imageLoader,
                            onOpenMedia = { refs, start ->
                                lightbox = LightboxState(
                                    media = refs,
                                    startIndex = start,
                                )
                            },
                        )
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(posts, key = { it.id }, contentType = { "gallery_cell" }) { post ->
                        OptimizedGalleryCell(
                            post = post,
                            imageLoader = imageLoader,
                            onClick = {
                                val refs = post.channelMediaRefs()
                                if (refs.isNotEmpty()) {
                                    lightbox = LightboxState(
                                        media = refs,
                                        startIndex = 0,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        if (!canPublish && publishCheckDone && mode == ChannelViewMode.Feed) {
            Text(
                "Пишут только авторы канала",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        // Channel-only compose panel (not chat input capsule). Text-only for now.
        if (canPublish && mode == ChannelViewMode.Feed) {
            val canSendPost = draft.isNotBlank()
            fun publishPost() {
                if (sending || !canSendPost) return
                scope.launch {
                    sending = true
                    error = null
                    val bodySnapshot = draft.trim()
                    runCatching {
                        repository.sendChannelPost(
                            chatId = chat.id,
                            body = bodySnapshot,
                        )
                    }.onSuccess { sent ->
                        draft = ""
                        // Show immediately even if subsequent feed refresh is slow/flaky.
                        if (sent.isMeaningfulChannelPost()) {
                            posts = (listOf(sent) + posts.filterNot { it.id == sent.id || it.pending_id == sent.pending_id })
                                .sortedByDescending { it.created_at.orEmpty() }
                        }
                        reload(quiet = true)
                    }.onFailure { fail ->
                        // Timeout after server commit: recover by reloading feed.
                        val maybeTimeout = fail.message.orEmpty().contains("timeout", ignoreCase = true)
                        if (maybeTimeout) {
                            val recovered = runCatching {
                                repository.listChannelPosts(chat.id)
                            }.getOrNull()
                            if (recovered != null) {
                                applyPostsPage(recovered.messages)
                                val appears = recovered.messages.any { post ->
                                    post.body.trim() == bodySnapshot && bodySnapshot.isNotEmpty()
                                }
                                if (appears || recovered.messages.isNotEmpty()) {
                                    draft = ""
                                    error = null
                                    sending = false
                                    return@launch
                                }
                            }
                        }
                        error = humanizeChannelError(fail.message, "Не удалось опубликовать")
                    }
                    sending = false
                }
            }
            ChannelPostComposer(
                draft = draft,
                onDraftChange = { draft = it },
                onPublish = { publishPost() },
                sending = sending,
                canPublish = canSendPost,
                error = error?.takeIf { posts.isNotEmpty() },
            )
        }
    }

        // Progressive full-size lightbox overlay
        lightbox?.let { state ->
            ChannelMediaLightbox(
                media = state.media,
                startIndex = state.startIndex,
                loader = imageLoader,
                onDismiss = { lightbox = null },
            )
        }
    }
}

@Composable
private fun SegmentChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        color = fg,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ChannelPostCard(
    post: Message,
    imageLoader: OpenMediaImageLoader,
    onOpenMedia: (List<ChannelMediaRef>, Int) -> Unit,
) {
    val refs = remember(post.id, post.metadata) { post.channelMediaRefs() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(14.dp),
    ) {
        if (refs.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                refs.take(10).forEachIndexed { index, ref ->
                    ChannelOpenImage(
                        loader = imageLoader,
                        fileId = ref.fileId,
                        thumbFileId = ref.thumbFileId,
                        preferThumb = false,
                        maxEdgePx = ChannelImageEdges.Feed,
                        modifier = Modifier
                            .width(240.dp)
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenMedia(refs, index) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Reactions + comments are deferred; post is media + text only for now.
        val text = post.channelPostText()
        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun OptimizedGalleryCell(
    post: Message,
    imageLoader: OpenMediaImageLoader,
    onClick: () -> Unit,
) {
    val cover = remember(post.id, post.metadata) { post.channelCoverRef() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        if (cover != null) {
            ChannelOpenImage(
                loader = imageLoader,
                fileId = cover.fileId,
                thumbFileId = cover.thumbFileId,
                preferThumb = true,
                maxEdgePx = ChannelImageEdges.Gallery,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = post.body.ifBlank { "Пост" },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

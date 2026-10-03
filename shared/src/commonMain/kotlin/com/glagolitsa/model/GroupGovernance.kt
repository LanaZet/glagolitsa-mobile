// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.model

import kotlinx.serialization.Serializable

@Serializable
data class GroupMemberDto(
    val user_id: String,
    val role: String = "member",
    val joined_at: String? = null,
    val muted_until: String? = null,
    val admin_rights: AdminRightsDto? = null,
)

@Serializable
data class AdminRightsDto(
    val change_info: Boolean = false,
    val delete_messages: Boolean = false,
    val ban_users: Boolean = false,
    val invite_users: Boolean = false,
    val pin_messages: Boolean = false,
    val add_admins: Boolean = false,
    val post_messages: Boolean = false,
    val edit_messages: Boolean = false,
) {
    companion object {
        val DEFAULT_ADMIN = AdminRightsDto(
            change_info = true,
            delete_messages = true,
            ban_users = true,
            invite_users = true,
            pin_messages = true,
            add_admins = false,
            post_messages = true,
            edit_messages = false,
        )
        val OWNER = DEFAULT_ADMIN.copy(add_admins = true, edit_messages = true)
    }
}

@Serializable
data class GroupSettingsDto(
    val join_by_invite_only: Boolean = true,
    val join_requests_enabled: Boolean = false,
    val perm_invite: String = "admin",
    val perm_send_messages: String = "admin",
    val perm_pin: String = "admin",
    val perm_moderate: String = "admin",
    val perm_change_info: String = "admin",
    val perm_comment: String = "all",
    val perm_react: String = "all",
    val visibility: String = "private",
    val slug: String? = null,
    val description: String? = null,
    val encryption_mode: String = "e2e",
)

@Serializable
data class GroupResponse(
    val group_id: String,
    val title: String = "",
    val members: List<GroupMemberDto> = emptyList(),
    val settings: GroupSettingsDto = GroupSettingsDto(),
    val membership_version: Int = 0,
)

object GroupRoles {
    const val OWNER = "owner"
    const val ADMIN = "admin"
    const val MEMBER = "member"
}

@Serializable
data class GroupInviteDto(
    val invite_id: String,
    val token: String,
    val group_id: String,
    val title: String? = null,
    val link: String = "",
    val expires_at: String? = null,
    val max_uses: Int? = null,
    val use_count: Int = 0,
    val requires_approval: Boolean = false,
    val created_at: String? = null,
)

@Serializable
data class CreateGroupInviteRequest(
    val title: String? = null,
    val expires_in_hours: Int = 168,
    val max_uses: Int? = null,
    val requires_approval: Boolean = false,
)

@Serializable
data class GroupInvitePreviewDto(
    val token: String,
    val group_id: String,
    val title: String = "",
    val chat_type: String = "",
    val visibility: String? = null,
    val slug: String? = null,
    val member_count: Int = 0,
    val requires_approval: Boolean = false,
    val already_member: Boolean = false,
    val expired: Boolean = false,
)

@Serializable
data class MembershipChangeDto(
    val group_id: String,
    val user_id: String? = null,
    val membership_version: Int = 0,
    val key_rotation_required: Boolean = false,
    val pending_approval: Boolean = false,
)

@Serializable
data class UpdateMemberRoleRequest(
    val role: String,
)

@Serializable
data class MuteMemberRequest(
    val duration_minutes: Int,
)

@Serializable
data class BanMemberRequest(
    val reason: String? = null,
)

@Serializable
data class UpdateGroupSettingsRequest(
    val join_by_invite_only: Boolean? = null,
    val join_requests_enabled: Boolean? = null,
    val perm_invite: String? = null,
    val perm_send_messages: String? = null,
    val perm_pin: String? = null,
    val perm_moderate: String? = null,
    val perm_change_info: String? = null,
    val perm_comment: String? = null,
    val perm_react: String? = null,
)

/** Whether current user may publish root posts to a channel (client gate; server still enforces). */
fun GroupResponse.canPublishPosts(userId: String?): Boolean =
    ChatPermissionPolicy.canSend(this, userId)

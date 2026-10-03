// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glagolitsa.model.CallSession
import com.glagolitsa.model.callJoinLink
import com.glagolitsa.ui.theme.GlagolitsaColors

@Composable
fun GroupCallBanner(
    call: CallSession,
    inThisCall: Boolean,
    onCopyLink: (String) -> Unit,
    onJoin: (CallSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = if (inThisCall) {
            "Вы в звонке · ${callJoinLink(call.id)}"
        } else {
            "Идет групповой звонок · нажмите, чтобы войти"
        },
        color = GlagolitsaColors.OrnamentGold,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable {
                if (inThisCall) {
                    onCopyLink(callJoinLink(call.id))
                } else {
                    onJoin(call)
                }
            },
    )
}

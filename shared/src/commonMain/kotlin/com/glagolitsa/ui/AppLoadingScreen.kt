// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import glagolitsamobile.shared.generated.resources.Res
import glagolitsamobile.shared.generated.resources.loading_splash
import org.jetbrains.compose.resources.painterResource

private val LoaderBackground = Color(0xFF02080F)

/**
 * Cold-start splash with the full brand artwork.
 *
 * Session restore is disk-first (see [MessengerApp]), so this screen should only
 * flash briefly — keep the visual, don't block on network.
 */
@Composable
fun AppLoadingScreen(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LoaderBackground),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(Res.drawable.loading_splash),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

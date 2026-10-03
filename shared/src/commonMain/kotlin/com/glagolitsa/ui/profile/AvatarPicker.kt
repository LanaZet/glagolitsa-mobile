// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.profile

import androidx.compose.runtime.Composable

@Composable
expect fun rememberAvatarPicker(onResult: (String?) -> Unit): () -> Unit
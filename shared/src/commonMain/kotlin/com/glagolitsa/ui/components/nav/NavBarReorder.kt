// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.components.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.glagolitsa.ui.navigation.MainTab

class NavBarReorderState internal constructor(
    private val onOrderChange: (List<MainTab>) -> Unit,
) {
    var tabOrder by mutableStateOf(MainTab.defaultOrder)
        internal set

    var draggingTab by mutableStateOf<MainTab?>(null)
        private set

    var dragOffsetX by mutableFloatStateOf(0f)
        private set

    var itemWidthPx by mutableFloatStateOf(0f)
        internal set

    fun setOrder(order: List<MainTab>) {
        tabOrder = order
    }

    fun onDragStart(tab: MainTab) {
        draggingTab = tab
        dragOffsetX = 0f
    }

    fun onDrag(tab: MainTab, deltaX: Float) {
        if (draggingTab != tab || itemWidthPx <= 0f) return

        dragOffsetX += deltaX
        val currentIndex = tabOrder.indexOf(tab)
        if (currentIndex == -1) return

        val swapThreshold = itemWidthPx * 0.45f

        if (dragOffsetX > swapThreshold && currentIndex < tabOrder.lastIndex) {
            swap(currentIndex, currentIndex + 1)
            dragOffsetX -= itemWidthPx
        } else if (dragOffsetX < -swapThreshold && currentIndex > 0) {
            swap(currentIndex, currentIndex - 1)
            dragOffsetX += itemWidthPx
        }
    }

    fun onDragEnd() {
        if (draggingTab != null) {
            onOrderChange(tabOrder)
        }
        draggingTab = null
        dragOffsetX = 0f
    }

    private fun swap(from: Int, to: Int) {
        val mutable = tabOrder.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        tabOrder = mutable
    }
}

@Composable
fun rememberNavBarReorderState(
    order: List<MainTab>,
    onOrderChange: (List<MainTab>) -> Unit,
): NavBarReorderState {
    val state = remember { NavBarReorderState(onOrderChange) }

    LaunchedEffect(order) {
        if (state.draggingTab == null && state.tabOrder != order) {
            state.setOrder(order)
        }
    }

    return state
}
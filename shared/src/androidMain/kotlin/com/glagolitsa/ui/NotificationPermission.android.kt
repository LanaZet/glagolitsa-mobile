// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
actual fun rememberNotificationPermissionController(): NotificationPermissionController {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val controller = remember(appContext) {
        AndroidNotificationPermissionController(appContext)
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        controller.onPermissionResult(granted)
    }
    controller.requestPermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            controller.onPermissionResult(controller.readEnabled())
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        controller.refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return controller
}

private class AndroidNotificationPermissionController(
    private val context: Context,
) : NotificationPermissionController {
    var requestPermission: (() -> Unit)? = null
    private var pendingResult: ((Boolean) -> Unit)? = null
    private var enabledState by mutableStateOf(readEnabled())

    override val notificationsEnabled: Boolean get() = enabledState

    override fun refresh() {
        enabledState = readEnabled()
    }

    override fun request(onResult: (Boolean) -> Unit) {
        if (readEnabled()) {
            enabledState = true
            onResult(true)
            return
        }
        pendingResult = onResult
        requestPermission?.invoke() ?: run {
            openSystemSettings()
            pendingResult = null
            onResult(false)
        }
    }

    override fun openSystemSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun onPermissionResult(granted: Boolean) {
        refresh()
        pendingResult?.invoke(granted && enabledState)
        pendingResult = null
    }

    fun readEnabled(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}

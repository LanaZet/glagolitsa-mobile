// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSItemProvider
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.darwin.NSObject
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
internal class IosSingleItemPhotoPicker(
    private val filter: PHPickerFilter,
    private val onPicked: (NSItemProvider?) -> Unit,
) {
    private var activePicker: PHPickerViewController? = null
    private var delegate: Delegate? = null
    private var closed = false

    fun present() {
        if (closed || activePicker != null) return
        val configuration = PHPickerConfiguration().apply {
            selectionLimit = 1
            filter = this@IosSingleItemPhotoPicker.filter
        }
        val picker = PHPickerViewController(configuration)
        val nextDelegate = Delegate { provider -> finish(provider) }
        activePicker = picker
        delegate = nextDelegate
        picker.delegate = nextDelegate
        iosPresent(picker)
    }

    fun close() {
        closed = true
        activePicker?.let(::iosDismiss)
        activePicker = null
        delegate = null
    }

    private fun finish(provider: NSItemProvider?) {
        val picker = activePicker ?: return
        activePicker = null
        iosDismiss(picker)
        delegate = null
        if (!closed) onPicked(provider)
    }

    private class Delegate(
        private val onPicked: (NSItemProvider?) -> Unit,
    ) : NSObject(), PHPickerViewControllerDelegateProtocol {
        override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
            val result = didFinishPicking.firstOrNull() as? PHPickerResult
            onPicked(result?.itemProvider)
        }
    }
}

internal sealed interface IosProviderDataResult {
    data class Loaded(val bytes: ByteArray, val typeIdentifier: String) : IosProviderDataResult
    data object TooLarge : IosProviderDataResult
    data object Unavailable : IosProviderDataResult
}

internal suspend fun iosLoadProviderData(
    provider: NSItemProvider,
    preferredTypeIdentifiers: List<String>,
    maxBytes: Long,
): IosProviderDataResult {
    val typeIdentifier = preferredTypeIdentifiers.firstOrNull(provider::hasItemConformingToTypeIdentifier)
        ?: return IosProviderDataResult.Unavailable
    val data = suspendCancellableCoroutine<NSData?> { continuation ->
        provider.loadDataRepresentationForTypeIdentifier(typeIdentifier) { loaded, _ ->
            if (continuation.isActive) continuation.resume(loaded)
        }
    } ?: return IosProviderDataResult.Unavailable
    return withContext(Dispatchers.Default) {
        if (data.length > maxBytes.toULong()) {
            IosProviderDataResult.TooLarge
        } else {
            IosProviderDataResult.Loaded(data.toByteArray(), typeIdentifier)
        }
    }
}

internal suspend fun iosLoadUrlData(url: NSURL, maxBytes: Long): IosProviderDataResult =
    withContext(Dispatchers.Default) {
        val data = NSData.dataWithContentsOfURL(url) ?: return@withContext IosProviderDataResult.Unavailable
        if (data.length > maxBytes.toULong()) {
            IosProviderDataResult.TooLarge
        } else {
            IosProviderDataResult.Loaded(data.toByteArray(), "")
        }
    }

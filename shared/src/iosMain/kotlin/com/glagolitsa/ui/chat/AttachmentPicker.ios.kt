// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.glagolitsa.platform.IosProviderDataResult
import com.glagolitsa.platform.IosSingleItemPhotoPicker
import com.glagolitsa.platform.iosDismiss
import com.glagolitsa.platform.iosLoadProviderData
import com.glagolitsa.platform.iosLoadUrlData
import com.glagolitsa.platform.iosPresent
import com.glagolitsa.platform.iosRunOnMain
import com.glagolitsa.platform.toByteArray
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSURL
import platform.PhotosUI.PHPickerFilter
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.UniformTypeIdentifiers.UTTypeMovie
import platform.UniformTypeIdentifiers.UTTypePDF
import platform.darwin.NSObject

@Composable
actual fun rememberAttachmentPicker(onResult: (PickedAttachment?) -> Unit): AttachmentPickerActions {
    val scope = rememberCoroutineScope()
    val latestOnResult by rememberUpdatedState(onResult)
    val coordinator = remember(scope) {
        IosAttachmentPickerCoordinator(scope) { latestOnResult(it) }
    }
    DisposableEffect(coordinator) {
        onDispose { coordinator.close() }
    }
    return remember(coordinator) {
        AttachmentPickerActions(
            pickPhotoOrVideo = { coordinator.pickMedia() },
            pickFile = { coordinator.pickFile() },
            openCamera = { coordinator.openCamera() },
        )
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosAttachmentPickerCoordinator(
    private val scope: CoroutineScope,
    private val onResult: (PickedAttachment?) -> Unit,
) {
    private var fileDelegate: FileDelegate? = null
    private var cameraDelegate: CameraDelegate? = null
    private val mediaPicker = IosSingleItemPhotoPicker(
        filter = PHPickerFilter.anyFilterMatchingSubfilters(
            listOf(PHPickerFilter.imagesFilter, PHPickerFilter.videosFilter),
        ),
        onPicked = { provider -> handleProvider(provider, provider?.suggestedName) },
    )

    fun pickMedia() = mediaPicker.present()

    fun pickFile() {
        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(
                UTTypeItem,
                UTTypeData,
                UTTypeImage,
                UTTypeMovie,
                UTTypePDF,
                UTTypeAudio,
            ),
            asCopy = true,
        )
        picker.allowsMultipleSelection = false
        val delegate = FileDelegate { url ->
            iosDismiss(picker)
            if (url == null) {
                onResult(null)
                return@FileDelegate
            }
            scope.launch {
                val attachment = withContext(Dispatchers.Default) {
                    val name = url.lastPathComponent
                    when (val loaded = iosLoadUrlData(url, IOS_MAX_ATTACHMENT_BYTES)) {
                        IosProviderDataResult.TooLarge -> attachmentError(
                            name,
                            iosGuessMime(name, null),
                            "Файл слишком большой (максимум 100 МБ)",
                        )
                        IosProviderDataResult.Unavailable -> attachmentError(
                            name,
                            iosGuessMime(name, null),
                            "Не удалось открыть файл",
                        )
                        is IosProviderDataResult.Loaded ->
                            iosBuildPickedAttachment(loaded.bytes, name, iosGuessMime(name, null))
                    }
                }
                onResult(attachment)
            }
        }
        fileDelegate = delegate
        picker.delegate = delegate
        iosPresent(picker)
    }

    fun openCamera() {
        if (!UIImagePickerController.isSourceTypeAvailable(
                UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera,
            )
        ) {
            onResult(
                PickedAttachment(
                    fileName = null,
                    mimeType = null,
                    errorMessage = "Камера недоступна",
                ),
            )
            return
        }
        val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
        when (status) {
            AVAuthorizationStatusAuthorized -> presentCamera()
            AVAuthorizationStatusDenied, AVAuthorizationStatusRestricted -> onResult(
                PickedAttachment(
                    fileName = null,
                    mimeType = null,
                    errorMessage = "Нет доступа к камере",
                ),
            )
            else -> AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                iosRunOnMain {
                    if (granted) {
                        presentCamera()
                    } else {
                        onResult(attachmentError(null, null, "Нет доступа к камере"))
                    }
                }
            }
        }
    }

    private fun presentCamera() {
        val picker = UIImagePickerController()
        picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
        picker.allowsEditing = false
        val delegate = CameraDelegate { image ->
            iosDismiss(picker)
            if (image == null) {
                onResult(null)
                return@CameraDelegate
            }
            scope.launch {
                val attachment = withContext(Dispatchers.Default) {
                    val data = UIImageJPEGRepresentation(image, 0.9) ?: return@withContext PickedAttachment(
                        fileName = null,
                        mimeType = "image/jpeg",
                        errorMessage = "Не удалось сохранить фото",
                    )
                    iosBuildPickedAttachment(
                        bytes = data.toByteArray(),
                        fileName = "camera.jpg",
                        mimeType = "image/jpeg",
                    )
                }
                onResult(attachment)
            }
        }
        cameraDelegate = delegate
        picker.delegate = delegate
        iosPresent(picker)
    }

    private fun handleProvider(provider: platform.Foundation.NSItemProvider?, suggestedName: String?) {
        if (provider == null) {
            onResult(null)
            return
        }
        scope.launch {
            when (val loaded = iosLoadProviderData(
                provider,
                listOf(UTTypeMovie.identifier, UTTypeImage.identifier, UTTypeItem.identifier),
                IOS_MAX_ATTACHMENT_BYTES,
            )) {
                IosProviderDataResult.TooLarge -> onResult(
                    attachmentError(suggestedName, null, "Файл слишком большой (максимум 100 МБ)"),
                )
                IosProviderDataResult.Unavailable -> onResult(null)
                is IosProviderDataResult.Loaded -> {
                    val name = suggestedName ?: defaultNameForType(loaded.typeIdentifier)
                    val mime = mimeForType(loaded.typeIdentifier, name)
                    val attachment = withContext(Dispatchers.Default) {
                        iosBuildPickedAttachment(loaded.bytes, name, mime)
                    }
                    onResult(attachment)
                }
            }
        }
    }

    fun close() {
        mediaPicker.close()
        fileDelegate = null
        cameraDelegate = null
    }
}

private class FileDelegate(
    private val onPicked: (NSURL?) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onPicked(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onPicked(null)
    }
}

private class CameraDelegate(
    private val onPicked: (UIImage?) -> Unit,
) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>,
    ) {
        onPicked(didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage)
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        onPicked(null)
    }
}

private fun attachmentError(fileName: String?, mimeType: String?, message: String) = PickedAttachment(
    fileName = fileName,
    mimeType = mimeType,
    errorMessage = message,
)

private fun mimeForType(typeId: String, fileName: String?): String? = when {
    typeId == UTTypeImage.identifier -> iosGuessMime(fileName, "image/jpeg")
    typeId == UTTypeMovie.identifier -> iosGuessMime(fileName, "video/mp4")
    else -> iosGuessMime(fileName, null)
}

private fun defaultNameForType(typeId: String): String = when (typeId) {
    UTTypeMovie.identifier -> "video.mp4"
    UTTypeImage.identifier -> "image.jpg"
    else -> "file.bin"
}

// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa.platform

import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

internal fun iosRunOnMain(block: () -> Unit) {
    dispatch_async(dispatch_get_main_queue(), block)
}

internal fun iosTopViewController(): UIViewController? {
    val app = UIApplication.sharedApplication
    val window = app.windows.mapNotNull { it as? UIWindow }.firstOrNull { it.isKeyWindow() }
        ?: app.keyWindow
    var controller = window?.rootViewController
    while (true) {
        val presented = controller?.presentedViewController ?: break
        controller = presented
    }
    return controller
}

internal fun iosPresent(controller: UIViewController) {
    iosRunOnMain {
        iosTopViewController()?.presentViewController(controller, animated = true, completion = null)
    }
}

internal fun iosDismiss(controller: UIViewController) {
    iosRunOnMain {
        controller.dismissViewControllerAnimated(true, completion = null)
    }
}

// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package com.glagolitsa

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.fragment.app.FragmentActivity
import com.glagolitsa.backup.EncryptedBackupExporter
import com.glagolitsa.backup.EncryptedBackupImporter
import com.glagolitsa.crypto.CryptoEngineFactory
import com.glagolitsa.db.DatabaseDriverFactory
import com.glagolitsa.jobs.initBackgroundSyncScheduler
import com.glagolitsa.model.ShareIntakePolicy
import com.glagolitsa.platform.AppLifecycle
import com.glagolitsa.platform.NetworkPathMonitor
import com.glagolitsa.security.LocalAuthenticator
import com.glagolitsa.security.SecureClipboard
import com.glagolitsa.session.SecureSessionStore
import com.glagolitsa.share.IncomingShare
import com.glagolitsa.share.IncomingShareBus
import com.glagolitsa.ui.MessengerApp

@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initBackgroundSyncScheduler(applicationContext)
        SecureClipboard.init(applicationContext)
        EncryptedBackupExporter.init(applicationContext)
        EncryptedBackupImporter.init(applicationContext)
        AppLifecycle.init(this)
        NetworkPathMonitor.init(applicationContext)
        com.glagolitsa.push.LocalMessageNotifier.ensureChannels()
        enableEdgeToEdge()
        consumeShareIntent(intent)
        setContent {
            // Maestro / UI tests: Compose testTag → Android resource id.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { testTagsAsResourceId = true },
            ) {
                MessengerApp(
                    driverFactory = DatabaseDriverFactory(applicationContext),
                    cryptoEngineFactory = CryptoEngineFactory(applicationContext),
                    secureSessionStore = SecureSessionStore(applicationContext),
                    localAuthenticator = LocalAuthenticator(this@MainActivity),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppLifecycle.setForeground(true)
    }

    override fun onPause() {
        AppLifecycle.setForeground(false)
        super.onPause()
    }

    /** ACTION_SEND from YouTube / Telegram / browser → [IncomingShareBus]. */
    private fun consumeShareIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND) return
        val type = intent.type.orEmpty()
        if (!type.startsWith("text/") && type.isNotEmpty()) return

        val rawText = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()

        val body = ShareIntakePolicy.normalizeSharedText(rawText)
            ?: ShareIntakePolicy.normalizeSharedText(subject)
            ?: return

        IncomingShareBus.offer(
            IncomingShare(
                text = body,
                subject = ShareIntakePolicy.normalizeSharedText(subject),
            ),
        )
        // Avoid re-processing the same intent on rotation if activity recreated
        // with same intent while bus already holds payload — clear action extras.
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.removeExtra(Intent.EXTRA_SUBJECT)
        intent.action = Intent.ACTION_MAIN
    }
}

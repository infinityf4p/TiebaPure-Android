package dev.infinityf4p.tiebapure

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private var nextExternalNavigationId = 0L
    private var externalNavigationEvent by mutableStateOf<ExternalNavigationEvent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) dispatchExternalIntent(intent)
        enableEdgeToEdge()
        setContent {
            TiebaPureRoot(
                externalNavigationEvent = externalNavigationEvent,
                onExternalNavigationConsumed = ::consumeExternalNavigation,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchExternalIntent(intent)
    }

    private fun dispatchExternalIntent(intent: Intent?) {
        val input = when (intent?.action) {
            Intent.ACTION_VIEW -> ExternalNavigationInput(
                action = ExternalNavigationAction.View,
                data = intent.dataString,
            )
            Intent.ACTION_SEND -> ExternalNavigationInput(
                action = ExternalNavigationAction.SendText,
                mimeType = intent.type,
                sharedText = intent.extras?.get(Intent.EXTRA_TEXT)?.toString(),
            )
            else -> null
        } ?: return
        val destination = parseExternalNavigation(input) ?: return
        externalNavigationEvent = ExternalNavigationEvent(++nextExternalNavigationId, destination)
    }

    private fun consumeExternalNavigation(id: Long) {
        if (externalNavigationEvent?.id == id) externalNavigationEvent = null
    }
}

package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ConnectionStatus
import com.example.ui.LkProxyViewModel
import com.example.ui.MainScreen
import com.example.ui.theme.LkProxyTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_REQUEST_MIC = "extra_request_mic"
    }

    private val viewModel: LkProxyViewModel by viewModels()
    private var pendingMicRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
        handleIntentExtras(intent)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val lifecycleOwner = LocalLifecycleOwner.current
            var askedNotificationsThisSession by remember { mutableStateOf(false) }

            val overlaySettingsLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) {
                viewModel.refreshPermissions()
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    viewModel.dismissOverlayPermissionDialog()
                    viewModel.onStartClicked()
                }
            }

            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) {
                viewModel.onStartClicked()
            }

            val micPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                viewModel.onMicPermissionResult(granted)
            }

            // Refresh permission state on resume
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        viewModel.refreshPermissions()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            // Handle Voice Changer permission request triggered from floating overlay
            LaunchedEffect(pendingMicRequest) {
                if (pendingMicRequest) {
                    pendingMicRequest = false
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.onMicPermissionResult(true)
                    }
                }
            }

            // Apply legal performance window flags (keep screen on & preferred refresh rate)
            LaunchedEffect(
                uiState.connectionStatus,
                uiState.features.gameBoosterEnabled,
                uiState.features.fpsRate
            ) {
                val active = uiState.connectionStatus == ConnectionStatus.ACTIVE
                if (active && uiState.features.gameBoosterEnabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                val attrs = window.attributes
                attrs.preferredRefreshRate =
                    if (active && uiState.features.fpsRate > 0) {
                        uiState.features.fpsRate.toFloat()
                    } else {
                        0f
                    }
                window.attributes = attrs
            }

            LkProxyTheme {
                MainScreen(
                    uiState = uiState,
                    onStartClick = {
                        if (!askedNotificationsThisSession &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            askedNotificationsThisSession = true
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.onStartClicked()
                        }
                    },
                    onStopClick = { viewModel.onStopClicked() },
                    onWhatsappClick = { viewModel.setWhatsappDialogVisible(true) },
                    onOpenWhatsappExternal = {
                        viewModel.setWhatsappDialogVisible(false)
                        runCatching {
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(getString(R.string.whatsapp_channel_url))
                                )
                            )
                        }
                    },
                    onDismissWhatsappDialog = { viewModel.setWhatsappDialogVisible(false) },
                    onGrantOverlayClick = {
                        viewModel.dismissOverlayPermissionDialog()
                        val settingsIntent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        overlaySettingsLauncher.launch(settingsIntent)
                    },
                    onDismissOverlayDialog = { viewModel.dismissOverlayPermissionDialog() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentExtras(intent)
    }

    private fun handleIntentExtras(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true) {
            intent.removeExtra(EXTRA_REQUEST_MIC)
            pendingMicRequest = true
        }
    }
}

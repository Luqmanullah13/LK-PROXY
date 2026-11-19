package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ConnectionStatus
import com.example.data.LkProxyUiState
import com.example.ui.theme.LkBlack
import com.example.ui.theme.LkGrey
import com.example.ui.theme.LkRed
import com.example.ui.theme.LkSurface
import com.example.ui.theme.LkWhite
import java.util.Locale

@Composable
fun MainScreen(
    uiState: LkProxyUiState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onWhatsappClick: () -> Unit,
    onOpenWhatsappExternal: () -> Unit,
    onDismissWhatsappDialog: () -> Unit,
    onGrantOverlayClick: () -> Unit,
    onDismissOverlayDialog: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "lk_glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = if (uiState.connectionStatus == ConnectionStatus.ACTIVE) 0.42f else 0.18f,
        targetValue = if (uiState.connectionStatus == ConnectionStatus.ACTIVE) 0.78f else 0.34f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LkBlack)
            .drawBehind {
                val step = 18.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    drawLine(
                        color = Color(0x0AFF0000),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                    y += step
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            LkRed.copy(alpha = glowAlpha * 0.45f),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height * 0.34f),
                        radius = size.minDimension * 0.52f
                    ),
                    center = Offset(size.width / 2f, size.height * 0.34f),
                    radius = size.minDimension * 0.52f
                )
            }
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Top Left: "LK PROXY" small text
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.brand_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = LkWhite,
                    modifier = Modifier.testTag("top_left_brand")
                )
            }

            Spacer(modifier = Modifier.weight(1f, fill = true))
            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier.widthIn(max = 520.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 2. Center: Big Circular Logo (Man with hat)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(168.dp)
                        .drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        LkRed.copy(alpha = glowAlpha),
                                        Color.Transparent
                                    )
                                ),
                                radius = size.minDimension * 0.65f
                            )
                        }
                        .clip(CircleShape)
                        .border(width = 2.5.dp, color = LkRed, shape = CircleShape)
                        .background(LkBlack)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_lk_logo),
                        contentDescription = stringResource(R.string.cd_lk_logo),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .testTag("lk_logo_image")
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                Text(
                    text = stringResource(R.string.brand_title),
                    style = MaterialTheme.typography.displayLarge,
                    color = LkWhite,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("center_brand_title")
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = stringResource(R.string.subtitle_redline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LkGrey,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("subtitle_redline")
                )

                Spacer(modifier = Modifier.height(36.dp))

                // 3. Big Button: START -> [ LINKING... ] -> STOP + [ ACTIVE ]
                when (uiState.connectionStatus) {
                    ConnectionStatus.IDLE -> {
                        OutlinedButton(
                            onClick = onStartClick,
                            border = BorderStroke(2.dp, LkRed),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = LkBlack,
                                contentColor = LkWhite
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
                            modifier = Modifier
                                .width(240.dp)
                                .height(60.dp)
                                .testTag("start_stop_button")
                        ) {
                            Text(
                                text = stringResource(R.string.btn_start),
                                style = MaterialTheme.typography.labelLarge,
                                color = LkWhite
                            )
                        }
                    }

                    ConnectionStatus.LINKING -> {
                        val dots = ".".repeat(uiState.linkingDotsCount.coerceIn(1, 3))
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            border = BorderStroke(2.dp, LkRed),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = LkBlack,
                                disabledContainerColor = LkBlack,
                                disabledContentColor = LkRed
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                            modifier = Modifier
                                .width(240.dp)
                                .height(60.dp)
                                .testTag("start_stop_button")
                        ) {
                            Text(
                                text = "[ LINKING$dots ]",
                                style = MaterialTheme.typography.titleMedium,
                                color = LkRed
                            )
                        }
                    }

                    ConnectionStatus.ACTIVE -> {
                        Button(
                            onClick = onStopClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LkRed,
                                contentColor = LkWhite
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
                            modifier = Modifier
                                .width(240.dp)
                                .height(60.dp)
                                .testTag("start_stop_button")
                        ) {
                            Text(
                                text = stringResource(R.string.btn_stop),
                                style = MaterialTheme.typography.labelLarge,
                                color = LkWhite
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = stringResource(R.string.status_active),
                            style = MaterialTheme.typography.titleMedium,
                            color = LkRed,
                            modifier = Modifier.testTag("status_active_label")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(26.dp))

                // 4. Below: "SECURE // CONNECTION" small text
                Text(
                    text = stringResource(R.string.label_secure_connection),
                    style = MaterialTheme.typography.labelSmall,
                    color = LkWhite,
                    letterSpacing = 2.sp,
                    modifier = Modifier.testTag("secure_connection_label")
                )

                Spacer(modifier = Modifier.height(18.dp))

                // 5. Below: Small Button "WhatsApp" - Red outline
                OutlinedButton(
                    onClick = onWhatsappClick,
                    border = BorderStroke(1.5.dp, LkRed),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = LkBlack,
                        contentColor = LkWhite
                    ),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("whatsapp_button")
                ) {
                    Text(
                        text = stringResource(R.string.btn_whatsapp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LkWhite
                    )
                }

                AnimatedVisibility(
                    visible = uiState.connectionStatus == ConnectionStatus.ACTIVE &&
                        uiState.features.signalSpeedEnabled,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    val dl = formatCompactSpeed(uiState.telemetry.downloadKbps)
                    val ul = formatCompactSpeed(uiState.telemetry.uploadKbps)
                    Column(
                        modifier = Modifier
                            .padding(top = 20.dp)
                            .fillMaxWidth()
                            .border(1.dp, LkRed.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .background(LkSurface, RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "DL $dl  |  UL $ul  |  PING ${uiState.telemetry.pingMs} ms (${uiState.telemetry.slowRateLabel})",
                            style = MaterialTheme.typography.labelSmall,
                            color = LkWhite,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f, fill = true))
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (uiState.showOverlayPermissionDialog) {
        AlertDialog(
            onDismissRequest = onDismissOverlayDialog,
            containerColor = LkSurface,
            titleContentColor = LkRed,
            textContentColor = LkWhite,
            title = {
                Text(
                    text = stringResource(R.string.overlay_permission_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.overlay_permission_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LkWhite
                )
            },
            confirmButton = {
                Button(
                    onClick = onGrantOverlayClick,
                    colors = ButtonDefaults.buttonColors(containerColor = LkRed, contentColor = LkWhite),
                    modifier = Modifier.testTag("overlay_grant_button")
                ) {
                    Text(
                        text = stringResource(R.string.overlay_permission_grant),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissOverlayDialog) {
                    Text(
                        text = stringResource(R.string.overlay_permission_cancel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LkGrey
                    )
                }
            }
        )
    }

    if (uiState.showWhatsappDialog) {
        AlertDialog(
            onDismissRequest = onDismissWhatsappDialog,
            containerColor = LkSurface,
            titleContentColor = LkRed,
            textContentColor = LkWhite,
            title = {
                Text(
                    text = stringResource(R.string.whatsapp_dialog_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.whatsapp_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LkWhite
                )
            },
            confirmButton = {
                Button(
                    onClick = onOpenWhatsappExternal,
                    colors = ButtonDefaults.buttonColors(containerColor = LkRed, contentColor = LkWhite),
                    modifier = Modifier.testTag("whatsapp_open_button")
                ) {
                    Text(
                        text = stringResource(R.string.whatsapp_open_action),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissWhatsappDialog) {
                    Text(
                        text = stringResource(R.string.whatsapp_close_action),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LkGrey
                    )
                }
            }
        )
    }
}

private fun formatCompactSpeed(kbps: Double): String {
    return if (kbps >= 1024.0) {
        String.format(Locale.US, "%.1f MB/s", kbps / 1024.0)
    } else {
        String.format(Locale.US, "%.0f KB/s", kbps)
    }
}

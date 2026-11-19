package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.res.ResourcesCompat
import com.example.LkProxyApplication
import com.example.MainActivity
import com.example.R
import com.example.data.LkProxyRepository
import com.example.data.LkProxyUiState
import com.example.data.VoicePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class LkOverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.example.action.SHOW_OVERLAY"
        const val ACTION_STOP = "com.example.action.STOP_OVERLAY"
        private const val CHANNEL_ID = "lk_proxy_overlay_channel"
        private const val NOTIFICATION_ID = 2001
    }

    private lateinit var windowManager: WindowManager
    private lateinit var repository: LkProxyRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var stateCollectJob: Job? = null

    private var floatingButtonView: View? = null
    private var floatingMenuView: View? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var menuParams: WindowManager.LayoutParams? = null

    // Menu dynamic widgets bound to repository state
    private var switchGameBooster: Switch? = null
    private var labelRamInfo: TextView? = null
    private var seekFpsRate: SeekBar? = null
    private var labelFpsValue: TextView? = null

    private var switchVoiceChanger: Switch? = null
    private var labelVoiceStatus: TextView? = null
    private val voicePresetCards = mutableMapOf<VoicePreset, LinearLayout>()
    private var seekVoiceSpeed: SeekBar? = null
    private var labelVoiceSpeedValue: TextView? = null
    private var seekVoicePitch: SeekBar? = null
    private var labelVoicePitchValue: TextView? = null

    private var switchInternalAudio: Switch? = null
    private var labelAudioStatus: TextView? = null
    private var switchSignalSpeed: Switch? = null
    private var telemetryBox: LinearLayout? = null
    private var labelTelemetrySpeed: TextView? = null
    private var labelTelemetryPing: TextView? = null

    private var isProgrammaticUpdate = false

    private val fontOrbitron: Typeface? by lazy {
        ResourcesCompat.getFont(this, R.font.orbitron)
    }
    private val fontMono: Typeface? by lazy {
        ResourcesCompat.getFont(this, R.font.jetbrains_mono)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repository = (application as LkProxyApplication).repository
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                removeAllOverlayViews()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW, null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification())
                }
                if (Settings.canDrawOverlays(this)) {
                    showFloatingButton()
                    observeRepositoryState()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun observeRepositoryState() {
        stateCollectJob?.cancel()
        stateCollectJob = serviceScope.launch {
            repository.uiState.collect { state ->
                bindStateToMenu(state)
            }
        }
    }

    private fun bindStateToMenu(state: LkProxyUiState) {
        isProgrammaticUpdate = true
        try {
            switchGameBooster?.isChecked = state.features.gameBoosterEnabled
            val ramText = if (state.telemetry.totalRamMb > 0) {
                if (state.features.gameBoosterEnabled) {
                    "BOOST ACTIVE • FREE: ${state.telemetry.availableRamMb} MB"
                } else {
                    "STANDBY • FREE: ${state.telemetry.availableRamMb} MB"
                }
            } else {
                if (state.features.gameBoosterEnabled) "BOOST ACTIVE" else "STANDBY"
            }
            labelRamInfo?.text = ramText

            if (seekFpsRate?.progress != state.features.fpsRate) {
                seekFpsRate?.progress = state.features.fpsRate
            }
            labelFpsValue?.text = if (state.features.fpsRate == 0) {
                "AUTO | LIVE: ${state.telemetry.liveFps} FPS"
            } else {
                "TARGET: ${state.features.fpsRate} FPS | LIVE: ${state.telemetry.liveFps} FPS"
            }

            switchVoiceChanger?.isChecked = state.features.voiceChangerEnabled
            labelVoiceStatus?.text = state.telemetry.voiceStatusLabel

            voicePresetCards.forEach { (preset, card) ->
                val isSelected = preset == state.features.voicePreset
                card.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(
                        if (isSelected) Color.parseColor("#260000") else Color.parseColor("#111111")
                    )
                    setStroke(
                        dp(if (isSelected) 2 else 1),
                        if (isSelected) Color.RED else Color.parseColor("#3D0000")
                    )
                    cornerRadius = dp(8).toFloat()
                }
            }

            val speedProgress = (state.features.voiceSpeedRate + 100).coerceIn(0, 200)
            if (seekVoiceSpeed?.progress != speedProgress) {
                seekVoiceSpeed?.progress = speedProgress
            }
            labelVoiceSpeedValue?.text = formatSigned(state.features.voiceSpeedRate)

            val pitchProgress = (state.features.voicePitchRate + 100).coerceIn(0, 200)
            if (seekVoicePitch?.progress != pitchProgress) {
                seekVoicePitch?.progress = pitchProgress
            }
            labelVoicePitchValue?.text = formatSigned(state.features.voicePitchRate)

            switchInternalAudio?.isChecked = state.features.internalAudioEnabled
            labelAudioStatus?.text = state.telemetry.recordingStatusLabel

            switchSignalSpeed?.isChecked = state.features.signalSpeedEnabled
            telemetryBox?.visibility =
                if (state.features.signalSpeedEnabled) View.VISIBLE else View.GONE

            if (state.features.signalSpeedEnabled) {
                val dl = formatSpeed(state.telemetry.downloadKbps)
                val ul = formatSpeed(state.telemetry.uploadKbps)
                labelTelemetrySpeed?.text = "DL: $dl   UL: $ul"
                labelTelemetryPing?.text =
                    "PING: ${state.telemetry.pingMs} ms   RATE: ${state.telemetry.slowRateLabel}"
            }
        } finally {
            isProgrammaticUpdate = false
        }
    }

    private fun formatSigned(value: Int): String {
        return if (value > 0) "+$value" else "$value"
    }

    private fun formatSpeed(kbps: Double): String {
        return if (kbps >= 1024.0) {
            String.format(Locale.US, "%.1f MB/s", kbps / 1024.0)
        } else {
            String.format(Locale.US, "%.0f KB/s", kbps)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingButton() {
        removeFloatingMenu()
        if (floatingButtonView != null) return

        val sizePx = dp(62)
        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLACK)
                setStroke(dp(3), Color.RED)
            }
            elevation = dp(10).toFloat()
            contentDescription = getString(R.string.cd_floating_button)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }

        val logoImage = ImageView(this).apply {
            setImageResource(R.drawable.img_lk_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(logoImage)

        val params = buttonParams ?: createOverlayParams(
            width = sizePx,
            height = sizePx,
            x = dp(16),
            y = dp(180)
        ).also { buttonParams = it }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { windowManager.updateViewLayout(container, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        showFloatingMenu()
                    }
                    true
                }
                else -> false
            }
        }

        floatingButtonView = container
        runCatching { windowManager.addView(container, params) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingMenu() {
        removeFloatingButton()
        if (floatingMenuView != null) return

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val panelWidth = minOf(dp(330), (screenWidth * 0.92f).toInt())
        val maxScrollHeight = (screenHeight * 0.64f).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.BLACK)
                setStroke(dp(2), Color.RED)
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(14), dp(12), dp(14), dp(14))
            elevation = dp(14).toFloat()
        }

        // Draggable Header Row: "LK PROXY" + Solid Red "MINIMAZE" button
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.brand_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = fontOrbitron ?: Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val minimizeBtn = TextView(this).apply {
            text = getString(R.string.btn_minimaze)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = fontOrbitron ?: Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            minHeight = dp(48)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.RED)
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener {
                showFloatingButton()
            }
        }

        headerRow.addView(titleView)
        headerRow.addView(minimizeBtn)
        root.addView(headerRow)

        root.addView(View(this).apply {
            setBackgroundColor(Color.RED)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply { bottomMargin = dp(10) }
        })

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 1. Game Booster: ON / OFF toggle
        val (boosterRow, boosterSwitch, boosterSub) = createToggleRow(
            title = getString(R.string.feature_game_booster),
            subtitle = "STANDBY"
        ) { checked ->
            if (!isProgrammaticUpdate) repository.setGameBooster(checked)
        }
        switchGameBooster = boosterSwitch
        labelRamInfo = boosterSub
        scrollContent.addView(boosterRow)

        // 2. FPS Rate: Slider 0-90
        val fpsCard = createCardContainer()
        val fpsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val fpsTitle = TextView(this).apply {
            text = getString(R.string.feature_fps_rate)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = fontMono ?: Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val fpsReadout = TextView(this).apply {
            text = "TARGET: 60 FPS"
            setTextColor(Color.RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = fontMono ?: Typeface.MONOSPACE
        }
        labelFpsValue = fpsReadout
        fpsHeader.addView(fpsTitle)
        fpsHeader.addView(fpsReadout)
        fpsCard.addView(fpsHeader)

        val fpsSlider = SeekBar(this).apply {
            max = 90
            progress = 60
            progressTintList = ColorStateList.valueOf(Color.RED)
            thumbTintList = ColorStateList.valueOf(Color.RED)
            progressBackgroundTintList = ColorStateList.valueOf(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !isProgrammaticUpdate) {
                        repository.setFpsRate(progress)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        seekFpsRate = fpsSlider
        fpsCard.addView(fpsSlider)
        scrollContent.addView(fpsCard)

        // 3. VOICE CHANGER + 6 Real Voices with logos + Speed/Pitch -100..+100 Sliders
        val voiceCard = createCardContainer()
        val voiceHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val voiceTitleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val voiceTitle = TextView(this).apply {
            text = getString(R.string.feature_voice_changer)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = fontMono ?: Typeface.MONOSPACE
        }
        val voiceSub = TextView(this).apply {
            text = "OFF"
            setTextColor(Color.RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = fontMono ?: Typeface.MONOSPACE
        }
        labelVoiceStatus = voiceSub
        voiceTitleCol.addView(voiceTitle)
        voiceTitleCol.addView(voiceSub)

        val voiceSwitch = Switch(this).apply {
            minHeight = dp(48)
            minWidth = dp(48)
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.RED, Color.WHITE)
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#7A0000"), Color.DKGRAY)
            )
            setOnCheckedChangeListener { _, isChecked ->
                if (!isProgrammaticUpdate) {
                    repository.toggleVoiceChanger(isChecked) {
                        val launchMain = Intent(this@LkOverlayService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(MainActivity.EXTRA_REQUEST_MIC, true)
                        }
                        startActivity(launchMain)
                    }
                }
            }
        }
        switchVoiceChanger = voiceSwitch
        voiceHeaderRow.addView(voiceTitleCol)
        voiceHeaderRow.addView(voiceSwitch)
        voiceCard.addView(voiceHeaderRow)

        // 2 rows x 3 columns of real voice logos
        voicePresetCards.clear()
        val presets = VoicePreset.entries
        for (rowIdx in 0 until 2) {
            val gridRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            for (colIdx in 0 until 3) {
                val preset = presets[rowIdx * 3 + colIdx]
                val itemCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    minimumHeight = dp(56)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (colIdx < 2) marginEnd = dp(6)
                    }
                    setOnClickListener {
                        repository.selectVoicePreset(preset)
                    }
                }
                val iconView = ImageView(this).apply {
                    setImageResource(preset.iconRes)
                    contentDescription = preset.displayName
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                }
                val nameTv = TextView(this).apply {
                    text = preset.shortLabel
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                    typeface = fontMono ?: Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                }
                itemCard.addView(iconView)
                itemCard.addView(nameTv)
                voicePresetCards[preset] = itemCard
                gridRow.addView(itemCard)
            }
            voiceCard.addView(gridRow)
        }

        // Custom Speed Slider (-100 to +100)
        val (speedHeader, speedValTv) = createSliderHeader(getString(R.string.voice_speed_label), "0")
        labelVoiceSpeedValue = speedValTv
        voiceCard.addView(speedHeader)
        val speedSeek = SeekBar(this).apply {
            max = 200
            progress = 100
            progressTintList = ColorStateList.valueOf(Color.RED)
            thumbTintList = ColorStateList.valueOf(Color.RED)
            progressBackgroundTintList = ColorStateList.valueOf(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(2))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !isProgrammaticUpdate) {
                        repository.setVoiceSpeedRate(progress - 100)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        seekVoiceSpeed = speedSeek
        voiceCard.addView(speedSeek)

        // Custom Pitch Slider (-100 to +100)
        val (pitchHeader, pitchValTv) = createSliderHeader(getString(R.string.voice_pitch_label), "0")
        labelVoicePitchValue = pitchValTv
        voiceCard.addView(pitchHeader)
        val pitchSeek = SeekBar(this).apply {
            max = 200
            progress = 100
            progressTintList = ColorStateList.valueOf(Color.RED)
            thumbTintList = ColorStateList.valueOf(Color.RED)
            progressBackgroundTintList = ColorStateList.valueOf(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(2))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !isProgrammaticUpdate) {
                        repository.setVoicePitchRate(progress - 100)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        seekVoicePitch = pitchSeek
        voiceCard.addView(pitchSeek)

        scrollContent.addView(voiceCard)

        // 4. Internal Audio recording: ON/OFF
        val (audioRow, audioSwitch, audioSub) = createToggleRow(
            title = getString(R.string.feature_internal_audio),
            subtitle = "OFF"
        ) { checked ->
            if (!isProgrammaticUpdate) {
                repository.toggleInternalAudio(checked)
            }
        }
        switchInternalAudio = audioSwitch
        labelAudioStatus = audioSub
        scrollContent.addView(audioRow)

        // 5. Signal Speed and slow rate show: ON / OFF
        val (signalRow, signalSwitch, signalSub) = createToggleRow(
            title = getString(R.string.feature_signal_speed),
            subtitle = "LIVE SPEED & PING HUD"
        ) { checked ->
            if (!isProgrammaticUpdate) {
                repository.setSignalSpeedEnabled(checked)
            }
        }
        switchSignalSpeed = signalSwitch
        signalSub.setTextColor(Color.parseColor("#8C8C94"))
        scrollContent.addView(signalRow)

        val speedHud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#0D0D0D"))
                setStroke(dp(1), Color.RED)
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val speedTxt = TextView(this).apply {
            text = "DL: 0 KB/s   UL: 0 KB/s"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = fontMono ?: Typeface.MONOSPACE
        }
        val pingTxt = TextView(this).apply {
            text = "PING: -- ms   RATE: STANDBY"
            setTextColor(Color.RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = fontMono ?: Typeface.MONOSPACE
        }
        labelTelemetrySpeed = speedTxt
        labelTelemetryPing = pingTxt
        speedHud.addView(speedTxt)
        speedHud.addView(pingTxt)
        telemetryBox = speedHud
        scrollContent.addView(speedHud)

        val scrollView = ScrollView(this).apply {
            addView(scrollContent)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxScrollHeight
            )
        }
        root.addView(scrollView)

        val params = menuParams ?: createOverlayParams(
            width = panelWidth,
            height = ViewGroup.LayoutParams.WRAP_CONTENT,
            x = dp(16),
            y = dp(90)
        ).also { menuParams = it }
        params.width = panelWidth

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        headerRow.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    runCatching { windowManager.updateViewLayout(root, params) }
                    true
                }
                else -> false
            }
        }

        floatingMenuView = root
        runCatching { windowManager.addView(root, params) }
        bindStateToMenu(repository.uiState.value)
    }

    private fun createSliderHeader(title: String, initialValue: String): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val labelTv = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = fontMono ?: Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valTv = TextView(this).apply {
            text = initialValue
            setTextColor(Color.RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = fontMono ?: Typeface.MONOSPACE
        }
        row.addView(labelTv)
        row.addView(valTv)
        return row to valTv
    }

    private fun createCardContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#0B0B0B"))
                setStroke(dp(1), Color.parseColor("#4D0000"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun createToggleRow(
        title: String,
        subtitle: String,
        onToggle: (Boolean) -> Unit
    ): Triple<LinearLayout, Switch, TextView> {
        val card = createCardContainer().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleTv = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = fontMono ?: Typeface.MONOSPACE
        }

        val subTv = TextView(this).apply {
            text = subtitle
            setTextColor(Color.RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = fontMono ?: Typeface.MONOSPACE
        }

        textCol.addView(titleTv)
        textCol.addView(subTv)

        val toggleSwitch = Switch(this).apply {
            minHeight = dp(48)
            minWidth = dp(48)
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.RED, Color.WHITE)
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#7A0000"), Color.DKGRAY)
            )
            setOnCheckedChangeListener { _, isChecked ->
                onToggle(isChecked)
            }
        }

        card.addView(textCol)
        card.addView(toggleSwitch)
        return Triple(card, toggleSwitch, subTv)
    }

    private fun createOverlayParams(
        width: Int,
        height: Int,
        x: Int,
        y: Int
    ): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun removeFloatingButton() {
        floatingButtonView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        floatingButtonView = null
    }

    private fun removeFloatingMenu() {
        floatingMenuView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        floatingMenuView = null
        switchGameBooster = null
        labelRamInfo = null
        seekFpsRate = null
        labelFpsValue = null
        switchVoiceChanger = null
        labelVoiceStatus = null
        voicePresetCards.clear()
        seekVoiceSpeed = null
        labelVoiceSpeedValue = null
        seekVoicePitch = null
        labelVoicePitchValue = null
        switchInternalAudio = null
        labelAudioStatus = null
        switchSignalSpeed = null
        telemetryBox = null
        labelTelemetrySpeed = null
        labelTelemetryPing = null
    }

    private fun removeAllOverlayViews() {
        stateCollectJob?.cancel()
        removeFloatingButton()
        removeFloatingMenu()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_overlay),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LkOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_active_title))
            .setContentText(getString(R.string.notification_active_text))
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, getString(R.string.notification_stop_action), stopPendingIntent)
            .build()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onDestroy() {
        removeAllOverlayViews()
        serviceScope.cancel()
        super.onDestroy()
    }
}

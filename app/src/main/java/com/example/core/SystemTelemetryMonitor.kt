package com.example.core

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class SystemTelemetryMonitor(private val context: Context) {

    private var lastRxBytes: Long = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
    private var lastTxBytes: Long = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
    private var lastSampleTimeMs: Long = System.currentTimeMillis()

    @Volatile
    private var measuredFps: Int = 60

    private var frameCount = 0
    private var lastFpsWindowNs = 0L
    private var choreographerRunning = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!choreographerRunning) return
            if (lastFpsWindowNs == 0L) {
                lastFpsWindowNs = frameTimeNanos
            }
            frameCount++
            val elapsedNs = frameTimeNanos - lastFpsWindowNs
            if (elapsedNs >= 1_000_000_000L) {
                measuredFps = ((frameCount * 1_000_000_000L) / elapsedNs).toInt().coerceIn(1, 120)
                frameCount = 0
                lastFpsWindowNs = frameTimeNanos
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun startFpsMonitoring() {
        if (choreographerRunning) return
        choreographerRunning = true
        frameCount = 0
        lastFpsWindowNs = 0L
        mainHandler.post {
            if (choreographerRunning) {
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }
    }

    fun stopFpsMonitoring() {
        choreographerRunning = false
        mainHandler.post {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    fun getLiveFps(): Int = measuredFps

    fun readMemoryInfo(): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024L * 1024L)
        val totalMb = memInfo.totalMem / (1024L * 1024L)
        return availMb to totalMb
    }

    fun trimAppCaches(): Long {
        val before = readMemoryInfo().first
        runCatching {
            context.cacheDir?.listFiles()?.forEach { file ->
                if (file.isFile && System.currentTimeMillis() - file.lastModified() > 60_000L) {
                    file.delete()
                }
            }
            System.gc()
        }
        val after = readMemoryInfo().first
        return (after - before).coerceAtLeast(0L)
    }

    fun sampleTrafficSpeedKbps(): Pair<Double, Double> {
        val now = System.currentTimeMillis()
        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()
        val dtSec = ((now - lastSampleTimeMs).coerceAtLeast(200L)) / 1000.0

        val downKbps = if (currentRx >= 0 && lastRxBytes >= 0) {
            ((currentRx - lastRxBytes).coerceAtLeast(0L) / 1024.0) / dtSec
        } else 0.0

        val upKbps = if (currentTx >= 0 && lastTxBytes >= 0) {
            ((currentTx - lastTxBytes).coerceAtLeast(0L) / 1024.0) / dtSec
        } else 0.0

        if (currentRx >= 0) lastRxBytes = currentRx
        if (currentTx >= 0) lastTxBytes = currentTx
        lastSampleTimeMs = now
        return downKbps to upKbps
    }

    suspend fun measurePingAndSlowRate(): Pair<Int, String> = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNetwork)
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!hasInternet) {
            return@withContext 0 to "OFFLINE"
        }

        val start = System.nanoTime()
        val pingMs = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("1.1.1.1", 443), 1500)
            }
            ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
        }.getOrElse {
            -1
        }

        val label = when {
            pingMs < 0 -> "TIMEOUT"
            pingMs <= 45 -> "FAST"
            pingMs <= 95 -> "STABLE"
            pingMs <= 160 -> "MODERATE"
            else -> "SLOW"
        }
        pingMs.coerceAtLeast(0) to label
    }
}

package com.example.service

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.LkProxyApplication

class MediaProjectionConsentActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val repo = (application as LkProxyApplication).repository
        repo.onMediaProjectionConsentResult(result.resultCode, result.data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            (application as LkProxyApplication).repository.onMediaProjectionConsentResult(
                Activity.RESULT_CANCELED,
                null
            )
            finish()
            return
        }
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}

package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.LkProxyApplication
import com.example.data.LkProxyUiState
import kotlinx.coroutines.flow.StateFlow

class LkProxyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as LkProxyApplication).repository

    val uiState: StateFlow<LkProxyUiState> = repository.uiState

    fun refreshPermissions() {
        repository.refreshPermissions()
    }

    fun onStartClicked() {
        repository.onStartClicked()
    }

    fun onStopClicked() {
        repository.onStopClicked()
    }

    fun dismissOverlayPermissionDialog() {
        repository.dismissOverlayPermissionDialog()
    }

    fun setWhatsappDialogVisible(visible: Boolean) {
        repository.setWhatsappDialogVisible(visible)
    }

    fun onMicPermissionResult(granted: Boolean) {
        repository.refreshPermissions()
        if (granted) {
            repository.toggleVoiceChanger(true)
        }
    }
}

package com.chess.tournament.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chess.tournament.backup.BackupManager
import com.chess.tournament.backup.BackupResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val backupManager = BackupManager(application)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _lastResult = MutableStateFlow<BackupResult?>(null)
    val lastResult: StateFlow<BackupResult?> = _lastResult

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            _lastResult.value = null
            val result = backupManager.exportToUri(uri)
            _lastResult.value = result
            _isLoading.value = false
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            _lastResult.value = null
            val result = backupManager.importFromUri(uri)
            _lastResult.value = result
            _isLoading.value = false
        }
    }

    fun clearResult() {
        _lastResult.value = null
    }
}

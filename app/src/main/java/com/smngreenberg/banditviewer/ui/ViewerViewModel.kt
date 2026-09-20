package com.smngreenberg.banditviewer.ui

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smngreenberg.banditviewer.camera.BanditApi
import com.smngreenberg.banditviewer.camera.BanditException
import com.smngreenberg.banditviewer.camera.ViewfinderReceiver
import com.smngreenberg.banditviewer.network.WifiBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class UiState {
    object Idle : UiState()
    data class Connecting(val step: String) : UiState()
    object Streaming : UiState()
    data class Error(val message: String) : UiState()
}

class ViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val wifiBinder = WifiBinder(application)
    private val api = BanditApi()
    private val receiver = ViewfinderReceiver()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _latestFrame = MutableStateFlow<ImageBitmap?>(null)
    val latestFrame: StateFlow<ImageBitmap?> = _latestFrame.asStateFlow()

    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _droppedFrames = MutableStateFlow(0)
    val droppedFrames: StateFlow<Int> = _droppedFrames.asStateFlow()

    private val _batteryPct = MutableStateFlow<Int?>(null)
    val batteryPct: StateFlow<Int?> = _batteryPct.asStateFlow()

    private val _noFramesWarning = MutableStateFlow(false)
    val noFramesWarning: StateFlow<Boolean> = _noFramesWarning.asStateFlow()

    private var streamJob: Job? = null
    @Volatile
    private var lastFrameTime = 0L
    @Volatile
    private var networkLost = false

    init {
        wifiBinder.onNetworkLost = {
            networkLost = true
            streamJob?.cancel(CancellationException("Camera Wi-Fi connection lost."))
        }
    }

    fun startStreaming() {
        if (streamJob?.isActive == true) return

        streamJob = viewModelScope.launch {
            networkLost = false
            var errorMessage: String? = null
            var viewfinderStarted = false
            try {
                _uiState.value = UiState.Connecting("Binding Wi-Fi...")
                val bindResult = wifiBinder.bind()
                if (bindResult.isFailure) {
                    errorMessage = bindResult.exceptionOrNull()?.message ?: "Wi-Fi bind failed"
                    return@launch
                }

                _uiState.value = UiState.Connecting("Checking API version...")
                val version = withContext(Dispatchers.IO) { api.getVersion() }
                if (version != "2") {
                    throw BanditException("Unsupported API version: $version (expected 2)")
                }

                _uiState.value = UiState.Connecting("Preparing camera...")
                val status = withContext(Dispatchers.IO) { api.getStatus() }
                if (status.viewfinderActive == true) {
                    withContext(Dispatchers.IO) { api.stopViewfinder() }
                    delay(500)
                }

                _uiState.value = UiState.Connecting("Opening UDP socket...")
                withContext(Dispatchers.IO) { receiver.open(4001) }

                _uiState.value = UiState.Connecting("Starting viewfinder...")
                viewfinderStarted = true
                withContext(Dispatchers.IO) { api.startViewfinder(4001) }

                _uiState.value = UiState.Streaming
                _noFramesWarning.value = false
                lastFrameTime = System.currentTimeMillis()

                coroutineScope {
                    launch { monitorStatus() }
                    launch { monitorFps() }
                    launch { monitorFrameTimeout() }
                    launch { monitorDroppedFrames() }

                    receiver.run { frame ->
                        val bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
                        if (bitmap != null) {
                            _latestFrame.value = bitmap.asImageBitmap()
                            lastFrameTime = System.currentTimeMillis()
                            _noFramesWarning.value = false
                        }
                    }
                }

            } catch (e: Exception) {
                if (e !is CancellationException && isActive) {
                    errorMessage = e.message ?: "Unknown error"
                }
            } finally {
                withContext(NonCancellable) {
                    stopInternal(viewfinderStarted && !networkLost)
                    if (networkLost) {
                        _uiState.value = UiState.Error("Camera Wi-Fi connection lost.")
                    } else if (errorMessage != null) {
                        _uiState.value = UiState.Error(errorMessage)
                    } else {
                        _uiState.value = UiState.Idle
                    }
                }
            }
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
    }

    private suspend fun stopInternal(viewfinderStarted: Boolean) {
        withContext(Dispatchers.IO) {
            if (viewfinderStarted) {
                try { api.stopViewfinder() } catch (e: Exception) {}
            }
            receiver.close()
            wifiBinder.unbind()
        }
        _latestFrame.value = null
        _fps.value = 0
        _droppedFrames.value = 0
        _noFramesWarning.value = false
        _batteryPct.value = null
    }

    private suspend fun CoroutineScope.monitorStatus() {
        while (isActive) {
            try {
                val status = withContext(Dispatchers.IO) { api.getStatus() }
                _batteryPct.value = status.batteryPct
            } catch (e: Exception) {
                // Ignore status poll failures
            }
            delay(10000)
        }
    }

    private suspend fun CoroutineScope.monitorFps() {
        var lastCount = 0
        while (isActive) {
            delay(1000)
            val currentCount = receiver.receivedFrames
            _fps.value = currentCount - lastCount
            lastCount = currentCount
        }
    }

    private suspend fun CoroutineScope.monitorDroppedFrames() {
        while (isActive) {
            _droppedFrames.value = receiver.droppedFrames
            delay(500)
        }
    }

    private suspend fun CoroutineScope.monitorFrameTimeout() {
        while (isActive) {
            if (_uiState.value == UiState.Streaming && System.currentTimeMillis() - lastFrameTime > 5000) {
                _noFramesWarning.value = true
            }
            delay(1000)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopStreaming()
    }
}

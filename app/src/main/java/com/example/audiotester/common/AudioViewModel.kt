package com.example.audiotester.common

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Unified audio ViewModel: driven by a single [AudioEngine], handles config loading/reloading,
 * start/stop, state and messages.
 * Each feature differs only via engine / section / messages; ViewModelProvider scope is isolated per Fragment.
 */
class AudioViewModel(
    application: Application,
    private val engine: AudioEngine,
    private val section: String,
    private val messages: AudioMessages,
    // Injectable for deterministic interleaving control in unit tests
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {

    private val _state = MutableLiveData(AudioState.IDLE)
    val state: LiveData<AudioState> = _state

    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> = _statusMessage

    private val _errorMessage = MutableLiveData<AudioErrorType?>()
    val errorMessage: LiveData<AudioErrorType?> = _errorMessage

    private val _currentConfig = MutableLiveData<AudioConfig>()
    val currentConfig: LiveData<AudioConfig> = _currentConfig

    private val _availableConfigs = MutableLiveData<List<AudioConfig>>()
    val availableConfigs: LiveData<List<AudioConfig>> = _availableConfigs

    // Only touched on the main thread (start/stop/onStarted)
    private var stopRequested = false

    init {
        setupEngineListener()
        loadConfigurations()
        _statusMessage.value = messages.ready
    }

    private fun loadConfigurations() {
        viewModelScope.launch(ioDispatcher) {
            val configs = AudioConfig.loadConfigs(getApplication(), section)
            updateUI {
                _availableConfigs.value = configs
                if (configs.isNotEmpty()) {
                    // No status rewrite here: init already showed the ready text, and a slow
                    // initial load landing after Start must not overwrite the preparing text
                    val defaultConfig = configs[0]
                    _currentConfig.value = engine.setAudioConfig(defaultConfig)
                }
            }
        }
    }

    fun reloadConfigurations(previousPosition: Int) {
        if (_state.value == AudioState.ACTIVE || _state.value == AudioState.STARTING) {
            updateUI {
                _statusMessage.value = "Cannot reload configuration while active"
                _errorMessage.value = AudioErrorType.ALREADY_ACTIVE
            }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            // loadConfigs already catches all exceptions internally (failures fall back to emergency defaults); no extra try needed here
            val configs = AudioConfig.loadConfigs(getApplication(), section)
            updateUI {
                if (configs.isNotEmpty()) {
                    _availableConfigs.value = configs
                    // Restore by selected position rather than description: descriptions may be
                    // duplicated (custom /data configs); position naturally matches the UI selection
                    val newConfig = configs.getOrNull(previousPosition) ?: configs[0]
                    _currentConfig.value = engine.setAudioConfig(newConfig)
                    _statusMessage.value = "Configuration reloaded successfully: ${configs.size} configs"
                } else {
                    _statusMessage.value = "Configuration file is empty or format error"
                    _errorMessage.value = AudioErrorType.PARAM
                }
            }
        }
    }

    /** Must be called on the main thread (writes LiveData directly) */
    fun start() {
        if (_state.value == AudioState.ACTIVE || _state.value == AudioState.STARTING) return
        stopRequested = false
        _errorMessage.value = null
        _state.value = AudioState.STARTING
        _statusMessage.value = messages.preparing

        viewModelScope.launch(ioDispatcher) {
            val success = engine.start()
            if (!success) {
                updateUI {
                    if (_state.value != AudioState.ERROR) {
                        _state.value = AudioState.ERROR
                        _statusMessage.value = messages.failed
                    }
                }
            }
        }
    }

    /** Must be called on the main thread (writes LiveData directly) */
    fun stop() {
        stopRequested = true
        if (_state.value != AudioState.ACTIVE) return
        _statusMessage.value = "Stopping..."
        engine.stop()
    }

    /** Must be called on the main thread (writes LiveData directly); spinner callback is the only production caller */
    fun setAudioConfig(config: AudioConfig) {
        // Describe what the engine actually applied, not what was requested: a rejected
        // (ACTIVE) request returns the previous config, and the status must not claim
        // the rejected one took effect. Same main-thread-direct style as start/stop —
        // no updateUI hop for a call that is already on the main thread
        val applied = engine.setAudioConfig(config)
        _currentConfig.value = applied
        _statusMessage.value = "Configuration updated: ${applied.description}"
        _errorMessage.value = null
    }

    fun getAllAudioConfigs(): List<AudioConfig> = _availableConfigs.value ?: emptyList()

    fun clearError() {
        _errorMessage.value = null
        if (_state.value == AudioState.ERROR) {
            _state.value = AudioState.IDLE
            // Do not rewrite _statusMessage: the error text stays in the status bar until
            // something else writes it. In the active-observer stop path the follow-up
            // onStopped does overwrite it ("Recording Stopped") — accepted: the dialog is
            // the error surface there, and dismissing restores the ready text
        }
    }

    override fun onCleared() {
        engine.release()
    }

    private fun setupEngineListener() {
        engine.setListener(object : AudioEngine.Listener {
            override fun onStarted() {
                updateUI {
                    if (stopRequested) {
                        // stop() landed during the startup window: _state was still IDLE so only
                        // the flag was set. The engine has committed by now — stop it for real;
                        // the resulting onStopped syncs the UI back to IDLE
                        engine.stop()
                    } else {
                        _state.value = AudioState.ACTIVE
                        _statusMessage.value = messages.active
                    }
                    _errorMessage.value = null
                }
            }

            override fun onStopped() {
                updateUI {
                    // An error reported during release (e.g. WAV finalization failed) is queued
                    // ahead of this confirmation. Active observers consume it immediately
                    // (dialog + clearError); inactive ones (stop while backgrounded) rely on
                    // this guard so the error survives until it is delivered
                    if (_state.value != AudioState.ERROR) {
                        _state.value = AudioState.IDLE
                        _statusMessage.value = messages.stopped
                        _errorMessage.value = null
                    }
                }
            }

            override fun onError(type: AudioErrorType, detail: String) {
                updateUI {
                    _state.value = AudioState.ERROR
                    _statusMessage.value = messages.failed
                    _errorMessage.value = type
                }
            }
        })
    }

    private fun updateUI(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) { block() }
    }

    class Factory(
        private val application: Application,
        private val engineFactory: (Application) -> AudioEngine,
        private val section: String,
        private val messages: AudioMessages,
    ) : ViewModelProvider.Factory {
        // engine is created together with the ViewModel: when the ViewModel survives and is reused
        // (e.g. rotation), no new engine is created
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AudioViewModel(application, engineFactory(application), section, messages) as T
    }
}

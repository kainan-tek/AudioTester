package com.example.audiotester.common

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
) : AndroidViewModel(application) {

    private val _state = MutableLiveData(AudioState.IDLE)
    val state: LiveData<AudioState> = _state

    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> = _statusMessage

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _currentConfig = MutableLiveData<AudioConfig>()
    val currentConfig: LiveData<AudioConfig> = _currentConfig

    private val _availableConfigs = MutableLiveData<List<AudioConfig>>()
    val availableConfigs: LiveData<List<AudioConfig>> = _availableConfigs

    @Volatile
    private var stopRequested = false

    init {
        setupEngineListener()
        loadConfigurations()
        _statusMessage.value = messages.ready
    }

    private fun loadConfigurations() {
        viewModelScope.launch(Dispatchers.IO) {
            val configs = AudioConfig.loadConfigs(getApplication(), section)
            updateUI({
                _availableConfigs.value = configs
                if (configs.isNotEmpty()) {
                    val defaultConfig = configs[0]
                    engine.setAudioConfig(defaultConfig)
                    _currentConfig.value = defaultConfig
                    _statusMessage.value = messages.ready
                }
            })
        }
    }

    fun reloadConfigurations(previousPosition: Int) {
        if (_state.value == AudioState.ACTIVE) {
            updateUI({
                _statusMessage.value = "Cannot reload configuration while active"
                _errorMessage.value = "Please stop the current operation before reloading configuration"
            }, clearError = false)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // loadConfigs already catches all exceptions internally (failures fall back to emergency defaults); no extra try needed here
            val configs = AudioConfig.loadConfigs(getApplication(), section)
            updateUI({
                if (configs.isNotEmpty()) {
                    _availableConfigs.value = configs
                    // Restore by selected position rather than description: descriptions may be
                    // duplicated (custom /data configs); position naturally matches the UI selection
                    val newConfig = configs.getOrNull(previousPosition) ?: configs[0]
                    engine.setAudioConfig(newConfig)
                    _currentConfig.value = newConfig
                    _statusMessage.value = "Configuration reloaded successfully: ${configs.size} configs"
                } else {
                    _statusMessage.value = "Configuration file is empty or format error"
                    _errorMessage.value = "No valid audio configuration found"
                }
            }, clearError = false)
        }
    }

    /** Must be called on the main thread (writes LiveData directly) */
    fun start() {
        if (_state.value == AudioState.ACTIVE) return
        stopRequested = false
        _errorMessage.value = null
        if (_state.value == AudioState.ERROR) _state.value = AudioState.IDLE
        _statusMessage.value = messages.preparing

        viewModelScope.launch(Dispatchers.IO) {
            val success = engine.start()
            if (success && stopRequested) {
                // Canceled by stop() during startup (e.g. tab-switch mutual exclusion); stop immediately
                engine.stop()
                return@launch
            }
            if (!success) {
                updateUI({
                    if (_state.value != AudioState.ERROR) {
                        _state.value = AudioState.ERROR
                        _statusMessage.value = messages.failed
                    }
                }, clearError = false)
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

    fun setAudioConfig(config: AudioConfig) {
        engine.setAudioConfig(config)
        updateUI({
            _currentConfig.value = config
            _statusMessage.value = "Configuration updated: ${config.description}"
        })
    }

    fun getAllAudioConfigs(): List<AudioConfig> = _availableConfigs.value ?: emptyList()

    fun clearError() {
        _errorMessage.value = null
        if (_state.value == AudioState.ERROR) {
            _state.value = AudioState.IDLE
            // Do not rewrite _statusMessage: the error text must stay in the status bar; recovery
            // timing is left to the error dialog's OK/Cancel
        }
    }

    override fun onCleared() {
        engine.release()
    }

    private fun setupEngineListener() {
        engine.setListener(object : AudioEngine.Listener {
            override fun onStarted() {
                updateUI({
                    _state.value = AudioState.ACTIVE
                    _statusMessage.value = messages.active
                })
            }

            override fun onStopped() {
                updateUI({
                    _state.value = AudioState.IDLE
                    _statusMessage.value = messages.stopped
                })
            }

            override fun onError(error: String) {
                updateUI({
                    _state.value = AudioState.ERROR
                    _statusMessage.value = messages.failed
                    _errorMessage.value = error
                }, clearError = false)
            }
        })
    }

    private fun updateUI(block: () -> Unit, clearError: Boolean = true) {
        viewModelScope.launch(Dispatchers.Main) {
            block()
            if (clearError) _errorMessage.value = null
        }
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

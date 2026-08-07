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
 * 统一音频 ViewModel：由一个 [AudioEngine] 驱动，处理配置加载/重载、启停、状态与文案。
 * 各特性仅通过 engine / section / messages 区分；ViewModelProvider 作用域按 Fragment 隔离。
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

    fun reloadConfigurations() {
        if (_state.value == AudioState.ACTIVE) {
            updateUI({
                _statusMessage.value = "Cannot reload configuration while active"
                _errorMessage.value = "Please stop the current operation before reloading configuration"
            }, clearError = false)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val configs = AudioConfig.reloadConfigs(getApplication(), section)
                updateUI({
                    if (configs.isNotEmpty()) {
                        _availableConfigs.value = configs
                        val currentDescription = _currentConfig.value?.description
                        val newConfig = configs.find { it.description == currentDescription } ?: configs[0]
                        engine.setAudioConfig(newConfig)
                        _currentConfig.value = newConfig
                        _statusMessage.value = "Configuration reloaded successfully: ${configs.size} configs"
                    } else {
                        _statusMessage.value = "Configuration file is empty or format error"
                        _errorMessage.value = "No valid audio configuration found"
                    }
                }, clearError = false)
            } catch (e: Exception) {
                updateUI({
                    _statusMessage.value = "Configuration reload failed"
                    _errorMessage.value = "Configuration reload failed: ${e.message}"
                }, clearError = false)
            }
        }
    }

    /** 必须在主线程调用（直接写 LiveData） */
    fun start() {
        if (_state.value == AudioState.ACTIVE) return
        _errorMessage.value = null
        if (_state.value == AudioState.ERROR) _state.value = AudioState.IDLE
        _statusMessage.value = messages.preparing

        viewModelScope.launch(Dispatchers.IO) {
            val success = engine.start()
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

    /** 必须在主线程调用（直接写 LiveData） */
    fun stop() {
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
            _statusMessage.value = messages.ready
        }
    }

    fun release() {
        engine.release()
    }

    fun isActive(): Boolean = engine.isActive()

    override fun onCleared() {
        super.onCleared()
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
        private val engine: AudioEngine,
        private val section: String,
        private val messages: AudioMessages,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AudioViewModel(application, engine, section, messages) as T
    }
}

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

    fun reloadConfigurations() {
        if (_state.value == AudioState.ACTIVE) {
            updateUI({
                _statusMessage.value = "Cannot reload configuration while active"
                _errorMessage.value = "Please stop the current operation before reloading configuration"
            }, clearError = false)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // loadConfigs 内部已捕获全部异常（失败回退 emergency 默认配置），此处无需再包 try
            val configs = AudioConfig.loadConfigs(getApplication(), section)
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
        }
    }

    /** 必须在主线程调用（直接写 LiveData） */
    fun start() {
        if (_state.value == AudioState.ACTIVE) return
        stopRequested = false
        _errorMessage.value = null
        if (_state.value == AudioState.ERROR) _state.value = AudioState.IDLE
        _statusMessage.value = messages.preparing

        viewModelScope.launch(Dispatchers.IO) {
            val success = engine.start()
            if (success && stopRequested) {
                // 启动期间被 stop() 取消（如切 Tab 互斥），立即停止
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

    /** 必须在主线程调用（直接写 LiveData） */
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
            // 不重写 _statusMessage：错误文案需保留在状态栏，恢复时机交给错误对话框的 OK/Cancel
        }
    }

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
        private val engineFactory: (Application) -> AudioEngine,
        private val section: String,
        private val messages: AudioMessages,
    ) : ViewModelProvider.Factory {
        // engine 与 ViewModel 同步创建：ViewModel 存活复用时（如旋转）不新建 engine
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AudioViewModel(application, engineFactory(application), section, messages) as T
    }
}

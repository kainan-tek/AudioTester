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
    // Injectable so tests can force an empty config list: the real loader falls back to
    // emergency defaults on failure, so only a valid-but-empty file can yield an empty list
    private val loadConfigs: (String) -> List<AudioConfig> =
        { section -> AudioConfig.loadConfigs(application, section) },
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

    // Only touched on the main thread (reloadConfigurations entry / its updateUI block / start):
    // true from reload admission until its apply lands, so Start cannot race the reload's
    // un-guarded async setAudioConfig (same main-thread-confined pattern as stopRequested)
    private var reloadInFlight = false

    init {
        setupEngineListener()
        loadConfigurations()
        _statusMessage.value = messages.ready
    }

    private fun loadConfigurations() {
        viewModelScope.launch(ioDispatcher) {
            val configs = loadConfigs(section)
            updateUI {
                _availableConfigs.value = configs
                if (configs.isNotEmpty()) {
                    // Landing rewrites the status: a Start/reload refusal during the load window
                    // may have left "Configuration loading, please wait" here. The landing is
                    // guaranteed IDLE (start and reload are both refused until this lands) — the
                    // old "no status rewrite" concern (a preparing text being overwritten) was
                    // voided by those refusals. One benign overwrite remains: a fragment-side
                    // "Permission granted" (written directly by the permission launcher) landing
                    // just before this is replaced — VM-side status stays authoritative
                    val defaultConfig = configs[0]
                    _currentConfig.value = engine.setAudioConfig(defaultConfig)
                    _statusMessage.value = messages.ready
                } else {
                    // Same diagnosis as the reload empty branch; also recycles a stale waiting text
                    _statusMessage.value = "Configuration file is empty or format error"
                }
            }
        }
    }

    fun reloadConfigurations(previousPosition: Int) {
        // Re-entry refusal: reloadInFlight clears when the FIRST reload lands, so an admitted
        // second reload would reopen the start-vs-apply race after that clearing. Its waiting
        // text is recycled by the in-flight reload's own landing status write
        if (reloadInFlight) {
            _statusMessage.value = "Configuration reloading, please wait"
            return
        }
        // Same refusal family as start()'s not-loaded guard: a reload landing first would set
        // _availableConfigs while the initial load is still pending, letting Start slip in
        // before the initial landing and breaking the IDLE-at-landing invariant that the
        // initial apply and status write rely on
        if (_availableConfigs.value == null) {
            _statusMessage.value = "Configuration loading, please wait"
            return
        }
        if (_state.value == AudioState.ACTIVE || _state.value == AudioState.STARTING) {
            updateUI {
                _statusMessage.value = "Cannot reload configuration while active"
                _errorMessage.value = AudioErrorType.ALREADY_ACTIVE
            }
            return
        }
        reloadInFlight = true
        viewModelScope.launch(ioDispatcher) {
            // loadConfigs already catches all exceptions internally (failures fall back to emergency defaults); no extra try needed here
            val configs = loadConfigs(section)
            updateUI {
                if (configs.isNotEmpty()) {
                    _availableConfigs.value = configs
                    // Restore by selected position rather than description: descriptions may be
                    // duplicated (custom /data configs); position naturally matches the UI selection
                    val newConfig = configs.getOrNull(previousPosition) ?: configs[0]
                    _currentConfig.value = engine.setAudioConfig(newConfig)
                    _statusMessage.value = "Configuration reloaded successfully: ${configs.size} configs"
                } else {
                    // No dialog: an empty file is not an "invalid configuration" — the status
                    // bar carries the accurate diagnosis (PARAM would misdescribe it and offer
                    // picking from the stale, unrefreshed list)
                    _statusMessage.value = "Configuration file is empty or format error"
                }
                // Cleared here, in the same main-thread message as the apply: start() reads it
                // on the main thread too, so no admitted start can overlap the apply. The
                // status writes above always overwrite the start-refusal waiting text
                reloadInFlight = false
            }
        }
    }

    /** Must be called on the main thread (writes LiveData directly) */
    fun start() {
        if (_state.value == AudioState.ACTIVE || _state.value == AudioState.STARTING) return
        // Refuse before the initial async config load lands: the engine would silently run on
        // the built-in default while the spinner shows configs[0], and the load's setAudioConfig
        // (rejected as ACTIVE) would permanently desync UI and engine. loadConfigs always
        // completes (failures fall back to emergency defaults), so this cannot wedge Start
        val available = _availableConfigs.value
        if (available == null) {
            _statusMessage.value = "Configuration loading, please wait"
            return
        }
        // Same refusal family as the initial-load guard above, extended to the reload window:
        // a Start admitted here would either get a never-permission-gated config accepted into
        // a STARTING engine, or its own commit would make the reload's apply land on an ACTIVE
        // engine while the status claims it succeeded. The reload's completion status write
        // always replaces this text
        if (reloadInFlight) {
            _statusMessage.value = "Configuration reloading, please wait"
            return
        }
        // Same refusal family, for the empty-list variant: an empty section (or every entry
        // skipped as invalid) leaves the engine's constructor default as the only config —
        // starting would run it while the spinner is empty and the info panel shows nothing.
        // Checked after reloadInFlight so an in-flight reload (which may still land a list)
        // keeps the more accurate waiting text
        if (available.isEmpty()) {
            _statusMessage.value = "No configurations available"
            return
        }
        stopRequested = false
        _errorMessage.value = null
        _state.value = AudioState.STARTING
        _statusMessage.value = messages.preparing

        viewModelScope.launch(ioDispatcher) {
            val success = engine.start()
            if (!success) {
                updateUI {
                    // Backstop for a `false` without any onError — the only remaining such path
                    // is a released engine (hook failures are converted to typed errors by
                    // AudioEngineBase.ensureErrorReported). Nothing was reported, so un-wedging
                    // STARTING into a messageless ERROR is correct here. When an error WAS
                    // reported, it was already consumed (dialog + clearError → IDLE) or sits
                    // undelivered in ERROR — re-setting ERROR would strand a terminal ERROR with
                    // a null message (consume-on-delivery invariant)
                    if (_state.value == AudioState.STARTING) {
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
        // Describe what the engine actually applied, not what was requested: a rejected (ACTIVE)
        // request returns the previous config, and the status must not claim it took effect.
        // Same main-thread-direct style as start/stop — no updateUI hop for a call already there
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

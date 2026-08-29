package com.example.audiotester.common

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.audiotester.R
import androidx.core.net.toUri

/**
 * Abstract base class: carries all shared UI wiring (observers, Spinner, button states,
 * error dialog, permissions, stop on onPause).
 * Subclasses only provide feature-specific differences (engine/section/messages/permissions/info format/error translation).
 */
abstract class AudioTestFragment : Fragment() {

    protected lateinit var viewModel: AudioViewModel
    protected lateinit var startButton: Button
    protected lateinit var stopButton: Button
    protected lateinit var configSpinner: Spinner
    protected lateinit var statusText: TextView
    protected lateinit var infoText: TextView
    protected lateinit var configTitleText: TextView

    private var isSpinnerInitialized = false
    // "Permanently denied" detection requires at least one prior request: on first denial the
    // rationale is not yet available, so we must not misdirect the user to settings
    private var permissionRequestedOnce = false

    /**
     * Request runtime permissions via the Activity Result API (replaces the deprecated
     * requestPermissions / onRequestPermissionsResult).
     * Toasts are invisible on automotive (AAOS), so feedback uses the status bar text + dialog.
     */
    @SuppressLint("SetTextI18n")
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val ctx = context ?: return@registerForActivityResult
            val denied = result.filterValues { !it }.keys
            if (denied.isEmpty()) {
                statusText.text = "Permission granted"
                return@registerForActivityResult
            }
            val permanent = permissionRequestedOnce &&
                    denied.any { !shouldShowRequestPermissionRationale(it) }
            permissionRequestedOnce = true
            val builder = AlertDialog.Builder(ctx)
                .setTitle(errorDialogTitle)
                .setMessage(
                    if (permanent) "Permission permanently denied. Please grant it manually in system settings." else "Permission is required to continue."
                )
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            if (permanent) {
                builder.setNegativeButton("Go to Settings") { _, _ -> openAppSettings() }
            }
            builder.show()
            statusText.text = "Permission denied"
        }

    protected abstract fun createEngine(context: Context): AudioEngine
    protected abstract val section: String
    protected abstract val messages: AudioMessages
    protected abstract fun requiredPermissions(): Array<String>
    protected abstract fun formatInfo(config: AudioConfig): String
    protected abstract fun friendlyErrorMessage(raw: String): String

    protected open val startButtonText: CharSequence get() = "Start"
    protected open val stopButtonText: CharSequence get() = "Stop"
    protected open val configTitle: CharSequence get() = "Configuration"
    protected open val errorDialogTitle: CharSequence get() = "Audio Error"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_audio_test, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews()
        initViewModel()
        setupClickListeners()
    }

    private fun initViews() {
        startButton = requireView().findViewById(R.id.startButton)
        stopButton = requireView().findViewById(R.id.stopButton)
        configSpinner = requireView().findViewById(R.id.configSpinner)
        statusText = requireView().findViewById(R.id.statusTextView)
        infoText = requireView().findViewById(R.id.infoTextView)
        configTitleText = requireView().findViewById(R.id.configTitleTextView)
        startButton.text = startButtonText
        stopButton.text = stopButtonText
        configTitleText.text = configTitle
    }

    /**
     * The engine is created with the applicationContext (the ViewModel survives configuration
     * changes; this avoids leaking a destroyed Activity).
     */
    private fun initViewModel() {
        val app = requireActivity().application
        viewModel = ViewModelProvider(
            this, AudioViewModel.Factory(app, { ctx -> createEngine(ctx) }, section, messages)
        )[AudioViewModel::class.java]

        // On view recreation (rotation), clear unconsumed errors so LiveData does not replay an
        // old error and pop the dialog again
        viewModel.clearError()

        viewModel.state.observe(viewLifecycleOwner) { updateButtonStates(it) }
        viewModel.statusMessage.observe(viewLifecycleOwner) { statusText.text = it }
        // Clear on consume: prevents LiveData from replaying the last error value on
        // configuration changes and popping the dialog again
        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                handleError(it)
                viewModel.clearError()
            }
        }
        viewModel.currentConfig.observe(viewLifecycleOwner) { config ->
            config?.let {
                updateInfo()
                updateSpinnerSelection(it.description)
                if (configSpinner.adapter == null) setupConfigSpinner()
            }
        }
        viewModel.availableConfigs.observe(viewLifecycleOwner) {
            if (configSpinner.adapter != null) {
                // After a reload the adapter already exists; rebuild it to reflect the new config list
                isSpinnerInitialized = false
                setupConfigSpinner()
            }
        }
    }

    private fun setupClickListeners() {
        startButton.setOnClickListener {
            if (!hasPermission()) {
                requestPermission()
                return@setOnClickListener
            }
            // Disable Start during the startup window to prevent double clicks
            // (double start / "Already playing" → ERROR trap);
            // button states are restored by the state observer once the engine commits
            startButton.isEnabled = false
            stopButton.isEnabled = true
            viewModel.start()
        }
        stopButton.setOnClickListener { viewModel.stop() }
    }

    private fun setupConfigSpinner() {
        val configs = viewModel.getAllAudioConfigs()
        if (configs.isEmpty()) return

        val adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, configs.map { it.description }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        configSpinner.adapter = adapter

        viewModel.currentConfig.value?.let { current ->
            val index = configs.indexOfFirst { it.description == current.description }
            if (index >= 0) configSpinner.setSelection(index)
        }

        configSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isSpinnerInitialized) {
                    isSpinnerInitialized = true
                    return
                }
                val selected = configs[position]
                viewModel.setAudioConfig(selected)
                Toast.makeText(requireContext(), "Switched to: ${selected.description}", Toast.LENGTH_SHORT).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        configSpinner.setOnLongClickListener {
            reloadConfigurations()
            true
        }
    }

    private fun updateSpinnerSelection(description: String) {
        val configs = viewModel.getAllAudioConfigs()
        val index = configs.indexOfFirst { it.description == description }
        if (index >= 0 && index != configSpinner.selectedItemPosition) {
            isSpinnerInitialized = false
            configSpinner.setSelection(index)
        }
    }

    private fun updateButtonStates(state: AudioState) {
        when (state) {
            AudioState.IDLE -> {
                startButton.isEnabled = true
                stopButton.isEnabled = false
                configSpinner.isEnabled = true
            }
            AudioState.ACTIVE -> {
                startButton.isEnabled = false
                stopButton.isEnabled = true
                configSpinner.isEnabled = false
            }
            AudioState.ERROR -> {
                startButton.isEnabled = true
                stopButton.isEnabled = false
                configSpinner.isEnabled = true
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun handleError(error: String) {
        val userMessage = friendlyErrorMessage(error)
        AlertDialog.Builder(requireContext())
            .setTitle(errorDialogTitle)
            .setMessage(userMessage)
            // The error was already consumed and cleared by the observer (clearError);
            // closing the dialog only restores the status bar text
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss(); statusText.text = messages.ready }
            .setCancelable(true)
            .setOnCancelListener { statusText.text = messages.ready }
            .show()
        statusText.text = "Error: $userMessage"
        updateButtonStates(AudioState.ERROR)
    }

    private fun reloadConfigurations() {
        // Restore by selected position rather than description: descriptions may be duplicated
        // (custom configs); position naturally matches the UI selection
        viewModel.reloadConfigurations(configSpinner.selectedItemPosition)
    }

    @SuppressLint("SetTextI18n")
    private fun updateInfo() {
        viewModel.currentConfig.value?.let { infoText.text = formatInfo(it) }
            ?: run { infoText.text = "Information" }
    }

    // ===== Permissions (features differ only in requiredPermissions(); logic is shared) =====

    /** Runtime permissions actually needed by the current config (subclasses may trim per config, e.g. playing a built-in source needs no storage permission) */
    protected open fun permissionsForCurrentConfig(): Array<String> = requiredPermissions()

    private fun hasPermission(): Boolean = permissionsForCurrentConfig().all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        permissionLauncher.launch(permissionsForCurrentConfig())
    }

    /** Opens this app's page in system settings */
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData("package:${requireContext().packageName}".toUri())
        startActivity(intent)
    }

    // ===== Mutual exclusion: switching tabs moves the leaving page RESUMED→STARTED, triggering
    // onPause; going to background works the same way =====

    override fun onPause() {
        super.onPause()
        // Switching tabs moves the leaving page RESUMED→STARTED, triggering onPause; stop
        // unconditionally to also cover the startup race (start not yet committed)
        viewModel.stop()
    }
}

/**
 * Feature-specific message texts (ready / preparing / active / stopped / failed).
 * Other status texts (e.g. "Stopping...", "Configuration updated: X", reload results) are the
 * same for both features and unified as common texts.
 */
data class AudioMessages(
    val ready: String,
    val preparing: String,
    val active: String,
    val stopped: String,
    val failed: String,
)

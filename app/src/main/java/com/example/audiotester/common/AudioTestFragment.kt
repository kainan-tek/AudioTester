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
            // minSdk 32 (API 30+ semantics): a denial leaves the rationale showable unless
            // permanently denied (requires two denials), so rationale==false right after a
            // denial is the permanent-deny signal itself — no "requested once" state needed
            val permanent = denied.any { !shouldShowRequestPermissionRationale(it) }
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
    protected abstract fun permissionsForCurrentConfig(): Array<String>
    protected abstract fun formatInfo(config: AudioConfig): String
    protected abstract fun friendlyMessage(type: AudioErrorType): String

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

    @SuppressLint("SetTextI18n")
    private fun initViews() {
        startButton = requireView().findViewById(R.id.startButton)
        stopButton = requireView().findViewById(R.id.stopButton)
        configSpinner = requireView().findViewById(R.id.configSpinner)
        statusText = requireView().findViewById(R.id.statusTextView)
        infoText = requireView().findViewById(R.id.infoTextView)
        configTitleText = requireView().findViewById(R.id.configTitleTextView)
        startButton.text = "Start"
        stopButton.text = "Stop"
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

        viewModel.state.observe(viewLifecycleOwner) { updateButtonStates(it) }
        viewModel.statusMessage.observe(viewLifecycleOwner) { statusText.text = it }
        // Clear on consume: prevents LiveData from replaying the last error on configuration
        // changes and popping the dialog again. Consumed-on-delivery also means a non-null
        // errorMessage at view recreation is always undelivered: fresh observers receive it
        // (no clearError here) — a finalize failure survives view teardown and still reaches
        // the user
        viewModel.errorMessage.observe(viewLifecycleOwner) { type ->
            type?.let {
                handleError(it)
                viewModel.clearError()
            }
        }
        viewModel.currentConfig.observe(viewLifecycleOwner) { config ->
            config?.let {
                updateInfo()
                updateSpinnerSelection(it)
                if (configSpinner.adapter == null) setupConfigSpinner()
            }
        }
        viewModel.availableConfigs.observe(viewLifecycleOwner) {
            if (configSpinner.adapter != null) {
                // After a reload the adapter already exists; rebuild it to reflect the new config list
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
            // Double clicks are fenced by the STARTING layout (both buttons disabled, set
            // synchronously by the state observer when start() enters STARTING)
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
            // Value equality, not description: descriptions may be duplicated in custom configs
            val index = configs.indexOf(current)
            if (index >= 0) configSpinner.setSelection(index)
        }

        configSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = configs[position]
                // Echo of a programmatic selection (setup/reload/observer-driven setSelection)
                // delivers the current config — not a user switch. Value comparison survives
                // every platform delivery quirk (sync / posted / absent) with no ordering
                // assumptions
                if (selected == viewModel.currentConfig.value) return
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

    private fun updateSpinnerSelection(config: AudioConfig) {
        val index = viewModel.getAllAudioConfigs().indexOf(config)
        if (index >= 0 && index != configSpinner.selectedItemPosition) {
            configSpinner.setSelection(index)
        }
    }

    private fun updateButtonStates(state: AudioState) {
        when (state) {
            AudioState.IDLE, AudioState.ERROR -> {
                startButton.isEnabled = true
                stopButton.isEnabled = false
                configSpinner.isEnabled = true
            }
            AudioState.STARTING -> {
                startButton.isEnabled = false
                stopButton.isEnabled = false
                configSpinner.isEnabled = false
            }
            AudioState.ACTIVE -> {
                startButton.isEnabled = false
                stopButton.isEnabled = true
                configSpinner.isEnabled = false
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun handleError(type: AudioErrorType) {
        val userMessage = friendlyMessage(type)
        AlertDialog.Builder(requireContext())
            .setTitle(errorDialogTitle)
            .setMessage(userMessage)
            // The error was already consumed and cleared by the observer (clearError);
            // closing the dialog only restores the status bar text
            .setPositiveButton("OK") { dialog, _ -> dialog.cancel() }
            .setCancelable(true)
            .setOnCancelListener { statusText.text = messages.ready }
            .show()
        statusText.text = "Error: $userMessage"
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

    // ===== Permissions (each feature declares the permissions its current config needs) =====

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

    // ===== Mutual exclusion: switching tabs or going to background triggers onPause =====

    override fun onPause() {
        super.onPause()
        // Stop unconditionally to also cover the startup race (start not yet committed)
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

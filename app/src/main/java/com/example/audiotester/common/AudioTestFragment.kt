package com.example.audiotester.common

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.audiotester.R

/**
 * 抽象基类：承载全部共享 UI 接线（观察者、Spinner、按钮状态、错误对话框、权限、onPause 停止）。
 * 子类仅提供特性差异（引擎/section/文案/权限/信息格式/错误翻译）。
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
     * 引擎以 applicationContext 创建（ViewModel 跨配置变更存活，避免持有已销毁 Activity 泄漏）。
     */
    private fun initViewModel() {
        val app = requireActivity().application
        val engine = createEngine(app)
        viewModel = ViewModelProvider(
            this, AudioViewModel.Factory(app, engine, section, messages)
        )[AudioViewModel::class.java]

        viewModel.state.observe(viewLifecycleOwner) { updateButtonStates(it) }
        viewModel.statusMessage.observe(viewLifecycleOwner) { statusText.text = it }
        viewModel.errorMessage.observe(viewLifecycleOwner) { error -> error?.let { handleError(it) } }
        viewModel.currentConfig.observe(viewLifecycleOwner) { config ->
            config?.let {
                updateInfo()
                updateSpinnerSelection(it.description)
                if (configSpinner.adapter == null) setupConfigSpinner()
            }
        }
        viewModel.availableConfigs.observe(viewLifecycleOwner) {
            if (configSpinner.adapter != null) {
                // 重载后适配器已存在，重建以反映新配置列表
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
            // 启动窗口内禁用 Start 防止二次点击（双启动 / "Already playing"→ERROR 陷阱）；
            // 引擎提交后由 state 观察者恢复按钮状态
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

    private fun handleError(error: String) {
        val userMessage = friendlyErrorMessage(error)
        AlertDialog.Builder(requireContext())
            .setTitle(errorDialogTitle)
            .setMessage(userMessage)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss(); viewModel.clearError() }
            .setCancelable(true)
            .setOnCancelListener { viewModel.clearError() }
            .show()
        statusText.text = "Error: $userMessage"
        updateButtonStates(AudioState.ERROR)
    }

    private fun reloadConfigurations() {
        viewModel.reloadConfigurations()
    }

    private fun updateInfo() {
        viewModel.currentConfig.value?.let { infoText.text = formatInfo(it) }
            ?: run { infoText.text = "Information" }
    }

    // ===== 权限（各特性仅 requiredPermissions() 不同，逻辑共用）=====

    private fun hasPermission(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        requestPermissions(requiredPermissions(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty()) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            val message = if (allGranted) {
                "Permission granted"
            } else {
                "Permission required (${grantResults.count { it != PackageManager.PERMISSION_GRANTED }} denied)"
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 互斥：切 Tab 时离开页 RESUMED→STARTED 触发 onPause，退后台同理 =====

    override fun onPause() {
        super.onPause()
        // 切 Tab 时离开页 RESUMED→STARTED 触发 onPause；无条件 stop 以覆盖启动中（start 未提交）的竞态
        viewModel.stop()
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}

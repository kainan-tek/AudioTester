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
    // 权限"永久拒绝"判定需先申请过至少一次：首次拒绝时 rationale 尚不可展示，不能误导向设置页
    private var permissionRequestedOnce = false

    /**
     * Activity Result API 申请运行时权限（替代已弃用的 requestPermissions / onRequestPermissionsResult）。
     * 车机（AAOS）上 Toast 不可见，故用状态栏文本 + 对话框反馈。
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
                    if (permanent) "权限被永久拒绝，请前往系统设置手动授予后再试。" else "需要权限才能继续。"
                )
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            if (permanent) {
                builder.setNegativeButton("前往设置") { _, _ -> openAppSettings() }
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
     * 引擎以 applicationContext 创建（ViewModel 跨配置变更存活，避免持有已销毁 Activity 泄漏）。
     */
    private fun initViewModel() {
        val app = requireActivity().application
        viewModel = ViewModelProvider(
            this, AudioViewModel.Factory(app, { ctx -> createEngine(ctx) }, section, messages)
        )[AudioViewModel::class.java]

        // 视图重建（旋转）时清掉未被消费的错误，避免 LiveData 重放旧错误再弹框
        viewModel.clearError()

        viewModel.state.observe(viewLifecycleOwner) { updateButtonStates(it) }
        viewModel.statusMessage.observe(viewLifecycleOwner) { statusText.text = it }
        // 消费即清：防止配置变更时 LiveData 重放最后的错误值再次弹框
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

    @SuppressLint("SetTextI18n")
    private fun handleError(error: String) {
        val userMessage = friendlyErrorMessage(error)
        AlertDialog.Builder(requireContext())
            .setTitle(errorDialogTitle)
            .setMessage(userMessage)
            // 错误已被观察者消费清除（clearError），对话框关闭只负责恢复状态栏文案
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss(); statusText.text = messages.ready }
            .setCancelable(true)
            .setOnCancelListener { statusText.text = messages.ready }
            .show()
        statusText.text = "Error: $userMessage"
        updateButtonStates(AudioState.ERROR)
    }

    private fun reloadConfigurations() {
        // 按选中位置恢复而非 description：description 可能重复（自定义配置），位置与 UI 选中态天然一致
        viewModel.reloadConfigurations(configSpinner.selectedItemPosition)
    }

    @SuppressLint("SetTextI18n")
    private fun updateInfo() {
        viewModel.currentConfig.value?.let { infoText.text = formatInfo(it) }
            ?: run { infoText.text = "Information" }
    }

    // ===== 权限（各特性仅 requiredPermissions() 不同，逻辑共用）=====

    /** 当前配置实际需要的运行时权限（子类可按配置裁剪，如播放内置音源无需存储权限） */
    protected open fun permissionsForCurrentConfig(): Array<String> = requiredPermissions()

    private fun hasPermission(): Boolean = permissionsForCurrentConfig().all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        permissionLauncher.launch(permissionsForCurrentConfig())
    }

    /** 打开本应用的系统设置页 */
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData("package:${requireContext().packageName}".toUri())
        startActivity(intent)
    }

    // ===== 互斥：切 Tab 时离开页 RESUMED→STARTED 触发 onPause，退后台同理 =====

    override fun onPause() {
        super.onPause()
        // 切 Tab 时离开页 RESUMED→STARTED 触发 onPause；无条件 stop 以覆盖启动中（start 未提交）的竞态
        viewModel.stop()
    }
}

/**
 * 各特性差异文案（ready / preparing / active / stopped / failed）。
 * 其余状态文案（如 "Stopping..."、"Configuration updated: X"、重载结果等）两特性相同，统一为通用文案。
 */
data class AudioMessages(
    val ready: String,
    val preparing: String,
    val active: String,
    val stopped: String,
    val failed: String,
)

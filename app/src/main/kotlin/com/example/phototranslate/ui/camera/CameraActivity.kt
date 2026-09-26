package com.example.phototranslate.ui.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.phototranslate.R
import com.example.phototranslate.databinding.ActivityCameraBinding
import com.example.phototranslate.domain.OcrResult
import com.example.phototranslate.domain.TextBlock
import com.example.phototranslate.ui.history.HistoryActivity
import com.example.phototranslate.ui.language.LanguagePreferences
import com.example.phototranslate.ui.language.LanguageSelectActivity
import com.example.phototranslate.ui.result.ResultActivity
import com.example.phototranslate.ui.settings.SettingsActivity
import com.example.phototranslate.usecase.DefaultOcrUseCase
import com.example.phototranslate.usecase.DefaultTranslateUseCase
import com.example.phototranslate.usecase.OcrUseCase
import com.example.phototranslate.usecase.TranslateUseCase
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 相机页（启动页，全屏相机）。
 * - 顶部半透明栏：标题 + 语言 / 历史 / 设置入口
 * - 右上角「实时 / 拍照」分段切换（常驻可见）
 * - 实时模式：OCR → 真实 ML Kit 翻译，结果以圆角卡片浮于预览下方
 * - 拍照模式：真实拍照 → OCR → 翻译 → 打开结果页（不关闭相机，便于返回）
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private val cameraExecutor = Executors.newFixedThreadPool(4)
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var preview: Preview? = null

    private val ocrUseCase: OcrUseCase = DefaultOcrUseCase()
    private val translateUseCase: TranslateUseCase = DefaultTranslateUseCase()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraStarted = AtomicBoolean(false)

    // 协程异常兜底：避免实时/拍照链路中的未捕获异常直接闪退进程。
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("CameraActivity", "Uncaught coroutine error", throwable)
    }

    private enum class Mode { LIVE, PHOTO }

    // 相机分析线程与主线程都会访问，需保证可见性。
    @Volatile
    private var currentMode = Mode.LIVE

    private var lastOcrTime = 0L
    private val ocrThrottleInterval = 250L
    private val previousText = AtomicReference("")

    // OCR 并发守卫：实时模式下仅允许同时存在一次识别+翻译，避免帧在相机线程堆积导致卡顿。
    private val ocrRunning = AtomicBoolean(false)

    // 翻译代数令牌：帧间 OCR 抖动会连续触发多次翻译，旧结果可能晚于新结果返回，
    // 直接显示会导致译文来回跳变。仅代数最新的结果允许上屏（latest-wins）。
    private val translationGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val translatingText = AtomicReference("")

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPermissionLauncher()
        setupTopBar()
        setupModeToggle()
        setupCaptureButton()
        setupFocus()

        binding.btnRealtimeMode.isChecked = true
        setupLiveMode()

        // 进入即预热常用翻译模型，避免实时/拍照首次翻译时阻塞等待下载（否则首帧会卡数秒）。
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val src = LanguagePreferences.getSource(this@CameraActivity)
                val tgt = LanguagePreferences.getTarget(this@CameraActivity)
                translateUseCase.prepareModels(src, tgt)
            }
        }

        checkPermissionAndStart()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置返回后，若已授权则启动相机
        if (!cameraStarted.get() && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
    }

    // ===== 权限 =====
    private fun setupPermissionLauncher() {
        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) startCamera() else showPermissionDenied()
        }
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showPermissionDenied() {
        binding.permissionDeniedOverlay.visibility = android.view.View.VISIBLE
        binding.btnRetryPermission.setOnClickListener { openAppSettings() }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    // ===== 顶部栏导航 =====
    private fun setupTopBar() {
        binding.btnLanguage.setOnClickListener {
            startActivity(Intent(this, LanguageSelectActivity::class.java))
        }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // ===== 模式切换 =====
    private fun setupModeToggle() {
        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) when (checkedId) {
                R.id.btnRealtimeMode -> { currentMode = Mode.LIVE; setupLiveMode() }
                R.id.btnCaptureMode -> { currentMode = Mode.PHOTO; setupPhotoMode() }
            }
        }
    }

    private fun setupLiveMode() {
        binding.captureButton.visibility = android.view.View.GONE
        binding.textOverlay.visibility = android.view.View.VISIBLE
        binding.statusBar.visibility = android.view.View.VISIBLE
        updateStatus(getString(R.string.status_live_ready))
    }

    private fun setupPhotoMode() {
        binding.textOverlay.visibility = android.view.View.GONE
        binding.captureButton.visibility = android.view.View.VISIBLE
        binding.statusBar.visibility = android.view.View.GONE
    }

    private fun setupCaptureButton() {
        binding.captureButton.setOnClickListener {
            if (currentMode == Mode.PHOTO) takePhoto()
        }
    }

    // ===== 点击对焦 =====
    private fun setupFocus() {
        // 预览填充屏幕，避免画面被裁切/拉伸导致的"对焦错误"观感。
        binding.cameraPreview.scaleType = PreviewView.ScaleType.FILL_CENTER

        binding.cameraPreview.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val cam = camera ?: return@setOnTouchListener false
                val pv = preview ?: return@setOnTouchListener false
                val factory = SurfaceOrientedMeteringPointFactory(
                    binding.cameraPreview.width.toFloat(),
                    binding.cameraPreview.height.toFloat(),
                    pv
                )
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point)
                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                try {
                    cam.cameraControl.startFocusAndMetering(action)
                } catch (t: Throwable) {
                    Log.w("CameraActivity", "startFocusAndMetering failed", t)
                }
            }
            false
        }
    }

    // ===== 相机启动 =====
    private fun startCamera() {
        if (cameraStarted.getAndSet(true)) return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                this@CameraActivity.preview = preview

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // 实时识别分辨率 960×540：OCR/翻译不需要高清帧，更低分辨率显著缩短
                    // 双识别器耗时，提升实时跟手度（拍照走独立的高分辨率 ImageCapture）。
                    .setTargetResolution(Size(960, 540))
                    .build()
                    .also { it.setAnalyzer(cameraExecutor) { imageProxy -> processFrame(imageProxy) } }

                imageCapture = ImageCapture.Builder()
                    .setTargetResolution(Size(1920, 1080))
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis, imageCapture
                )
                Log.d("CameraActivity", "CameraX started")
            } catch (exc: Exception) {
                Log.e("CameraActivity", "Camera binding failed", exc)
                mainHandler.post {
                    Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ===== 实时识别 =====
    private fun processFrame(imageProxy: ImageProxy) {
        // 拍照模式下实时识别完全停跑：既省电，也避免与拍照 OCR 抢 CPU 拖慢出片速度。
        if (currentMode == Mode.PHOTO) {
            imageProxy.close()
            return
        }
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastOcrTime < ocrThrottleInterval) {
            imageProxy.close()
            return
        }
        // 上一次识别尚未结束则直接丢弃本帧，只处理最新画面，避免堆积导致实时卡顿。
        if (!ocrRunning.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        lastOcrTime = currentTime

        try {
            // 分析器回调本就运行在 cameraExecutor 上，直接同步执行即可；
            // 旧实现再排一层 cameraExecutor.execute 会额外占用线程并延迟 proxy 关闭。
            // analyze 在 finally 中关闭 imageProxy（仓库独占其生命周期），调用方不再关闭
            val ocrResult = ocrUseCase.analyze(imageProxy)
            mainHandler.post {
                try {
                    handleOcrResult(ocrResult)
                } catch (t: Throwable) {
                    Log.e("CameraActivity", "handleOcrResult failed", t)
                }
            }
        } catch (t: Throwable) {
            Log.e("CameraActivity", "OCR frame failed", t)
            try { imageProxy.close() } catch (_: Throwable) { /* no-op */ }
        } finally {
            ocrRunning.set(false)
        }
    }

    private fun handleOcrResult(ocrResult: OcrResult) {
        if (ocrResult.success && ocrResult.textRecognitionResult != null) {
            val fullText = ocrResult.textRecognitionResult.fullText
            if (fullText.isBlank()) {
                showNoText()
                return
            }
            binding.originalText.text = if (fullText.length > 100) {
                fullText.substring(0, 100) + "…"
            } else fullText

            if (currentMode == Mode.LIVE) {
                val source = resolveSource(LanguagePreferences.getSource(this), ocrResult)
                translateLive(fullText, source)
            }
        } else {
            showNoText()
        }
    }

    private fun showNoText() {
        binding.originalText.text = ""
        binding.translatedText.text = getString(R.string.status_no_text)
        updateStatus(getString(R.string.status_no_text))
    }

    /**
     * 解析翻译源语言：用户在语言页显式选择时尊重其选择；选「自动」时采用 OCR 推断的主导语种
     * （由 DefaultOcrRepository 按文本块 recognizedLanguage 加权得出），比 LanguageIdentification 更准。
     * 归一化到主语言码（zh-Hans→zh），供翻译仓库与 UI/历史记录统一使用。
     */
    private fun resolveSource(userSource: String, ocrResult: OcrResult): String {
        if (userSource != "auto") return userSource
        val dominant = ocrResult.textRecognitionResult?.dominantLanguage
        return com.example.phototranslate.domain.LangUtil.normalize(dominant) ?: "auto"
    }

    private fun translateLive(text: String, sourceLang: String) {
        val previous = previousText.get()
        if (text == previous) return
        // 已有相同内容的翻译在进行中，不重复发起（OCR 抖动会反复触发相同文本）。
        if (text == translatingText.get()) return
        previousText.set(text)
        translatingText.set(text)
        updateStatus(getString(R.string.status_translating))
        val generation = translationGeneration.incrementAndGet()

        lifecycleScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
            val target = LanguagePreferences.getTarget(this@CameraActivity)
            // 实时模式限制翻译输入长度：超长文本显著拖慢端侧翻译，实时跟手优先。
            val trimmed = if (text.length > 600) text.substring(0, 600) else text
            val result = translateUseCase.translate(trimmed, sourceLang, target)
            mainHandler.post {
                // 只呈现最新一帧的翻译结果，避免旧结果晚到覆盖新结果导致译文跳动。
                if (generation != translationGeneration.get()) return@post
                if (result.errorMessage != null) {
                    binding.translatedText.text = result.errorMessage
                } else {
                    binding.translatedText.text = result.translatedText
                }
                updateStatus(getString(R.string.status_live_ready))
            }
        }
    }

    // ===== 拍照模式 =====
    private fun takePhoto() {
        val imageCapture = imageCapture ?: run {
            Toast.makeText(this, R.string.processing, Toast.LENGTH_SHORT).show()
            return
        }
        binding.loadingOverlay.visibility = android.view.View.VISIBLE
        imageCapture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                // toBitmap() 在部分设备或图像格式下可能抛异常，必须就地捕获，否则会在相机线程未捕获崩溃。
                val bitmap: Bitmap = try {
                    // ImageCapture 回调拿到的图像未含显示方向旋转，需按 rotationDegrees 校正，
                    // 否则竖屏拍照会得到横置画面，OCR 读歪导致翻译错误率飙升。
                    val rotation = image.imageInfo.rotationDegrees
                    val raw = image.toBitmap()
                    image.close()
                    val rotated = rotateBitmap(raw, rotation)
                    if (rotated != raw) raw.recycle()
                    // 降采样到最长边 1280：翻译无需原图分辨率，可大幅缩短双识别器耗时、降低反应时间。
                    val scaled = downscaleBitmap(rotated, 1280)
                    if (scaled != rotated) rotated.recycle()
                    scaled
                } catch (t: Throwable) {
                    image.close()
                    Log.e("CameraActivity", "Bitmap conversion failed", t)
                    mainHandler.post {
                        binding.loadingOverlay.visibility = android.view.View.GONE
                        Toast.makeText(this@CameraActivity, R.string.translation_failed, Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                lifecycleScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
                    try {
                        val ocr = ocrUseCase.analyze(bitmap)
                        val userSource = LanguagePreferences.getSource(this@CameraActivity)
                        val target = LanguagePreferences.getTarget(this@CameraActivity)
                        if (ocr.success && ocr.textRecognitionResult != null) {
                            val fullText = ocr.textRecognitionResult.fullText
                            val source = resolveSource(userSource, ocr)
                            val translation = translateUseCase.translate(fullText, source, target)
                            mainHandler.post {
                                binding.loadingOverlay.visibility = android.view.View.GONE
                                openResult(fullText, source, target, translation)
                            }
                        } else {
                            mainHandler.post {
                                binding.loadingOverlay.visibility = android.view.View.GONE
                                openResult("", userSource, target, null)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e("CameraActivity", "Photo pipeline failed", t)
                        mainHandler.post {
                            binding.loadingOverlay.visibility = android.view.View.GONE
                            Toast.makeText(this@CameraActivity, R.string.translation_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("CameraActivity", "Photo capture failed", exception)
                mainHandler.post {
                    binding.loadingOverlay.visibility = android.view.View.GONE
                    Toast.makeText(this@CameraActivity, R.string.translation_failed, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun openResult(
        original: String,
        sourceLang: String,
        targetLang: String,
        translation: com.example.phototranslate.domain.TranslationResult?
    ) {
        val intent = Intent(this, ResultActivity::class.java).apply {
            putExtra("original_text", original)
            putExtra("translated_text", translation?.translatedText ?: "")
            putExtra("source_lang", sourceLang)
            putExtra("target_lang", targetLang)
            putExtra("is_error", translation?.errorMessage != null)
        }
        startActivity(intent)
    }

    // ===== UI 辅助 =====
    private fun updateStatus(text: String) {
        mainHandler.post { binding.statusText.text = text }
    }

    /**
     * 按角度旋转 Bitmap（拍照方向校正用）。
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 等比降采样 Bitmap，使最长边不超过 maxDim（翻译仅需文字清晰度，无需原图分辨率）。
     */
    private fun downscaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longer = maxOf(bitmap.width, bitmap.height)
        if (longer <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longer
        val nw = maxOf(1, (bitmap.width * scale).toInt())
        val nh = maxOf(1, (bitmap.height * scale).toInt())
        return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

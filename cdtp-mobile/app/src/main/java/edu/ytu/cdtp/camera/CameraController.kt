package edu.ytu.cdtp.camera

import android.annotation.SuppressLint
import android.content.Context
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor

/**
 * CameraX: Preview + ImageAnalysis (+ VideoCapture). Hepsi 4:3 → aynı görüş alanı,
 * böylece analiz koordinatları önizlemenin üzerine doğrudan çizilebilir.
 * Cihaz üç use-case'i birlikte desteklemezse video olmadan devam eder (şartname: "video VEYA zaman damgalı kayıt").
 */
class CameraController(private val ctx: Context) {
    private var provider: ProcessCameraProvider? = null
    private val bound = ArrayList<UseCase>()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    @Volatile private var released = false
    val videoAvailable: Boolean get() = videoCapture != null

    fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer,
        analyzerExecutor: Executor,
        withVideo: Boolean,
        onResult: (String) -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            if (released || owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return@addListener
            val p = runCatching { future.get() }.getOrElse { onResult("Kamera açılamadı: ${it.message}"); return@addListener }
            provider = p
            try {
                onResult(bindUseCases(p, owner, previewView, analyzer, analyzerExecutor, withVideo))
            } catch (e: Exception) {
                unbindOwn()
                onResult("Kamera bağlanamadı: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun bindUseCases(
        p: ProcessCameraProvider,
        owner: LifecycleOwner,
        previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer,
        analyzerExecutor: Executor,
        withVideo: Boolean,
    ): String {
        val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_90
        val ratio = AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio)
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(rotation)
            .build()
            .also { it.setAnalyzer(analyzerExecutor, analyzer) }

        unbindOwn()
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        var msg: String
        if (withVideo) {
            val recorder = Recorder.Builder()
                .setAspectRatio(AspectRatio.RATIO_4_3)
                .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build()
            val vc = VideoCapture.Builder(recorder).setTargetRotation(rotation).build()
            try {
                p.bindToLifecycle(owner, selector, preview, analysis, vc)
                bound += listOf(preview, analysis, vc)
                videoCapture = vc
                msg = "Kamera hazır (önizleme + analiz + video)"
            } catch (e: Exception) {
                p.unbind(preview, analysis, vc)
                videoCapture = null
                p.bindToLifecycle(owner, selector, preview, analysis)
                bound += listOf(preview, analysis)
                msg = "Bu cihaz video + analizi birlikte desteklemiyor; zaman damgalı kayıt kullanılacak"
            }
        } else {
            p.bindToLifecycle(owner, selector, preview, analysis)
            bound += listOf(preview, analysis)
            videoCapture = null
            msg = "Kamera hazır (önizleme + analiz)"
        }
        return msg
    }

    private fun unbindOwn() {
        val p = provider ?: return
        if (bound.isNotEmpty()) p.unbind(*bound.toTypedArray())
        bound.clear()
    }

    fun unbind() {
        released = true
        stopRecording()
        unbindOwn()
        videoCapture = null
    }

    /** Ana iş parçacığından çağrılmalı. onFinalized(hata veya null). */
    @SuppressLint("MissingPermission")
    fun startRecording(file: File, onFinalized: (String?) -> Unit): Boolean {
        val vc = videoCapture ?: return false
        file.parentFile?.mkdirs()
        return try {
            recording = vc.output
                .prepareRecording(ctx, FileOutputOptions.Builder(file).build())
                .start(ContextCompat.getMainExecutor(ctx)) { ev ->
                    if (ev is VideoRecordEvent.Finalize) {
                        recording = null
                        onFinalized(if (ev.hasError()) "Video hata kodu ${ev.error}" else null)
                    }
                }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stopRecording() {
        recording?.stop()
        recording = null
    }
}

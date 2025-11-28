package com.loyltworks.mylibrary.qrcodescanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.loyltworks.mylibrary.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


object QrCodeScanner {

    private const val TAG = "Scanner"
    const val REQUEST_CODE_PERMISSIONS = 10
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    // Config
    private var printLog = false
    private var scanDelay: Long = 300L
    private var previewResolution = Size(1280, 720)
    private var analyzerResolution = Size(1280, 720)
    private var defaultZoom = 1.6f

    // Runtime objects
    private var previewView: PreviewView? = null
    private var listener: QrCodeScannerListner? = null
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null

    var FRONTCAMERA = 101
    var BACKCAMERA = 102

    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // ML Kit
    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    )

    // Flags
    private var isRunning = false
    private var isPaused = false
    private var isScanning = false

    fun allPermissionsGranted(context: Context) =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun startScanner(
        context: Context,
        preview: PreviewView,
        scannerListener: QrCodeScannerListner
    ): QrCodeScanner {

        stopScanner()

        previewView = preview
        listener = scannerListener
        isPaused = false
        isScanning = false

        if (!allPermissionsGranted(context)) {
            ActivityCompat.requestPermissions(
                context as Activity,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
            return this
        }

        val owner = context as? LifecycleOwner
        if (owner == null) {
            Log.e(TAG, "LifecycleOwner is null — scanner cannot start")
            return this
        }

        startCamera(context, owner)
        return this
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera(context: Context, lifecycleOwner: LifecycleOwner) {
        if (cameraExecutor == null || cameraExecutor?.isShutdown == true) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        val previewUseCase = Preview.Builder()
            .setTargetResolution(previewResolution)
            .build()

        previewUseCase.setSurfaceProvider(previewView?.surfaceProvider)

        val analysisUseCase = ImageAnalysis.Builder()
            .setTargetResolution(analyzerResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysisUseCase.setAnalyzer(cameraExecutor!!) { proxy ->
            processImageProxy(proxy)
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                cameraProvider?.unbindAll()

                val camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    previewUseCase,
                    analysisUseCase
                )

                cameraControl = camera?.cameraControl
                cameraInfo = camera?.cameraInfo

                try { cameraControl?.setZoomRatio(defaultZoom) } catch (_: Exception) {}

                startRefocusLoop()
                isRunning = true
                log("Camera started")

            } catch (e: Exception) {
                loge("Camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun startRefocusLoop() {
        previewView?.postDelayed(object : Runnable {
            override fun run() {
                try {
                    if (cameraControl == null || previewView == null) return

                    val point = previewView!!.meteringPointFactory.createPoint(
                        previewView!!.width / 2f,
                        previewView!!.height / 2f
                    )

                    val action = FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                    )
                        .setAutoCancelDuration(2, TimeUnit.SECONDS)
                        .build()

                    cameraControl!!.startFocusAndMetering(action)

                } catch (_: Exception) {}

                previewView?.postDelayed(this, 2000)
            }
        }, 1000)
    }

    @ExperimentalGetImage
    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy) {

        if (previewView == null || listener == null) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        if (isPaused || isScanning) {
            imageProxy.close()
            return
        }

        isScanning = true

        val inputImage =
            InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        barcodeScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                if (previewView == null || listener == null) return@addOnSuccessListener

                if (barcodes.isNotEmpty()) {
                    barcodes.first().rawValue?.let { qr ->
                        listener?.onSuccess(qr)
                        try { playBeep(previewView!!.context) } catch (_: Exception) {}
                    }
                } else {
                    listener?.onFailed("No QR found")
                }
            }
            .addOnFailureListener { e ->
                listener?.onFailed("Scan failed: ${e.message}")
            }
            .addOnCompleteListener {
                try { imageProxy.close() } catch (_: Exception) {}

                previewView?.postDelayed({
                    isScanning = false
                }, scanDelay)
            }
    }

    private fun playBeep(context: Context) {
        try {
            val mp = MediaPlayer.create(context, R.raw.beeps) ?: return
            mp.start()

            Handler(Looper.getMainLooper()).postDelayed({
                try { mp.stop() } catch (_: Exception) {}
                mp.release()
            }, 600)

        } catch (_: Exception) {}
    }

    fun cameraSelect(camera: Int, context: Context) {
        cameraSelector = if (camera == FRONTCAMERA)
            CameraSelector.DEFAULT_FRONT_CAMERA
        else
            CameraSelector.DEFAULT_BACK_CAMERA

        stopScanner()

        val owner = context as? LifecycleOwner ?: return
        startCamera(context, owner)
    }

    fun stopScanner() {
        isPaused = true
        isScanning = false

        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        try { cameraExecutor?.shutdownNow() } catch (_: Exception) {}

        previewView = null
        listener = null
        cameraProvider = null
        cameraControl = null
        cameraInfo = null

        isRunning = false
    }

    fun pauseScan() { isPaused = true }
    fun resumeScan() { isPaused = false }

    fun toggleTorch() {
        if (cameraInfo!!.torchState.value == TorchState.ON) {
            cameraControl!!.enableTorch(false)
        } else {
            cameraControl!!.enableTorch(true)
        }
    }

    fun setResolution(preview: Size, analyzer: Size): QrCodeScanner {
        previewResolution = preview
        analyzerResolution = analyzer
        return this
    }

    fun scanDelayTime(ms: Long): QrCodeScanner { scanDelay = ms; return this }
    fun logPrint(enable: Boolean): QrCodeScanner { printLog = enable; return this }

    private fun log(msg: String) { if (printLog) Log.d(TAG, msg) }
    private fun loge(msg: String) { if (printLog) Log.e(TAG, msg) }
}

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
import androidx.appcompat.app.AppCompatActivity
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
import com.loyaltyworks.fleetguardhumsafar.utils.qrcodescanner.QrCodeScannerListner
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
        stopScanner() // reset everything before new start
        previewView = preview
        listener = scannerListener

        if (!allPermissionsGranted(context)) {
            ActivityCompat.requestPermissions(
                context as Activity,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
            return this
        }

        startCamera(context, context as AppCompatActivity)
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
            .also {
                it.surfaceProvider = previewView?.surfaceProvider
            }

        val analysisUseCase = ImageAnalysis.Builder()
            .setTargetResolution(analyzerResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor!!, QrCodeScanner::processImageProxy)
            }

        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                cameraProvider?.unbindAll()

                val camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    previewUseCase,
                    analysisUseCase
                )

                cameraControl = camera?.cameraControl
                cameraInfo = camera?.cameraInfo

                try {
                    cameraControl?.setZoomRatio(defaultZoom)
                } catch (_: Exception) {
                }

                startRefocusLoop()
                isRunning = true
                log("Camera started: Preview=$previewResolution, Analyzer=$analyzerResolution")

            } catch (e: Exception) {
                loge("Camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun startRefocusLoop() {
        previewView?.postDelayed(object : Runnable {
            override fun run() {
                if (cameraControl == null || cameraInfo == null || previewView == null) return
                try {
                    val point = previewView!!.meteringPointFactory.createPoint(
                        previewView!!.width / 2f,
                        previewView!!.height / 2f
                    )
                    val action = FocusMeteringAction.Builder(
                        point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                    ).setAutoCancelDuration(2, TimeUnit.SECONDS).build()
                    cameraControl!!.startFocusAndMetering(action)
                } catch (_: Exception) {
                }
                previewView?.postDelayed(this, 2000)
            }
        }, 1000)
    }

    @ExperimentalGetImage
    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || isPaused || isScanning) {
            imageProxy.close()
            return
        }

        val inputImage =
            InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        isScanning = true

        barcodeScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    val barcode = barcodes.first()
                    barcode.rawValue?.let { qr ->
                        listener!!.onSuccess(qr)
                        log("QR Detected: $qr")
                        try {
                            playBeep(previewView!!.context)
                        } catch (e: Exception) {

                        }

                    }
                } else {
                    try {
                        listener!!.onFailed("No QR found")
                    } catch (e: Exception) {

                    }

                }
            }
            .addOnFailureListener { e ->
                listener?.onFailed("Scan failed: ${e.message}")
            }
            .addOnCompleteListener {
                imageProxy.close()
                previewView?.postDelayed({ isScanning = false }, scanDelay)
            }
    }

    fun pauseScan() {
        isPaused = true
        log("Scan paused")
    }

    fun resumeScan() {
        isPaused = false
        log("Scan resumed")
    }

    fun toggleTorch() {
        try {
            val state = cameraInfo?.torchState?.value
            cameraControl?.enableTorch(state != TorchState.ON)
        } catch (_: Exception) {
            loge("Torch error")
        }
    }

    fun setResolution(preview: Size, analyzer: Size): QrCodeScanner {
        previewResolution = preview
        analyzerResolution = analyzer
        return this
    }

    fun scanDelayTime(ms: Long): QrCodeScanner {
        scanDelay = ms
        return this
    }

    fun logPrint(enable: Boolean): QrCodeScanner {
        printLog = enable
        return this
    }

    fun stopScanner() {
        if (!isRunning) return

        try {
            cameraProvider?.unbindAll()
            log("Camera use cases unbound")
        } catch (e: Exception) {
            loge("Failed to unbind camera: ${e.message}")
        }

        try {
            cameraExecutor?.shutdownNow()
            cameraExecutor = null
        } catch (e: Exception) {
            loge("Camera executor shutdown error: ${e.message}")
        }

        previewView = null
        listener = null
        cameraProvider = null
        cameraControl = null
        cameraInfo = null

        isRunning = false
        isPaused = false
        isScanning = false

        log("Scanner stopped")
    }

    private fun playBeep(context: Context) {
        try {
            val mediaPlayer = MediaPlayer.create(context, R.raw.beeps) ?: return

            mediaPlayer.start()

            // Stop and release after 1 second (even if it's still playing)
            Handler(Looper.getMainLooper()).postDelayed({
                if (mediaPlayer.isPlaying) {
                    try {
                        mediaPlayer.stop()
                    } catch (e: IllegalStateException) {
                        loge("Beep stop error: ${e.message}")
                    }
                }
                mediaPlayer.release()
            }, 600) // 1000ms = 1 second

        } catch (e: Exception) {
            loge("Beep error: ${e.message}")
        }
    }


    private fun log(msg: String) {
        if (printLog) Log.d(TAG, msg)
    }

    private fun loge(msg: String) {
        if (printLog) Log.e(TAG, msg)
    }
}

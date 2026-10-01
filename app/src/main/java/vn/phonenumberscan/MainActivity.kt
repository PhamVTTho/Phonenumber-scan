package vn.phonenumberscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import vn.phonenumberscan.databinding.ActivityMainBinding
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraStarting = false
    private var processing = false

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            binding.permissionPanel.visibility = View.GONE
            startCamera()
        } else {
            showPermissionPanel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.captureButton.setOnClickListener { captureAndRead() }
        binding.scanAgainButton.setOnClickListener { resetResult() }
        binding.grantPermissionButton.setOnClickListener { requestCameraAccess() }
        binding.previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                focusAndMeter(event.x, event.y)
                binding.focusHint.visibility = View.VISIBLE
                binding.focusHint.postDelayed({ binding.focusHint.visibility = View.GONE }, 900L)
            }
            true
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasCameraPermission()) startCamera() else showPermissionPanel()
    }

    override fun onStop() {
        // Lifecycle binding already suspends CameraX; explicitly unbind to release it promptly.
        processing = false
        cameraProvider?.unbindAll()
        imageCapture = null
        camera = null
        cameraStarting = false
        binding.progressIndicator.visibility = View.GONE
        binding.captureButton.isEnabled = false
        binding.captureButton.setText(R.string.capture)
        super.onStop()
    }

    override fun onDestroy() {
        recognizer.close()
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraAccess() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.camera_permission_title)
                .setMessage(R.string.camera_permission_explanation)
                .setPositiveButton(R.string.continue_label) { _, _ ->
                    requestCameraPermission.launch(Manifest.permission.CAMERA)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else if (!hasCameraPermission() &&
            getPreferences(MODE_PRIVATE).getBoolean(KEY_PERMISSION_REQUESTED, false)
        ) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).also { intent ->
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
        } else {
            getPreferences(MODE_PRIVATE).edit()
                .putBoolean(KEY_PERMISSION_REQUESTED, true)
                .apply()
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showPermissionPanel() {
        binding.permissionPanel.visibility = View.VISIBLE
        binding.captureButton.isEnabled = false
    }

    private fun startCamera() {
        if (cameraStarting || imageCapture != null || isFinishing) return
        cameraStarting = true
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraStarting = false
            val provider = runCatching { providerFuture.get() }.getOrElse {
                showError(getString(R.string.camera_start_error))
                return@addListener
            }
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                return@addListener
            }

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(binding.previewView.display.rotation)
                .build()

            runCatching<Camera> {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
            }.onSuccess {
                cameraProvider = provider
                camera = it
                imageCapture = capture
                reduceExposureForScreen(it)
                binding.permissionPanel.visibility = View.GONE
                binding.captureButton.isEnabled = true
            }.onFailure {
                showError(getString(R.string.camera_start_error))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndRead() {
        val capture = imageCapture ?: return
        if (processing) return
        processing = true
        setBusy(true)
        capture.targetRotation = binding.previewView.display.rotation

        // Give AF/AE a short moment to settle on the number displayed by another screen.
        focusAndMeter(binding.previewView.width / 2f, binding.previewView.height / 2f)
        binding.previewView.postDelayed({
            if (processing && imageCapture === capture) takePicture(capture)
        }, FOCUS_SETTLE_DELAY_MS)
    }

    private fun takePicture(capture: ImageCapture) {
        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    readText(image)
                }

                override fun onError(exception: ImageCaptureException) {
                    processing = false
                    setBusy(false)
                    showError(getString(R.string.capture_error))
                }
            }
        )
    }

    private fun focusAndMeter(x: Float, y: Float) {
        val activeCamera = camera ?: return
        if (binding.previewView.width == 0 || binding.previewView.height == 0) return
        val point = binding.previewView.meteringPointFactory.createPoint(x, y, 0.25f)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or
                FocusMeteringAction.FLAG_AE or
                FocusMeteringAction.FLAG_AWB
        ).build()
        if (activeCamera.cameraInfo.isFocusMeteringSupported(action)) {
            activeCamera.cameraControl.startFocusAndMetering(action)
        }
    }

    private fun reduceExposureForScreen(activeCamera: Camera) {
        val state = activeCamera.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val step = state.exposureCompensationStep.toFloat()
        if (step <= 0f) return
        val range = state.exposureCompensationRange
        val index = (TARGET_SCREEN_EV / step).roundToInt().coerceIn(range.lower, range.upper)
        activeCamera.cameraControl.setExposureCompensationIndex(index)
    }

    private fun readText(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            processing = false
            setBusy(false)
            showError(getString(R.string.capture_error))
            return
        }

        val input = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )
        val uprightHeight = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) {
            imageProxy.height
        } else {
            imageProxy.width
        }
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val scanBandText = textInsideScanBand(result, uprightHeight)
                showNumbers(PhoneNumberExtractor.extract(scanBandText))
            }
            .addOnFailureListener { showError(getString(R.string.ocr_error)) }
            .addOnCompleteListener {
                imageProxy.close()
                processing = false
                setBusy(false)
            }
    }

    private fun textInsideScanBand(result: Text, imageHeight: Int): String {
        val bandTop = imageHeight * SCAN_BAND_TOP
        val bandBottom = imageHeight * SCAN_BAND_BOTTOM
        return result.textBlocks
            .flatMap { it.lines }
            .filter { line ->
                val box = line.boundingBox ?: return@filter false
                box.centerY().toFloat() in bandTop..bandBottom
            }
            .joinToString(separator = "\n") { it.text }
    }

    private fun showNumbers(numbers: List<String>) {
        binding.numberContainer.removeAllViews()
        binding.resultTitle.setText(
            if (numbers.isEmpty()) R.string.no_number_found else R.string.number_found
        )
        numbers.forEach { number ->
            val button = layoutInflater.inflate(
                R.layout.item_phone_number,
                binding.numberContainer,
                false
            ) as Button
            button.text = number
            button.contentDescription = getString(R.string.open_dialer_for, number)
            button.setOnClickListener { openDialer(number) }
            binding.numberContainer.addView(button)
        }
        binding.resultPanel.visibility = View.VISIBLE
    }

    private fun openDialer(number: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            showError(getString(R.string.no_dialer))
        }
    }

    private fun resetResult() {
        binding.resultPanel.visibility = View.GONE
        binding.numberContainer.removeAllViews()
    }

    private fun setBusy(busy: Boolean) {
        binding.progressIndicator.visibility = if (busy) View.VISIBLE else View.GONE
        binding.captureButton.isEnabled = !busy && imageCapture != null
        binding.captureButton.text = getString(
            if (busy) R.string.processing else R.string.capture
        )
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val KEY_PERMISSION_REQUESTED = "camera_permission_requested"
        const val FOCUS_SETTLE_DELAY_MS = 550L
        const val TARGET_SCREEN_EV = -0.7f
        const val SCAN_BAND_TOP = 0.30f
        const val SCAN_BAND_BOTTOM = 0.64f
    }
}

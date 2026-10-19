package com.thelightphone.sdk.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Barcode format constants for [LightCodeScanner]'s `formats` parameter.
 * Mirrors ML Kit's `Barcode.FORMAT_*` values (stable public constants, inlined
 * at compile time) so callers can name formats without the ML Kit artifact on
 * their compile classpath.
 */
object LightBarcodeFormats {
    const val CODE_128 = Barcode.FORMAT_CODE_128
    const val CODE_39 = Barcode.FORMAT_CODE_39
    const val CODE_93 = Barcode.FORMAT_CODE_93
    const val CODABAR = Barcode.FORMAT_CODABAR
    const val DATA_MATRIX = Barcode.FORMAT_DATA_MATRIX
    const val EAN_13 = Barcode.FORMAT_EAN_13
    const val EAN_8 = Barcode.FORMAT_EAN_8
    const val ITF = Barcode.FORMAT_ITF
    const val QR = Barcode.FORMAT_QR_CODE
    const val UPC_A = Barcode.FORMAT_UPC_A
    const val UPC_E = Barcode.FORMAT_UPC_E
    const val PDF417 = Barcode.FORMAT_PDF417
    const val AZTEC = Barcode.FORMAT_AZTEC

    /** QR only — the default for the QR-specific client wrapper. */
    val QR_ONLY: List<Int> = listOf(QR)

    /** The symbologies a code-wallet/ticketing flow scans. */
    val COMMON: List<Int> = listOf(
        QR, AZTEC, PDF417, DATA_MATRIX, CODE_128, CODE_39, CODE_93,
        CODABAR, EAN_13, EAN_8, UPC_A, UPC_E, ITF,
    )
}

/**
 * A barcode decoded by the scanner: the decoded text value, the format as an
 * ML Kit `Barcode.FORMAT_*` constant (compare against [LightBarcodeFormats]),
 * and the raw payload bytes (present when the payload is binary rather than
 * text, e.g. an Aztec ticketing code).
 */
data class LightScannedBarcode(
    val value: String,
    val format: Int,
    val rawBytes: ByteArray? = null,
) {
    // ByteArray in a data class: compare by content.
    override fun equals(other: Any?): Boolean =
        other is LightScannedBarcode &&
            value == other.value &&
            format == other.format &&
            rawBytes.contentEquals(other.rawBytes)

    override fun hashCode(): Int =
        31 * (31 * value.hashCode() + format) + rawBytes.contentHashCode()
}

/**
 * Full-screen code scanner: live camera preview, dimmed overlay with viewfinder,
 * top-bar back, and [onScannedBarcode] with the decoded result (format +
 * raw payload bytes). The client library's `LightQrCodeScanner` is a
 * QR-specific wrapper around this that handles the camera permission RPC.
 *
 * The decoder scans the symbologies listed in [formats] (e.g. [LightBarcodeFormats].
 * constants).
 *
 * Host apps must declare [Manifest.permission.CAMERA].
 *
 * When handling [onScannedBarcode], defer navigation to a [LaunchedEffect] (or similar)
 * and pop the scanner before pushing the next screen, e.g. `goBack()` then `navigateTo(...)`.
 */
@Composable
fun LightCodeScanner(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String,
    checkCameraPermission: suspend () -> Result<Boolean>,
    launchCameraPermissionRequest: suspend () -> Unit,
    formats: List<Int>,
    onScannedBarcode: (LightScannedBarcode) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = LightThemeTokens.colors
    var launchedPermissionRequest by remember { mutableStateOf(false) }
    var uiState by remember { mutableStateOf(LightQrUiState.Loading) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val permissionCheck = checkCameraPermission()
            if (permissionCheck.isFailure) {
                uiState = LightQrUiState.PermissionError
            } else if (permissionCheck.getOrNull() == false) {
                if (!launchedPermissionRequest) {
                    launchCameraPermissionRequest()
                    launchedPermissionRequest = true
                } else {
                    uiState = LightQrUiState.PermissionDenied
                }
            } else {
                uiState = LightQrUiState.Active
            }
        }
    }

    val scannedOnce = remember { AtomicBoolean(false) }
    val onScannedBarcodeState = rememberUpdatedState(onScannedBarcode)
    val onBackState = rememberUpdatedState(onBack)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        if (uiState == LightQrUiState.Active) {
            QrCameraPreview(
                formats = formats,
                onBarcode = { scanned ->
                    if (scannedOnce.compareAndSet(false, true)) {
                        onScannedBarcodeState.value(scanned)
                    }
                },
                lifecycleOwner = lifecycleOwner,
                modifier = Modifier.fillMaxSize(),
            )
            QrViewfinderOverlay(
                frameColor = colors.content,
                scrimColor = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { onBackState.value() },
                    ),
                    center = LightTopBarCenter.Text(title),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )
            }

            if (uiState != LightQrUiState.Active) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (uiState == LightQrUiState.Loading) {
                        CircularProgressIndicator()
                    } else {
                        val message = if (uiState == LightQrUiState.PermissionDenied) {
                            "Camera permission is required to scan QR codes."
                        } else {
                            "Error: unable to request camera permission!"
                        }
                        LightText(
                            text = message,
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QrCameraPreview(
    onBarcode: (LightScannedBarcode) -> Unit,
    formats: List<Int>,
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val onBarcodeState = rememberUpdatedState(onBarcode)

    val barcodeScanner = remember(formats) {
        val first = formats.firstOrNull() ?: Barcode.FORMAT_QR_CODE
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(first, *formats.drop(1).toIntArray())
                .build(),
        )
    }

    val cameraController = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        }
    }

    DisposableEffect(lifecycleOwner, cameraController, barcodeScanner) {
        val analyzer = MlKitAnalyzer(
            listOf(barcodeScanner),
            CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
            mainExecutor,
        ) { result ->
            if (result.getThrowable(barcodeScanner) != null) {
                return@MlKitAnalyzer
            }

            val barcodes = result.getValue(barcodeScanner) ?: return@MlKitAnalyzer
            if (barcodes.isEmpty()) {
                return@MlKitAnalyzer
            }

            val scanned = barcodes.firstOrNull { !(it.rawValue ?: it.displayValue).isNullOrBlank() }

            if (scanned != null) {
                onBarcodeState.value(
                    LightScannedBarcode(
                        value = scanned.rawValue ?: scanned.displayValue!!,
                        format = scanned.format,
                        rawBytes = scanned.rawBytes,
                    ),
                )
            }
        }
        cameraController.setImageAnalysisAnalyzer(mainExecutor, analyzer)

        onDispose {
            cameraController.clearImageAnalysisAnalyzer()
            cameraController.unbind()
            barcodeScanner.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                controller = cameraController
                addOnAttachStateChangeListener(
                    object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) {
                            bindCamera(cameraController, lifecycleOwner)
                        }

                        override fun onViewDetachedFromWindow(view: View) {
                            cameraController.unbind()
                        }
                    },
                )
            }
        },
        update = { previewView ->
            if (previewView.isAttachedToWindow) {
                previewView.post {
                    bindCamera(cameraController, lifecycleOwner)
                }
            }
        },
    )
}

private fun bindCamera(
    cameraController: LifecycleCameraController,
    lifecycleOwner: LifecycleOwner,
) {
    runCatching {
        cameraController.bindToLifecycle(lifecycleOwner)
    }
}

@Composable
private fun QrViewfinderOverlay(
    frameColor: Color,
    scrimColor: Color,
    modifier: Modifier = Modifier,
    frameSizeFraction: Float = 0.62f,
) {
    Canvas(modifier = modifier) {
        val frameSize = size.minDimension * frameSizeFraction
        val left = (size.width - frameSize) / 2f
        val top = (size.height - frameSize) / 2f
        val right = left + frameSize
        val bottom = top + frameSize

        drawRect(scrimColor, topLeft = Offset.Zero, size = Size(size.width, top))
        drawRect(scrimColor, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
        drawRect(scrimColor, topLeft = Offset(0f, top), size = Size(left, frameSize))
        drawRect(scrimColor, topLeft = Offset(right, top), size = Size(size.width - right, frameSize))

        drawRoundRect(
            color = frameColor,
            topLeft = Offset(left, top),
            size = Size(frameSize, frameSize),
            cornerRadius = CornerRadius(8f, 8f),
            style = Stroke(width = 3f),
        )
    }
}

private enum class LightQrUiState {
    Loading, PermissionError, PermissionDenied, Active
}

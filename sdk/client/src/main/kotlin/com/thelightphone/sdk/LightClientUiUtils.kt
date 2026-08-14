package com.thelightphone.sdk

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.thelightphone.sdk.nfc.DefaultLightNfc
import com.thelightphone.sdk.nfc.LightNfc
import com.thelightphone.sdk.nfc.LightNfcAvailability
import com.thelightphone.sdk.nfc.LightNfcReadException
import com.thelightphone.sdk.nfc.LightNfcTap
import com.thelightphone.sdk.nfc.LightNfcUnavailableException
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import com.thelightphone.sdk.ui.LightNfcTapReader
import com.thelightphone.sdk.ui.LightNfcTapState
import com.thelightphone.sdk.ui.LightQrCodeScanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlin.time.Duration.Companion.milliseconds

/**
 * Wrapper for the UI library's LightQrCodeScanner that include the client library's functions for
 * checking and requesting the camera permission for the SDK server
 */
@Composable
fun LightQrCodeScanner(
    onScanned: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Scan QR Code",
) {
    val permissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.CAMERA)
    LightQrCodeScanner(
        title = title,
        onScanned = onScanned,
        onBack = onBack,
        modifier = modifier,
        checkCameraPermission = {
            checkPermission(Manifest.permission.CAMERA).asKotlinResult
                .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
        },
        launchCameraPermissionRequest = {
            permissionLauncher?.launch()
        }
    )
}

@Composable
fun rememberLightNfc(): LightNfc? {
    val activity = LocalActivity.current as? LightActivity
    return remember(activity) { activity?.let { DefaultLightNfc(SealedLightActivity(it)) } }
}

@Composable
fun LightNfcTapReader(
    onTap: (LightNfcTap) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Tap",
    prompt: String = "Hold your phone near the other device.",
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val nfc = rememberLightNfc()
    val onTapState = rememberUpdatedState(onTap)
    var availability by remember { mutableStateOf<LightNfcAvailability?>(null) }
    var readFailureMessage by remember { mutableStateOf<String?>(null) }
    var readerRestarts by remember { mutableIntStateOf(0) }

    LaunchedEffect(lifecycleOwner, nfc) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            availability = nfc?.availability ?: LightNfcAvailability.Unsupported
        }
    }

    LaunchedEffect(nfc, availability, readerRestarts) {
        readFailureMessage = null
        if (nfc == null || availability?.isReady != true) return@LaunchedEffect
        nfc.newReader().asFlow()
            .retryWhen { cause, _ ->
                if (cause !is LightNfcReadException) return@retryWhen false
                readFailureMessage = cause.message
                delay(READER_RESTART_DELAY)
                true
            }
            .catch { cause ->
                if (cause !is LightNfcUnavailableException) throw cause
                availability = nfc.availability
                delay(READER_RESTART_DELAY)
                readerRestarts++
            }
            .collect {
                readFailureMessage = null
                onTapState.value(it)
            }
    }

    LightNfcTapReader(
        state = availability?.toTapState() ?: LightNfcTapState.Unknown,
        onBack = onBack,
        modifier = modifier,
        title = title,
        prompt = readFailureMessage ?: prompt,
    )
}

internal fun LightNfcAvailability.toTapState(): LightNfcTapState = when (this) {
    LightNfcAvailability.Ready -> LightNfcTapState.Waiting
    LightNfcAvailability.Disabled -> LightNfcTapState.Disabled
    LightNfcAvailability.PermissionMissing -> LightNfcTapState.PermissionMissing
    LightNfcAvailability.Unsupported -> LightNfcTapState.Unavailable
}

private val READER_RESTART_DELAY = 500.milliseconds

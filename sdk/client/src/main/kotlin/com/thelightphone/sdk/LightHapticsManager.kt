package com.thelightphone.sdk

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.error
import com.thelightphone.sdk.shared.getOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "LightHapticsManager"

suspend fun refreshHapticsEnabled(): Boolean? {
    val result = callRemoteServiceMethod(LightServiceMethod.GetUserPreferences, Unit)
    result.error?.let {
        Log.e(TAG, "Error getting user preferences, code:${it.code}, message:${it.extra}")
        return null
    }
    return result.getOrNull()?.hapticsEnabled
}

private var cachedHapticsEnabled = false

@Composable
fun rememberHapticsEnabled(
    initialValue: Boolean = cachedHapticsEnabled
): StateFlow<Boolean> {
    val flow = remember { MutableStateFlow(initialValue) }
    val scope = rememberCoroutineScope()
    val refreshJob = remember { mutableStateOf<Job?>(null) }

    SideEffect {
        refreshJob.value?.cancel()
        refreshJob.value = scope.launch {
            refreshHapticsEnabled()?.let {
                cachedHapticsEnabled = it
                flow.value = it
            }
        }
    }
    return flow
}

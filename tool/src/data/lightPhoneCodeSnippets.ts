import { CodeFile } from '../types';

export const LIGHT_SDK_FILES: CodeFile[] = [
  {
    name: 'HomeScreen.kt',
    path: 'tool/src/main/kotlin/com/thelightphone/gemini/HomeScreen.kt',
    language: 'kotlin',
    description: 'The initial boot screen subclassing LightScreen with Compose UI and LightOS navigation.',
    content: `package com.thelightphone.gemini

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.*

/**
 * Main query screen for the Light Phone Gemini Tool.
 * Follows LightOS minimalist design: high-contrast, distraction-free, zero feeds.
 */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, GeminiToolViewModel>(sealedActivity) {

    override val viewModelClass: Class<GeminiToolViewModel>
        get() = GeminiToolViewModel::class.java

    override fun createViewModel(): GeminiToolViewModel {
        return GeminiToolViewModel(
            dataStore = lightContext.dataStore,
            filesDir = lightContext.filesDir
        )
    }

    @Composable
    override fun Content() {
        var inputPrompt by remember { mutableStateOf("") }
        val state by viewModel.uiState.collectAsState()
        val scrollState = rememberScrollState()

        LightTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                // Header row with Title and Settings link
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LightText(
                        text = "Ask",
                        variant = LightTextVariant.Heading
                    )
                    LightText(
                        text = "Settings",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.lightClickable {
                            navigateTo(::SettingsScreen)
                        }
                    )
                }

                // Clean minimal prompt input
                BasicTextField(
                    value = inputPrompt,
                    onValueChange = { inputPrompt = it },
                    textStyle = LightThemeTokens.typography.copy.copy(
                        color = LightThemeTokens.colors.text
                    ),
                    cursorBrush = SolidColor(LightThemeTokens.colors.text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    decorationBox = { innerTextField ->
                        if (inputPrompt.isEmpty()) {
                            LightText(
                                text = "What would you like to know?",
                                variant = LightTextVariant.Copy,
                                lighten = true
                            )
                        }
                        innerTextField()
                    }
                )

                // Actions: Query and Clear
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    LightText(
                        text = "Query",
                        variant = LightTextVariant.Detail,
                        modifier = Modifier
                            .lightClickable {
                                if (inputPrompt.isNotBlank()) {
                                    viewModel.ask(inputPrompt)
                                }
                            }
                            .padding(end = 24.dp)
                    )

                    LightText(
                        text = "Clear",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.lightClickable {
                            inputPrompt = ""
                            viewModel.reset()
                        }
                    )
                }

                // Response area
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                ) {
                    when (val current = state) {
                        is GeminiUiState.Idle -> {
                            LightText(
                                text = "Brief, plain-text answers. No algorithms or infinite feeds.",
                                variant = LightTextVariant.Detail,
                                lighten = true
                            )
                        }
                        is GeminiUiState.Loading -> {
                            LightText(
                                text = "Thinking...",
                                variant = LightTextVariant.Copy
                            )
                        }
                        is GeminiUiState.Success -> {
                            LightText(
                                text = current.answer,
                                variant = LightTextVariant.Copy
                            )
                        }
                        is GeminiUiState.MissingKey -> {
                            LightText(
                                text = "Gemini API Key is not set in DataStore.\\nTap 'Settings' to add your key.",
                                variant = LightTextVariant.Detail,
                                lighten = true
                            )
                        }
                        is GeminiUiState.Error -> {
                            LightText(
                                text = "Error: \${current.message}",
                                variant = LightTextVariant.Detail,
                                lighten = true
                            )
                        }
                    }
                }
            }
        }
    }
}
`,
  },
  {
    name: 'GeminiToolViewModel.kt',
    path: 'tool/src/main/kotlin/com/thelightphone/gemini/GeminiToolViewModel.kt',
    language: 'kotlin',
    description: 'MVVM controller: fetches key from DataStore and makes asynchronous REST calls to Gemini.',
    content: `package com.thelightphone.gemini

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface GeminiUiState {
    object Idle : GeminiUiState
    object Loading : GeminiUiState
    object MissingKey : GeminiUiState
    data class Success(val answer: String) : GeminiUiState
    data class Error(val message: String) : GeminiUiState
}

class GeminiToolViewModel(
    private val dataStore: DataStore<Preferences>,
    private val filesDir: File
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow<GeminiUiState>(GeminiUiState.Idle)
    val uiState: StateFlow<GeminiUiState> = _uiState

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun ask(prompt: String) {
        viewModelScope.launch {
            _uiState.value = GeminiUiState.Loading

            // 1. Read API key & preferences from DataStore
            val prefs = dataStore.data.first()
            val apiKey = prefs[DataStoreKeys.GEMINI_API_KEY]?.trim().orEmpty()
            val conciseMode = prefs[DataStoreKeys.CONCISE_MODE] ?: true

            if (apiKey.isBlank()) {
                _uiState.value = GeminiUiState.MissingKey
                return@launch
            }

            // 2. Perform network request off the main thread
            val result = withContext(Dispatchers.IO) {
                queryGeminiApi(apiKey, prompt, conciseMode)
            }

            _uiState.value = result
        }
    }

    private fun queryGeminiApi(apiKey: String, prompt: String, concise: Boolean): GeminiUiState {
        return try {
            val systemPrompt = if (concise) {
                "You are the Light Phone Knowledge Companion. The user values intentional, brief information. Respond strictly in 1 to 2 clear, direct sentences. No markdown formatting, no emojis, no bullet points, no greetings."
            } else {
                "You are the Light Phone Knowledge Companion. Respond in plain, calm, objective text. Keep it between 1 to 3 short paragraphs maximum. Do not use markdown headers, bullets, or emojis."
            }

            val payload = JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", prompt)
                    ))
                ))
            }

            val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=$apiKey")
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    return GeminiUiState.Error("HTTP \${response.code}: \$errorBody")
                }

                val responseJson = JSONObject(response.body?.string().orEmpty())
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val parts = candidates.getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                    val text = parts.getJSONObject(0).getString("text")
                    GeminiUiState.Success(text.trim())
                } else {
                    GeminiUiState.Error("No response candidates returned.")
                }
            }
        } catch (e: Exception) {
            GeminiUiState.Error(e.localizedMessage ?: "Network connection failed")
        }
    }

    fun reset() {
        _uiState.value = GeminiUiState.Idle
    }
}
`,
  },
  {
    name: 'SettingsScreen.kt',
    path: 'tool/src/main/kotlin/com/thelightphone/gemini/SettingsScreen.kt',
    language: 'kotlin',
    description: 'On-device screen allowing the user to view, enter, and persist their Gemini API key into DataStore.',
    content: `package com.thelightphone.gemini

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
) : LightViewModel<Unit>() {

    val currentKey = MutableStateFlow("")
    val conciseMode = MutableStateFlow(true)
    val saveStatus = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            val prefs = dataStore.data.first()
            currentKey.value = prefs[DataStoreKeys.GEMINI_API_KEY] ?: ""
            conciseMode.value = prefs[DataStoreKeys.CONCISE_MODE] ?: true
        }
    }

    fun saveKey(newKey: String) {
        viewModelScope.launch {
            dataStore.edit { preferences ->
                preferences[DataStoreKeys.GEMINI_API_KEY] = newKey.trim()
            }
            saveStatus.value = "Saved to DataStore"
        }
    }

    fun toggleConciseMode() {
        viewModelScope.launch {
            val next = !conciseMode.value
            conciseMode.value = next
            dataStore.edit { preferences ->
                preferences[DataStoreKeys.CONCISE_MODE] = next
            }
        }
    }
}

class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass = SettingsViewModel::class.java
    override fun createViewModel() = SettingsViewModel(lightContext.dataStore)

    @Composable
    override fun Content() {
        var keyInput by remember { mutableStateOf("") }
        val savedKey by viewModel.currentKey.collectAsState()
        val isConcise by viewModel.conciseMode.collectAsState()
        val statusMessage by viewModel.saveStatus.collectAsState()

        LaunchedEffect(savedKey) {
            keyInput = savedKey
        }

        LightTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(24.dp)
            ) {
                // Header
                LightText(
                    text = "Tool Settings",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                // API Key Label
                LightText(
                    text = "Gemini API Key",
                    variant = LightTextVariant.Detail,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // API Key Input
                BasicTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    textStyle = LightThemeTokens.typography.copy.copy(
                        color = LightThemeTokens.colors.text
                    ),
                    cursorBrush = SolidColor(LightThemeTokens.colors.text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    decorationBox = { innerTextField ->
                        if (keyInput.isEmpty()) {
                            LightText(
                                text = "Paste AI Studio API key...",
                                variant = LightTextVariant.Copy,
                                lighten = true
                            )
                        }
                        innerTextField()
                    }
                )

                // Save button
                Row(modifier = Modifier.padding(bottom = 28.dp)) {
                    LightText(
                        text = "Save Key",
                        variant = LightTextVariant.Detail,
                        modifier = Modifier
                            .lightClickable {
                                viewModel.saveKey(keyInput)
                            }
                            .padding(end = 24.dp)
                    )

                    statusMessage?.let {
                        LightText(
                            text = it,
                            variant = LightTextVariant.Detail,
                            lighten = true
                        )
                    }
                }

                // Conciseness Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    LightText(
                        text = "Ultra-Concise: \${if (isConcise) "ON" else "OFF"}",
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.lightClickable {
                            viewModel.toggleConciseMode()
                        }
                    )
                }

                // DataStore note
                LightText(
                    text = "Stored privately in on-device Jetpack DataStore (DEFAULT_DATASTORE). Never leaves your phone except for direct calls to Google Generative AI.",
                    variant = LightTextVariant.Detail,
                    lighten = true
                )
            }
        }
    }
}
`,
  },
  {
    name: 'DataStoreKeys.kt',
    path: 'tool/src/main/kotlin/com/thelightphone/gemini/DataStoreKeys.kt',
    language: 'kotlin',
    description: 'Typed keys for Jetpack DataStore Preferences.',
    content: `package com.thelightphone.gemini

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object DataStoreKeys {
    val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
    val CONCISE_MODE = booleanPreferencesKey("concise_mode")
    val SELECTED_MODEL = stringPreferencesKey("selected_model")
}
`,
  },
  {
    name: 'AndroidManifest.xml',
    path: 'tool/src/main/AndroidManifest.xml',
    language: 'xml',
    description: 'Standard Android manifest declaring internet permissions and service bindings.',
    content: `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.thelightphone.gemini">

    <!-- Required for querying the Gemini REST API -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:label="Gemini"
        android:allowBackup="false"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.NoTitleBar.Fullscreen">

        <!-- Tool Entry point activity handled by Light SDK -->
        <activity
            android:name="com.thelightphone.sdk.LightActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
`,
  },
  {
    name: 'build.gradle.kts',
    path: 'tool/build.gradle.kts',
    language: 'kotlin',
    description: 'Gradle configuration for the tool module in the light-sdk repository.',
    content: `plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.thelightphone.gemini"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.thelightphone.gemini"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":sdk:client"))

    // Networking for Gemini REST endpoint
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    
    // Jetpack DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
`,
  },
  {
    name: 'lighttool.toml',
    path: 'tool/lighttool.toml',
    language: 'toml',
    description: 'Light tool manifest specifying metadata and capabilities.',
    content: `[tool]
name = "Gemini"
package = "com.thelightphone.gemini"
description = "A quiet, distraction-free knowledge companion powered by Gemini."
version = "1.0.0"
author = "Light Community"
capabilities = ["network"]
`,
  },
];

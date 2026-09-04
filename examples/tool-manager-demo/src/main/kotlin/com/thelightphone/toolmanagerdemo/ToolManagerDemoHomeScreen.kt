package com.thelightphone.toolmanagerdemo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.LightFileShare
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.FileBrowserSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@InitialScreen
class ToolManagerDemoHomeScreen(sealedActivity: SealedLightActivity) :
    SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        InnerContent(ToolEntryPoint.directories, ::goBack) {
            lightContext.fileShare.list(it)
        }
    }
}

@Composable
private fun InnerContent(
    directories: List<FileBrowserSpec>,
    goBack: () -> Unit,
    fileLister: suspend (String) -> List<LightFileShare.LightFile>
) {
    val themeColors by LightThemeController.colors.collectAsState()
    val lastUpdated by ToolEntryPoint.updateFlow.collectAsState()
    var fileList by remember { mutableStateOf<List<LightFileShare.LightFile>>(emptyList()) }
    var path by remember { mutableStateOf<List<String>>(emptyList()) }
    val resolvedPath by remember { derivedStateOf { path.joinToString("/") } }
    val title by remember {
        derivedStateOf {
            directories.firstOrNull { it.path == path.firstOrNull() }?.label ?: "Files"
        }
    }

    LaunchedEffect(lastUpdated, resolvedPath) {
        fileList = fileLister(resolvedPath).filter { it.isFile }
    }

    fun onBackPressed() {
        if (path.isEmpty()) {
            goBack()
        } else {
            path = path.dropLast(1)
        }
    }

    LightTheme(colors = themeColors) {
        Column {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = ::onBackPressed),
                center = LightTopBarCenter.Text(title),
            )

            if (path.isEmpty()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(1f.gridUnitsAsDp())) {
                    items(directories) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { path += it.path }
                        ) {
                            LightIcon(LightIcons.ARROW_DOWN)
                            LightText(it.label, variant = LightTextVariant.Copy)
                        }
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(1f.gridUnitsAsDp())) {
                    items(fileList) {
                        Column(Modifier.fillMaxWidth()) {
                            LightText(it.name, variant = LightTextVariant.Copy)
                            LightText(it.lastModified.toString(), variant = LightTextVariant.Detail)
                            Spacer(Modifier.height(1f.gridUnitsAsDp()))
                        }
                    }
                }
            }
        }
    }
}

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    val directories = listOf(
        FileBrowserSpec(label = "Main Directory", path = "main"),
        FileBrowserSpec(label = "Other Directory", path = "other")
    )
    internal val updateFlow = MutableStateFlow<Long>(0)
    override fun getToolManagerManifest(): ClientToolManifest {
        return ClientToolManifest(
            title = "Tool Manager Demo",
            roots = directories.map { ClientLeafNode(it) }
        )
    }

    override suspend fun onToolManagerDataUpdate() {
        super.onToolManagerDataUpdate()
        updateFlow.tryEmit(System.currentTimeMillis())
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun ContentPreview() {
    val directories = listOf(
        FileBrowserSpec(label = "Main Directory", path = "main"),
        FileBrowserSpec(label = "Other Directory", path = "other")
    )
    val now = Clock.System.now()
    val fileMap = mapOf(
        "main" to listOf(
            LightFileShare.LightFile("File 1", Clock.System.now(), true),
            LightFileShare.LightFile("File 2", now - 1.days, true),
        ),
        "other" to listOf(
            LightFileShare.LightFile("File 3", now - 25.minutes, true),
            LightFileShare.LightFile("File 4", now - 4.hours, true),
        )
    )
    Box(Modifier.fillMaxSize()) {
        InnerContent(directories, goBack = {}) {
            fileMap[it].orEmpty()
        }
    }
}


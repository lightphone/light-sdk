package com.thelightphone.plugin

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedDependency
import java.io.File

class LightSdkPlugin : Plugin<Project> {

    companion object {
        val SDK_MODULES = setOf("client", "shared", "ui", "server", "emulator")

        val ALLOWED_DEPENDENCIES = setOf(
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlin:kotlin-test",
            "androidx.compose",
            "androidx.activity:activity-compose",
            "androidx.annotation",
            "org.jetbrains.kotlinx:kotlinx-coroutines",
            "androidx.lifecycle",
            "androidx.datastore",
            "com.squareup.okhttp3:okhttp",
            "io.ktor",
            "org.jetbrains.kotlinx:kotlinx-serialization",
            "org.jetbrains.kotlinx:kotlinx-io",
            "org.unifiedpush.android:connector",
            "androidx.core:core-splashscreen",
            "com.thelightphone.lp3keyboard",
            "androidx.room",
            "androidx.work",
            "androidx.startup",
        )

        val ALLOWED_PLUGINS = setOf(
            "com.android.application",
            "com.android.library",
            "org.jetbrains.kotlin.android",
            "org.jetbrains.kotlin.jvm",
            "org.jetbrains.kotlin.plugin.compose",
            "org.jetbrains.kotlin.plugin.serialization",
            "com.google.devtools.ksp",
            "com.thelightphone.light-sdk",
        )

        val INTERNAL_CONFIG_PREFIXES = listOf(
            "_internal-",
            "androidJdk",
            "composeMappingProducer",
            "kotlin-extension",
            "kotlinBuildTools",
            "kotlinCompiler",
            "kotlinKlib",
            "kotlinNative",
            "kotlinInternalAbi",
            "ksp",
            "kspPlugin",
            "lintChecks",
            "lintPublish",
        )

        val BLOCKED_IMPORTS = listOf(
            "android.app.",
            "android.content.Context",
            "android.content.Intent",
            "android.content.ComponentName",
            "android.content.BroadcastReceiver",
            "android.content.ContentProvider",
            "android.content.ServiceConnection",
            "androidx.compose.ui.platform.LocalContext",
            "androidx.compose.ui.platform.LocalView",
            "androidx.compose.ui.platform.LocalLifecycleOwner",
            "androidx.activity.ComponentActivity",
            "androidx.activity.compose.setContent",
            "androidx.appcompat.",
            "java.lang.reflect.",
            "kotlin.reflect.",
        )

        val BLOCKED_CODE_PATTERNS = listOf(
            Regex("""\bLocalContext\s*\.\s*current\b""") to "LocalContext.current is not allowed — use LightScreen APIs instead",
            Regex("""\bLocalView\s*\.\s*current\b""") to "LocalView.current is not allowed — use LightScreen APIs instead",
            Regex("""\bas\s+\w*Activity\b""") to "Casting to Activity is not allowed",
            Regex("""\bas\?\s+\w*Activity\b""") to "Casting to Activity is not allowed",
            Regex("""\bstartActivity\s*\(""") to "startActivity() is not allowed — use LightScreen.navigateTo() instead",
            Regex("""\bstartService\s*\(""") to "startService() is not allowed",
            Regex("""\bbindService\s*\(""") to "bindService() is not allowed",
            Regex("""\bregisterReceiver\s*\(""") to "registerReceiver() is not allowed",
            Regex("""\bgetSystemService\s*\(""") to "getSystemService() is not allowed",
            Regex("""\bcontentResolver\b""") to "contentResolver access is not allowed",
            Regex("""\b\.javaClass\b""") to "Reflection is not allowed",
            Regex("""\b\.java\s*\.\s*\w""") to "Reflection is not allowed",
            Regex("""\bClass\s*\.\s*forName\s*\(""") to "Reflection is not allowed",
            Regex("""\b\.getDeclaredMethod\s*\(""") to "Reflection is not allowed",
            Regex("""\b\.getMethod\s*\(""") to "Reflection is not allowed",
            Regex("""\b\.getDeclaredField\s*\(""") to "Reflection is not allowed",
            Regex("""\b\.getField\s*\(""") to "Reflection is not allowed",
        )
    }

    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.google.devtools.ksp") {
            // SDK modules have nothing to register, and running the processor there
            // emits an empty LightSdkRegistry that ships in the SDK jar and collides
            // with the consumer app's generated one at class-load time.
            if (project.name !in SDK_MODULES) {
                val pluginJar = this@LightSdkPlugin::class.java.protectionDomain.codeSource.location
                project.dependencies.add("ksp", project.files(pluginJar))
            }
        }

        // Hook AGP for tool modules only; SDK modules don't have lighttool.toml.
        if (project.name !in SDK_MODULES) {
            project.pluginManager.withPlugin("com.android.application") {
                applyToolMetadata(project)
            }
        }

        project.afterEvaluate(::validate)
    }

    /**
     * Reads `lighttool.toml`, configures AGP's `defaultConfig` with the
     * declared metadata, and points `sourceSets.main.manifest.srcFile` at a
     * generated manifest. After this runs the dev does not need (and is not
     * allowed) to provide their own AndroidManifest.xml or applicationId.
     *
     * When `-DlightSdk.unsigned=true` is passed (the server-side builder
     * does this), the variant API is used to disable APK signing for every
     * scheme so the artifact comes out unsigned and ready for the signing
     * service to apply the per-tool key. Locally devs run without the flag
     * and the dev keystore signs as normal.
     */
    private fun applyToolMetadata(project: Project) {
        val tomlFile = File(project.projectDir, LightToolMetadata.FILE_NAME)
        val metadata = try {
            LightToolMetadata.parse(tomlFile)
        } catch (e: LightToolMetadataException) {
            throw GradleException("Light SDK: ${e.message}")
        }

        // Generated manifest path. Anchored under build/ so a clean rebuild
        // always regenerates from current metadata.
        val generatedManifestDir = File(project.layout.buildDirectory.asFile.get(), "generated/light-sdk")
        val generatedManifest = File(generatedManifestDir, "AndroidManifest.xml")

        val app = project.extensions.getByType(ApplicationExtension::class.java)
        with(app) {
            namespace = metadata.toolId
            defaultConfig.applicationId = metadata.toolId
            defaultConfig.versionCode = metadata.versionCode
            defaultConfig.versionName = metadata.versionName
            sourceSets.getByName("main").manifest.srcFile(generatedManifest.path)
        }

        // Always (re)write the manifest at configure time so it's present
        // before AGP starts wiring up its tasks. Cheap; the file is tiny.
        generatedManifestDir.mkdirs()
        generatedManifest.writeText(ManifestGenerator.render(metadata))

        if (System.getProperty("lightSdk.unsigned") == "true") {
            val ac = project.extensions.getByType(
                com.android.build.api.variant.ApplicationAndroidComponentsExtension::class.java
            )
            ac.onVariants { variant ->
                project.logger.lifecycle(
                    "Light SDK: disabling APK signing for variant ${variant.name}"
                )
                variant.signingConfig?.apply {
                    enableV1Signing.set(false)
                    enableV2Signing.set(false)
                    enableV3Signing.set(false)
                    enableV4Signing.set(false)
                }
            }
        }
    }

    private fun validate(project: Project) {
        val violations = mutableListOf<String>()

        validateBuildScript(project, violations)
        validateSourceFiles(project, violations)
        validateDeclaredDependencies(project, violations)
        validateResolvedDependencies(project, violations)
        if (project.name !in SDK_MODULES) {
            validateNoUserManifest(project, violations)
        }

        if (violations.isNotEmpty()) {
            throw GradleException(buildString {
                appendLine("Light SDK: build configuration violations detected:")
                appendLine()
                violations.forEach { appendLine(it) }
                appendLine()
                appendLine("Allowed dependencies:")
                ALLOWED_DEPENDENCIES.sorted().forEach { appendLine("  $it") }
            })
        }
    }

    /**
     * Parse the build script file and reject disallowed plugins, buildscript blocks,
     * resolutionStrategy, and other dangerous constructs.
     */
    private fun validateBuildScript(project: Project, violations: MutableList<String>) {
        val buildFile = project.buildFile
        if (!buildFile.exists()) return

        val content = buildFile.readText()
        // Strip single-line and block comments to avoid false positives
        val stripped = content
            .replace(Regex("//.*"), "")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

        // Check for disallowed plugin IDs
        val pluginIdPattern = Regex("""id\s*\(\s*["']([^"']+)["']\s*\)""")

        pluginIdPattern.findAll(stripped).forEach { match ->
            val pluginId = match.groupValues[1]
            if (pluginId !in ALLOWED_PLUGINS) {
                violations.add("  Plugin not allowed: $pluginId")
            }
        }

        // Patterns that are dangerous everywhere — applied to SDK modules
        // and consumer apps alike.
        val universallyDisallowed = mapOf(
            Regex("""\bbuildscript\s*\{""") to "buildscript {} block not allowed",
            Regex("""\bresolutionStrategy\b""") to "resolutionStrategy not allowed",
            Regex("""\bdependencySubstitution\b""") to "dependencySubstitution not allowed",
            Regex("""\bapply\s*\(\s*plugin""") to "apply(plugin = ...) not allowed — use the plugins {} block",
            Regex("""\bapply\s*\(\s*from""") to "apply(from = ...) not allowed — external scripts are not permitted",
            Regex("""\bapply\s*<""") to "apply<...>() not allowed — use the plugins {} block",
        )
        universallyDisallowed.forEach { (pattern, message) ->
            if (pattern.containsMatchIn(stripped)) violations.add("  $message")
        }

        // App-metadata bans apply only to consumer apps; SDK library modules
        // legitimately set namespace/etc. themselves.
        if (project.name !in SDK_MODULES) {
            val consumerOnlyDisallowed = mapOf(
                Regex("""\bapplicationId\s*=""") to "applicationId must be declared in lighttool.toml, not the build script",
                Regex("""\bversionCode\s*=""") to "versionCode must be declared in lighttool.toml, not the build script",
                Regex("""\bversionName\s*=""") to "versionName must be declared in lighttool.toml, not the build script",
                Regex("""\bnamespace\s*=""") to "namespace is derived from tool.id in lighttool.toml and may not be set in the build script",
            )
            consumerOnlyDisallowed.forEach { (pattern, message) ->
                if (pattern.containsMatchIn(stripped)) violations.add("  $message")
            }
        }
    }

    /**
     * The plugin generates the AndroidManifest.xml from lighttool.toml. A
     * hand-written src/main/AndroidManifest.xml would be silently overridden,
     * so reject it loudly instead of letting devs think their edits matter.
     */
    private fun validateNoUserManifest(project: Project, violations: MutableList<String>) {
        val manifest = project.projectDir.resolve("src/main/AndroidManifest.xml")
        if (manifest.isFile) {
            violations.add(
                "  src/main/AndroidManifest.xml is not allowed — declare your tool's" +
                        " metadata and permissions in lighttool.toml"
            )
        }
    }

    /**
     * Scan user source files for blocked imports and code patterns.
     */
    private fun validateSourceFiles(project: Project, violations: MutableList<String>) {
        // Only scan consumer projects, not the SDK itself
        if (project.name in SDK_MODULES) return

        val srcDirs = project.projectDir.resolve("src")
        if (!srcDirs.exists()) return

        srcDirs.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val relativePath = file.relativeTo(project.projectDir).path
                val lines = file.readLines()

                lines.forEachIndexed { index, line ->
                    val lineNum = index + 1

                    // Split on semicolons to handle multiple statements per line
                    val statements = line.split(';')

                    statements.forEach { statement ->
                        val trimmed = statement.trim()

                        // Check imports
                        if (trimmed.startsWith("import ")) {
                            val importPath = trimmed.removePrefix("import ").trim()
                            BLOCKED_IMPORTS.forEach { blocked ->
                                if (importPath.startsWith(blocked)) {
                                    violations.add("  $relativePath:$lineNum: blocked import '$importPath'")
                                }
                            }
                        }

                        // Skip comments
                        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEach

                        // Check code patterns (only non-import lines)
                        if (!trimmed.startsWith("import ")) {
                            BLOCKED_CODE_PATTERNS.forEach { (pattern, message) ->
                                if (pattern.containsMatchIn(statement)) {
                                    violations.add("  $relativePath:$lineNum: $message")
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun isInternalConfig(name: String): Boolean {
        return INTERNAL_CONFIG_PREFIXES.any { name.startsWith(it) }
    }

    private fun isAllowed(group: String, name: String): Boolean {
        val coordinate = "$group:$name"
        return ALLOWED_DEPENDENCIES.any { coordinate.startsWith(it) }
    }

    /**
     * Check all declarable configurations for disallowed dependencies.
     * Catches: direct disallowed deps, file/jar deps, custom configurations.
     */
    private fun validateDeclaredDependencies(project: Project, violations: MutableList<String>) {
        project.configurations
            .filter { it.isCanBeDeclared && !isInternalConfig(it.name) }
            .forEach { config ->
                config.dependencies.forEach { dep ->
                    if (dep is FileCollectionDependency) {
                        violations.add("  ${config.name}: file dependency not allowed (${dep.files.files.joinToString { it.name }})")
                        return@forEach
                    }

                    if (dep is ProjectDependency) return@forEach

                    val group = dep.group ?: return@forEach
                    if (!isAllowed(group, dep.name)) {
                        violations.add("  ${config.name}: ${group}:${dep.name}:${dep.version ?: "?"}")
                    }
                }
            }
    }

    /**
     * Validate resolved dependency graphs to detect substitution attacks.
     * Compares what was declared vs what actually resolved, flagging any
     * unexpected artifacts that aren't transitives of allowed dependencies.
     */
    private fun isProjectDependency(dep: ResolvedDependency, project: Project): Boolean {
        if (dep.moduleGroup == project.rootProject.name) return true
        return project.rootProject.allprojects.any {
            it.group.toString() == dep.moduleGroup && it.name == dep.moduleName
        }
    }

    private fun validateResolvedDependencies(project: Project, violations: MutableList<String>) {
        project.configurations
            .filter { it.isCanBeResolved && !isInternalConfig(it.name) }
            .forEach { config ->
                val resolved = try {
                    config.resolvedConfiguration.firstLevelModuleDependencies
                } catch (_: Exception) {
                    return@forEach
                }

                // Collect coordinates that are transitives of allowed first-level deps.
                // Only trust transitives of allowed module deps — not project deps,
                // since project dep transitives may themselves be substituted.
                val allowedTransitives = mutableSetOf<String>()
                fun collectTransitives(dep: ResolvedDependency) {
                    dep.children.forEach { child ->
                        val coord = "${child.moduleGroup}:${child.moduleName}"
                        if (allowedTransitives.add(coord)) {
                            collectTransitives(child)
                        }
                    }
                }

                resolved.forEach { dep ->
                    if (isProjectDependency(dep, project)) return@forEach
                    if (isAllowed(dep.moduleGroup, dep.moduleName)) {
                        collectTransitives(dep)
                    }
                }

                resolved.forEach { dep ->
                    if (isProjectDependency(dep, project)) return@forEach

                    val resolvedCoord = "${dep.moduleGroup}:${dep.moduleName}"

                    if (resolvedCoord in allowedTransitives) return@forEach
                    if (isAllowed(dep.moduleGroup, dep.moduleName)) return@forEach

                    violations.add("  ${config.name}: $resolvedCoord:${dep.moduleVersion} (unexpected resolved dependency — possible substitution)")
                }
            }
    }
}

package com.thelightphone.plugin

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedDependency

class LightSdkPlugin : Plugin<Project> {

    companion object {
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
            "com.thelightphone.lp3keyboard"
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
            val pluginJar = this@LightSdkPlugin::class.java.protectionDomain.codeSource.location
            project.dependencies.add("ksp", project.files(pluginJar))
        }
        project.afterEvaluate(::validate)
    }

    private fun validate(project: Project) {
        val violations = mutableListOf<String>()

        validateBuildScript(project, violations)
        validateSourceFiles(project, violations)
        validateDeclaredDependencies(project, violations)
        validateResolvedDependencies(project, violations)

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

        // Check for dangerous constructs
        val disallowedPatterns = mapOf(
            Regex("""\bbuildscript\s*\{""") to "buildscript {} block not allowed",
            Regex("""\bresolutionStrategy\b""") to "resolutionStrategy not allowed",
            Regex("""\bdependencySubstitution\b""") to "dependencySubstitution not allowed",
            Regex("""\bapply\s*\(\s*plugin""") to "apply(plugin = ...) not allowed — use the plugins {} block",
            Regex("""\bapply\s*\(\s*from""") to "apply(from = ...) not allowed — external scripts are not permitted",
            Regex("""\bapply\s*<""") to "apply<...>() not allowed — use the plugins {} block",
        )

        disallowedPatterns.forEach { (pattern, message) ->
            if (pattern.containsMatchIn(stripped)) {
                violations.add("  $message")
            }
        }
    }

    /**
     * Scan user source files for blocked imports and code patterns.
     */
    private fun validateSourceFiles(project: Project, violations: MutableList<String>) {
        // Only scan consumer projects, not the SDK itself
        val sdkModules = setOf("client", "shared", "ui", "server", "emulator")
        if (project.name in sdkModules) return

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

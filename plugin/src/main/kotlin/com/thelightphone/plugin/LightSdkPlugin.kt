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
            "io.github.david-allison:anki-android-backend",
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
            "kspPlugin",
            "lintChecks",
            "lintPublish",
        )

        val ALLOWED_KSP_PROCESSORS = setOf(
            "androidx.room:room-compiler",
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
            "androidx.lifecycle.compose.LocalLifecycleOwner",
            "androidx.activity.",
            "androidx.appcompat.",
            "java.lang.reflect.",
            "java.lang.invoke.",
            "kotlin.reflect.",
        )

        val BLOCKED_CODE_PATTERNS = listOf(
            Regex("""\bLocalContext\b""") to "LocalContext is not allowed — use LightScreen APIs instead",
            Regex("""\bLocalView\b""") to "LocalView is not allowed — use LightScreen APIs instead",
            Regex("""\bLocalActivity\b""") to "LocalActivity is not allowed — use LightScreen APIs instead",
            Regex("""\bLocalLifecycleOwner\b""") to "LocalLifecycleOwner is not allowed — use LightScreen APIs instead",
            Regex("""\bas\??\s+(?:\w+\.)*\w*Activity\b""") to "Casting to Activity is not allowed",
            Regex("""\bas\??\s+(?:\w+\.)*(?:Context|ContextWrapper|ContextThemeWrapper|Application|Service|ContentProvider|BroadcastReceiver)\b""") to "Casting to Android framework type is not allowed",
            Regex("""\bstartActivity\s*\(""") to "startActivity() is not allowed — use LightScreen.navigateTo() instead",
            Regex("""\bstartService\s*\(""") to "startService() is not allowed",
            Regex("""\bbindService\s*\(""") to "bindService() is not allowed",
            Regex("""\bregisterReceiver\s*\(""") to "registerReceiver() is not allowed",
            Regex("""\bgetSystemService\s*\(""") to "getSystemService() is not allowed",
            Regex("""\bcontentResolver\b""") to "contentResolver access is not allowed",
            Regex("""\bgetBaseContext\s*\(""") to "getBaseContext() is not allowed",
            Regex("""\battachBaseContext\s*\(""") to "attachBaseContext() is not allowed",
            Regex("""\bcreatePackageContext\s*\(""") to "createPackageContext() is not allowed",
            Regex("""\bcreateConfigurationContext\s*\(""") to "createConfigurationContext() is not allowed",
            Regex("""\bcreateDeviceProtectedStorageContext\s*\(""") to "createDeviceProtectedStorageContext() is not allowed",
            Regex("""\bcreateContextForSplit\s*\(""") to "createContextForSplit() is not allowed",
            Regex("""\bcreateAttributionContext\s*\(""") to "createAttributionContext() is not allowed",
            Regex("""\bcreateWindowContext\s*\(""") to "createWindowContext() is not allowed",
            Regex("""\bcreateDisplayContext\s*\(""") to "createDisplayContext() is not allowed",
            Regex("""\b\.javaClass\b""") to "Reflection is not allowed",
            Regex("""\b\.java\s*\.\s*\w""") to "Reflection is not allowed",
            Regex("""\bClass\s*\.\s*forName\s*\(""") to "Reflection is not allowed",
            Regex("""\b\.getDeclaredMethod\s*\(""") to "Reflection is not allowed",
            Regex("""\b\.getMethod\s*\(""") to "Reflection is not allowed",
            Regex("""\b\.getDeclaredField\s*\(""") to "Reflection is not allowed",
            Regex("""\b\.getField\s*\(""") to "Reflection is not allowed",
            Regex("""\bMethodHandles\b""") to "java.lang.invoke.MethodHandles is not allowed",
        )

        /** Build-script patterns banned everywhere (SDK modules + consumer apps). */
        val UNIVERSAL_BUILD_SCRIPT_PATTERNS = listOf(
            Regex("""\bbuildscript\s*\{""") to "buildscript {} block not allowed",
            Regex("""\bresolutionStrategy\b""") to "resolutionStrategy not allowed",
            Regex("""\bdependencySubstitution\b""") to "dependencySubstitution not allowed",
            Regex("""\bapply\s*\(\s*plugin""") to "apply(plugin = ...) not allowed — use the plugins {} block",
            Regex("""\bapply\s*\(\s*from""") to "apply(from = ...) not allowed — external scripts are not permitted",
            Regex("""\bapply\s*<""") to "apply<...>() not allowed — use the plugins {} block",
            Regex("""\bpluginManager\s*\.\s*apply\b""") to "pluginManager.apply() not allowed — use the plugins {} block",
            Regex("""(?<![.\w])apply\s*\{""") to "apply {} block not allowed — use the plugins {} block",
            Regex("""\bsrcDirs?\s*\(""") to "custom source directories (srcDir/srcDirs) are not allowed",
        )

        /** Build-script patterns banned only on consumer (tool) modules. */
        val CONSUMER_BUILD_SCRIPT_PATTERNS = listOf(
            Regex("""\bapplicationId\s*=""") to "applicationId must be declared in lighttool.toml, not the build script",
            Regex("""\bversionCode\s*=""") to "versionCode must be declared in lighttool.toml, not the build script",
            Regex("""\bversionName\s*=""") to "versionName must be declared in lighttool.toml, not the build script",
            Regex("""\bnamespace\s*=""") to "namespace is derived from tool.id in lighttool.toml and may not be set in the build script",
        )

        private val PLUGIN_ID_PATTERN = Regex("""id\s*\(\s*["']([^"']+)["']\s*\)""")

        /**
         * Takes the script body and returns one violation message per problem. Used by the Gradle
         * Plugin entry point and by tests.
         */
        fun findBuildScriptViolations(content: String, isConsumer: Boolean): List<String> {
            val stripped = content
                .replace(Regex("//.*"), "")
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

            val violations = mutableListOf<String>()

            PLUGIN_ID_PATTERN.findAll(stripped).forEach { match ->
                val pluginId = match.groupValues[1]
                if (pluginId !in ALLOWED_PLUGINS) {
                    violations.add("Plugin not allowed: $pluginId")
                }
            }

            UNIVERSAL_BUILD_SCRIPT_PATTERNS.forEach { (regex, msg) ->
                if (regex.containsMatchIn(stripped)) violations.add(msg)
            }
            if (isConsumer) {
                CONSUMER_BUILD_SCRIPT_PATTERNS.forEach { (regex, msg) ->
                    if (regex.containsMatchIn(stripped)) violations.add(msg)
                }
            }
            return violations
        }

        /**
         * Pure-function form of the per-line source check. Returns one
         * violation message per problem found in the line.
         */
        fun findSourceLineViolations(line: String): List<String> {
            val violations = mutableListOf<String>()
            line.split(';').forEach { statement ->
                val trimmed = statement.trim()
                if (trimmed.startsWith("import ")) {
                    val importPath = trimmed.removePrefix("import ").trim()
                    BLOCKED_IMPORTS.forEach { blocked ->
                        if (importPath.startsWith(blocked)) {
                            violations.add("blocked import '$importPath'")
                        }
                    }
                    return@forEach
                }
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEach
                BLOCKED_CODE_PATTERNS.forEach { (regex, msg) ->
                    if (regex.containsMatchIn(statement)) {
                        violations.add(msg)
                    }
                }
            }
            return violations
        }

        /**
         * Returns one violation message per `.java` file under the given src dir.
         */
        fun findJavaSourceViolations(srcDir: File, projectDir: File): List<String> {
            if (!srcDir.exists()) return emptyList()
            return srcDir.walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .map {
                    "${it.relativeTo(projectDir).path}: Java source files are not allowed " +
                            "— Light SDK tools must be written in Kotlin"
                }
                .toList()
        }
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
            // finalizeDsl runs after the dev's build script body has evaluated
            // but before AGP creates variants from the DSL. Nulling the
            // signing config here means AGP never wires a signing path into
            // any variant — neither the validateSigning* task nor the package
            // task have anything to do with keystores. Doing this in
            // afterEvaluate is too late because AGP has already snapshotted
            // the build types into variants.
            val ac = project.extensions.getByType(
                com.android.build.api.variant.ApplicationAndroidComponentsExtension::class.java
            )
            ac.finalizeDsl { ext ->
                ext.buildTypes.configureEach { bt ->
                    if (bt.signingConfig != null) {
                        project.logger.lifecycle(
                            "Light SDK: clearing signingConfig on buildType ${bt.name}"
                        )
                        bt.signingConfig = null
                    }
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
            validateNoJavaSources(project, violations)
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
        val isConsumer = project.name !in SDK_MODULES
        findBuildScriptViolations(buildFile.readText(), isConsumer).forEach {
            violations.add("  $it")
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

    private fun validateNoJavaSources(project: Project, violations: MutableList<String>) {
        findJavaSourceViolations(project.projectDir.resolve("src"), project.projectDir)
            .forEach { violations.add("  $it") }
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
                file.readLines().forEachIndexed { index, line ->
                    findSourceLineViolations(line).forEach { msg ->
                        violations.add("  $relativePath:${index + 1}: $msg")
                    }
                }
            }
    }

    private fun isInternalConfig(name: String): Boolean {
        return INTERNAL_CONFIG_PREFIXES.any { name.startsWith(it) }
    }

    /**
     * KSP exposes one declarable + many variant configurations (ksp,
     * kspDebug, kspRelease, kspAndroidTest, ...). All of them feed
     * generated Kotlin into the APK and need the processor allowlist.
     * KSP's own internal classpath uses `kspPlugin*` and is excluded by
     * [INTERNAL_CONFIG_PREFIXES].
     */
    private fun isKspConfig(name: String): Boolean = name.startsWith("ksp")

    private fun isAllowed(group: String, name: String): Boolean {
        val coordinate = "$group:$name"
        return ALLOWED_DEPENDENCIES.any { coordinate.startsWith(it) }
    }

    private fun isAllowedKspProcessor(group: String, name: String): Boolean {
        return "$group:$name" in ALLOWED_KSP_PROCESSORS
    }

    /**
     * Returns the on-disk location of this plugin jar (or classes dir during
     * dev/test). Used to allowlist the file dep we self-add to `ksp(...)`.
     */
    private fun ownPluginJar(): File? = try {
        this::class.java.protectionDomain?.codeSource?.location
            ?.let { File(it.toURI()).canonicalFile }
    } catch (_: Throwable) {
        null
    }

    /**
     * Check all declarable configurations for disallowed dependencies.
     * Catches: direct disallowed deps, file/jar deps, custom configurations.
     */
    private fun validateDeclaredDependencies(project: Project, violations: MutableList<String>) {
        val pluginJar = ownPluginJar()

        project.configurations
            .filter { it.isCanBeDeclared && !isInternalConfig(it.name) }
            .forEach { config ->
                val isKsp = isKspConfig(config.name)
                config.dependencies.forEach { dep ->
                    if (dep is FileCollectionDependency) {
                        // We self-add the plugin jar to `ksp` for the registry
                        // processor. Allow exactly that file; reject anything
                        // else, since file deps bypass coordinate validation.
                        if (isKsp && pluginJar != null &&
                            dep.files.files.all { it.canonicalFile == pluginJar }
                        ) {
                            return@forEach
                        }
                        violations.add("  ${config.name}: file dependency not allowed (${dep.files.files.joinToString { it.name }})")
                        return@forEach
                    }

                    if (dep is ProjectDependency) return@forEach

                    val group = dep.group ?: return@forEach
                    if (isKsp) {
                        if (!isAllowedKspProcessor(group, dep.name)) {
                            violations.add("  ${config.name}: ${group}:${dep.name}:${dep.version ?: "?"} (KSP processor not allowed)")
                        }
                    } else {
                        if (!isAllowed(group, dep.name)) {
                            violations.add("  ${config.name}: ${group}:${dep.name}:${dep.version ?: "?"}")
                        }
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

                val isKsp = isKspConfig(config.name)
                val allowPredicate: (String, String) -> Boolean =
                    if (isKsp) ::isAllowedKspProcessor else ::isAllowed

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
                    if (allowPredicate(dep.moduleGroup, dep.moduleName)) {
                        collectTransitives(dep)
                    }
                }

                resolved.forEach { dep ->
                    if (isProjectDependency(dep, project)) return@forEach

                    val resolvedCoord = "${dep.moduleGroup}:${dep.moduleName}"

                    if (resolvedCoord in allowedTransitives) return@forEach
                    if (allowPredicate(dep.moduleGroup, dep.moduleName)) return@forEach

                    val tag = if (isKsp) "unexpected resolved KSP dependency" else "unexpected resolved dependency — possible substitution"
                    violations.add("  ${config.name}: $resolvedCoord:${dep.moduleVersion} ($tag)")
                }
            }
    }
}

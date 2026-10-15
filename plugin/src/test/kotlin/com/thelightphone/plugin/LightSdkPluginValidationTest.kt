package com.thelightphone.plugin

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Disclosure: These tests were mostly written by an LLM!
class LightSdkPluginValidationTest {

    // ---------------------------------------------------------------------
    // Source-line patterns: composition locals (incl. alias bypass)
    // ---------------------------------------------------------------------

    @Test
    fun `LocalContext_current is blocked`() {
        val v = LightSdkPlugin.findSourceLineViolations(
            "val ctx = androidx.compose.ui.platform.LocalContext.current"
        )
        assertTrue(v.any { "LocalContext" in it }, "expected LocalContext violation, got $v")
    }

    @Test
    fun `LocalContext alias bypass is caught by bare-name pattern`() {
        // val L = androidx.compose.ui.platform.LocalContext
        // L.current  ← would slip past a `.current`-only regex
        val v = LightSdkPlugin.findSourceLineViolations(
            "val L = androidx.compose.ui.platform.LocalContext"
        )
        assertTrue(v.any { "LocalContext" in it }, "expected bare-name LocalContext to fire, got $v")
    }

    @Test
    fun `LocalView alias bypass is caught`() {
        val v = LightSdkPlugin.findSourceLineViolations(
            "val V = androidx.compose.ui.platform.LocalView"
        )
        assertTrue(v.any { "LocalView" in it }, "expected bare-name LocalView to fire, got $v")
    }

    @Test
    fun `LocalActivity_current is blocked`() {
        val v = LightSdkPlugin.findSourceLineViolations(
            "val a = androidx.activity.compose.LocalActivity.current"
        )
        assertTrue(v.any { "LocalActivity" in it }, "expected LocalActivity violation, got $v")
    }

    @Test
    fun `LocalLifecycleOwner_current is blocked`() {
        val v = LightSdkPlugin.findSourceLineViolations(
            "val owner = LocalLifecycleOwner.current"
        )
        assertTrue(v.any { "LocalLifecycleOwner" in it }, "got $v")
    }

    @Test
    fun `unrelated identifier containing LocalContext substring is not blocked`() {
        // `myLocalContext` is a normal camelCase var; `\b` keeps it safe.
        val v = LightSdkPlugin.findSourceLineViolations("val myLocalContext = 1")
        assertTrue(v.isEmpty(), "expected no violations, got $v")
    }

    // ---------------------------------------------------------------------
    // Cast bypasses
    // ---------------------------------------------------------------------

    @Test
    fun `cast to ComponentActivity is blocked`() {
        val v = LightSdkPlugin.findSourceLineViolations("val a = x as ComponentActivity")
        assertTrue(v.any { "Activity" in it }, "got $v")
    }

    @Test
    fun `cast to fully-qualified Context is blocked`() {
        val v = LightSdkPlugin.findSourceLineViolations(
            "val c = owner as android.content.Context"
        )
        assertTrue(v.any { "framework type" in it }, "got $v")
    }

    @Test
    fun `nullable cast to Application is blocked`() {
        val v = LightSdkPlugin.findSourceLineViolations("val app = x as? Application")
        assertTrue(v.any { "framework type" in it }, "got $v")
    }

    @Test
    fun `cast to ContextWrapper is blocked`() {
        val v = LightSdkPlugin.findSourceLineViolations("val w = x as ContextWrapper")
        assertTrue(v.any { "framework type" in it }, "got $v")
    }

    @Test
    fun `cast to user-named type ending in Activity-ish suffix is NOT blocked`() {
        // `as MyService` shouldn't fire — Service alternation requires bare
        // or dotted-qualified `Service`, not arbitrary suffix.
        val v = LightSdkPlugin.findSourceLineViolations("val s = x as MyService")
        assertTrue(v.isEmpty(), "expected no violations, got $v")
    }

    // ---------------------------------------------------------------------
    // Context obtainer methods
    // ---------------------------------------------------------------------

    @Test
    fun `getBaseContext is blocked`() {
        val v = LightSdkPlugin.findSourceLineViolations("val c = ctx.getBaseContext()")
        assertTrue(v.any { "getBaseContext" in it }, "got $v")
    }

    @Test
    fun `createPackageContext is blocked`() {
        val v = LightSdkPlugin.findSourceLineViolations(
            "val c = ctx.createPackageContext(\"a\", 0)"
        )
        assertTrue(v.any { "createPackageContext" in it }, "got $v")
    }

    @Test
    fun `createConfigurationContext is blocked`() {
        val v = LightSdkPlugin.findSourceLineViolations(
            "val c = ctx.createConfigurationContext(cfg)"
        )
        assertTrue(v.any { "createConfigurationContext" in it }, "got $v")
    }

    // ---------------------------------------------------------------------
    // Reflection extensions
    // ---------------------------------------------------------------------

    @Test
    fun `MethodHandles is blocked`() {
        val v = LightSdkPlugin.findSourceLineViolations(
            "val l = java.lang.invoke.MethodHandles.lookup()"
        )
        assertTrue(v.any { "MethodHandles" in it }, "got $v")
    }

    @Test
    fun `java_lang_invoke import is blocked`() {
        val v = LightSdkPlugin.findSourceLineViolations(
            "import java.lang.invoke.MethodHandles"
        )
        assertTrue(v.any { "java.lang.invoke" in it }, "got $v")
    }

    // ---------------------------------------------------------------------
    // BLOCKED_IMPORTS coverage
    // ---------------------------------------------------------------------

    @Test
    fun `androidx_lifecycle_compose_LocalLifecycleOwner import is blocked`() {
        val v = LightSdkPlugin.findSourceLineViolations(
            "import androidx.lifecycle.compose.LocalLifecycleOwner"
        )
        assertTrue(
            v.any { "LocalLifecycleOwner" in it },
            "blocking the lifecycle-compose twin closes the import-side hole, got $v"
        )
    }

    @Test
    fun `android_content_Context import is blocked`() {
        val v = LightSdkPlugin.findSourceLineViolations("import android.content.Context")
        assertTrue(v.any { "Context" in it }, "got $v")
    }

    @Test
    fun `kotlinx_serialization import is allowed`() {
        // sanity check: legit imports don't trip the scanner
        val v = LightSdkPlugin.findSourceLineViolations(
            "import kotlinx.serialization.Serializable"
        )
        assertTrue(v.isEmpty(), "expected no violations, got $v")
    }

    // ---------------------------------------------------------------------
    // Build-script: new patterns
    // ---------------------------------------------------------------------

    @Test
    fun `pluginManager_apply is blocked`() {
        val script = """
            pluginManager.apply("com.evil.plugin")
        """.trimIndent()
        val v = LightSdkPlugin.findBuildScriptViolations(script, isConsumer = true)
        assertTrue(v.any { "pluginManager.apply" in it }, "got $v")
    }

    @Test
    fun `apply block form is blocked`() {
        val script = """
            apply {
                plugin("com.evil.plugin")
            }
        """.trimIndent()
        val v = LightSdkPlugin.findBuildScriptViolations(script, isConsumer = true)
        assertTrue(v.any { "apply {} block" in it }, "got $v")
    }

    @Test
    fun `kotlin scope-function apply is NOT blocked`() {
        // `Properties().apply { ... }` is a normal Kotlin idiom used in
        // every other build script and must not trigger the Gradle apply
        // detector.
        val script = """
            val localProps = Properties().apply {
                if (file.exists()) file.inputStream().use { load(it) }
            }
        """.trimIndent()
        val v = LightSdkPlugin.findBuildScriptViolations(script, isConsumer = false)
        assertTrue(v.isEmpty(), "scope-function apply should pass, got $v")
    }

    @Test
    fun `srcDirs is blocked`() {
        val script = """
            android.sourceSets["main"].kotlin.srcDirs("hidden/")
        """.trimIndent()
        val v = LightSdkPlugin.findBuildScriptViolations(script, isConsumer = true)
        assertTrue(v.any { "srcDir" in it }, "got $v")
    }

    @Test
    fun `srcDir singular is blocked`() {
        val script = """
            android.sourceSets["main"].java.srcDir("more/")
        """.trimIndent()
        val v = LightSdkPlugin.findBuildScriptViolations(script, isConsumer = true)
        assertTrue(v.any { "srcDir" in it }, "got $v")
    }

    // ---------------------------------------------------------------------
    // Build-script: legacy patterns still work
    // ---------------------------------------------------------------------

    @Test
    fun `buildscript block is blocked`() {
        val v = LightSdkPlugin.findBuildScriptViolations(
            "buildscript { dependencies { classpath(\"x\") } }",
            isConsumer = true,
        )
        assertTrue(v.any { "buildscript" in it }, "got $v")
    }

    @Test
    fun `disallowed plugin id is reported`() {
        val script = """
            plugins {
                id("com.evil.plugin")
            }
        """.trimIndent()
        val v = LightSdkPlugin.findBuildScriptViolations(script, isConsumer = true)
        assertTrue(v.any { "com.evil.plugin" in it }, "got $v")
    }

    @Test
    fun `allowed plugin id passes`() {
        val script = """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }
        """.trimIndent()
        val v = LightSdkPlugin.findBuildScriptViolations(script, isConsumer = true)
        assertTrue(v.isEmpty(), "expected no violations, got $v")
    }

    @Test
    fun `applicationId in consumer build script is blocked`() {
        val v = LightSdkPlugin.findBuildScriptViolations(
            "defaultConfig { applicationId = \"foo\" }",
            isConsumer = true,
        )
        assertTrue(v.any { "applicationId" in it }, "got $v")
    }

    @Test
    fun `applicationId in SDK build script is allowed`() {
        val v = LightSdkPlugin.findBuildScriptViolations(
            "defaultConfig { applicationId = \"foo\" }",
            isConsumer = false,
        )
        assertTrue(v.isEmpty(), "SDK modules legitimately set applicationId, got $v")
    }

    @Test
    fun `comments are stripped before scanning`() {
        // a literal `applicationId =` inside a comment should not trip the check
        val script = """
            // applicationId = "foo"
            /* applicationId = "bar" */
        """.trimIndent()
        val v = LightSdkPlugin.findBuildScriptViolations(script, isConsumer = true)
        assertTrue(v.isEmpty(), "expected no violations, got $v")
    }

    // ---------------------------------------------------------------------
    // Java source ban
    // ---------------------------------------------------------------------

    @Test
    fun `java files in src are reported`(@TempDir dir: Path) {
        val projectDir = dir.toFile()
        val src = File(projectDir, "src/main/java/com/example").also { it.mkdirs() }
        File(src, "Sneaky.java").writeText("package com.example;\nclass Sneaky {}\n")

        val violations = LightSdkPlugin.findJavaSourceViolations(
            srcDir = File(projectDir, "src"),
            projectDir = projectDir,
        )
        assertEquals(1, violations.size, "got $violations")
        assertTrue(violations.single().contains("Sneaky.java"))
        assertTrue(violations.single().contains("not allowed"))
    }

    @Test
    fun `kotlin files do not trip the java ban`(@TempDir dir: Path) {
        val projectDir = dir.toFile()
        val src = File(projectDir, "src/main/kotlin/com/example").also { it.mkdirs() }
        File(src, "Fine.kt").writeText("package com.example\nclass Fine\n")

        val violations = LightSdkPlugin.findJavaSourceViolations(
            srcDir = File(projectDir, "src"),
            projectDir = projectDir,
        )
        assertTrue(violations.isEmpty(), "got $violations")
    }

    @Test
    fun `missing src dir produces no violations`(@TempDir dir: Path) {
        val projectDir = dir.toFile()
        val violations = LightSdkPlugin.findJavaSourceViolations(
            srcDir = File(projectDir, "src"),
            projectDir = projectDir,
        )
        assertTrue(violations.isEmpty())
    }

    // ---------------------------------------------------------------------
    // KSP allowlist data
    // ---------------------------------------------------------------------

    @Test
    fun `room-compiler is on the KSP allowlist`() {
        assertTrue("androidx.room:room-compiler" in LightSdkPlugin.ALLOWED_KSP_PROCESSORS)
    }

    @Test
    fun `KSP allowlist does not silently include unrelated processors`() {
        // canary — fail loudly if someone adds something dangerous without
        // thinking. Tighten / loosen this set deliberately, not by accident.
        assertEquals(
            setOf("androidx.room:room-compiler"),
            LightSdkPlugin.ALLOWED_KSP_PROCESSORS,
        )
    }

    // ---------------------------------------------------------------------
    // INTERNAL_CONFIG_PREFIXES no longer skips ksp
    // ---------------------------------------------------------------------

    @Test
    fun `ksp config is not treated as internal`() {
        // previously `ksp` was in INTERNAL_CONFIG_PREFIXES, which let any
        // KSP processor in unchecked. Make sure that's gone.
        assertFalse(
            LightSdkPlugin.INTERNAL_CONFIG_PREFIXES.any { "ksp".startsWith(it) },
            "ksp must not be treated as internal — generated source feeds the APK",
        )
    }

    @Test
    fun `kspPlugin internals remain excluded`() {
        // KSP's own classpath wiring uses kspPlugin* — those are still
        // legitimately internal and should not be policed for processors.
        assertTrue(
            LightSdkPlugin.INTERNAL_CONFIG_PREFIXES.any { "kspPluginClasspath".startsWith(it) },
        )
    }

    // ---------------------------------------------------------------------
    // Dependency allowlist and versions
    // ---------------------------------------------------------------------

    @Test
    fun `group entries match the group and its subgroups only`() {
        assertTrue(LightSdkPlugin.isAllowedCoordinate("io.ktor", "ktor-server-core"))
        assertTrue(LightSdkPlugin.isAllowedCoordinate("androidx.compose.ui", "ui"))
        assertFalse(LightSdkPlugin.isAllowedCoordinate("io.ktorx", "ktor-server-core"))
        assertFalse(LightSdkPlugin.isAllowedCoordinate("androidx.roomx", "room-runtime"))
    }

    @Test
    fun `artifact entries match the artifact and dash-suffixed variants only`() {
        assertTrue(LightSdkPlugin.isAllowedCoordinate("org.jetbrains.kotlinx", "kotlinx-coroutines-core"))
        assertTrue(LightSdkPlugin.isAllowedCoordinate("com.squareup.okhttp3", "okhttp"))
        assertFalse(LightSdkPlugin.isAllowedCoordinate("com.squareup.okhttp3", "okhttpx"))
        assertFalse(LightSdkPlugin.isAllowedCoordinate("com.squareup.okhttp3", "logging-interceptor"))
    }

    @Test
    fun `exact and absent versions are accepted`() {
        assertEquals(null, LightSdkPlugin.findVersionViolation("3.4.2"))
        assertEquals(null, LightSdkPlugin.findVersionViolation("0.1.70-anki26.09.3"))
        assertEquals(null, LightSdkPlugin.findVersionViolation(null))
        assertEquals(null, LightSdkPlugin.findVersionViolation(""))
    }

    @Test
    fun `dynamic and snapshot versions are rejected`() {
        listOf("1.+", "+", "[1.0,2.0)", "(,1.0]", "latest.release", "latest.integration", "1.0-SNAPSHOT", "1.0-snapshot")
            .forEach { version ->
                assertTrue(LightSdkPlugin.findVersionViolation(version) != null, "expected '$version' to be rejected")
            }
    }

    @Test
    fun `SDK version resource is filled in at build time`() {
        assertTrue(LightSdkPlugin.SDK_VERSION.isNotBlank())
        assertFalse("$" in LightSdkPlugin.SDK_VERSION, "unexpanded: ${LightSdkPlugin.SDK_VERSION}")
    }

    @Test
    fun `dependency violations are reported once with every configuration`() {
        val violations = DependencyViolations()
        violations.add("releaseCompileClasspath", "com.google.code.gson:gson:2.11.0")
        violations.add("implementation", "com.google.code.gson:gson:2.11.0")
        violations.add("implementation", "com.google.code.gson:gson:2.11.0")
        assertEquals(
            listOf("  com.google.code.gson:gson:2.11.0 (in implementation, releaseCompileClasspath)"),
            violations.lines(),
        )
    }

    @Test
    fun `allowlist is only listed next to dependency violations`() {
        val source = LightSdkPlugin.formatViolations(listOf("  Foo.kt:3: blocked import"), emptyList())!!
        assertFalse("Allowed dependencies:" in source, source)

        val dependency = LightSdkPlugin.formatViolations(emptyList(), listOf("  a:b:1 (in implementation)"))!!
        assertTrue("Allowed dependencies:" in dependency, dependency)

        assertEquals(null, LightSdkPlugin.formatViolations(emptyList(), emptyList()))
    }

    @Test
    fun `test configurations are recognised`() {
        listOf("testImplementation", "debugUnitTestCompileClasspath", "debugAndroidTestRuntimeClasspath", "kspTestKotlin")
            .forEach { assertTrue(LightSdkPlugin.isTestConfig(it), it) }
        listOf("releaseRuntimeClasspath", "releaseCompileClasspath", "kspRelease")
            .forEach { assertFalse(LightSdkPlugin.isTestConfig(it), it) }
    }
}

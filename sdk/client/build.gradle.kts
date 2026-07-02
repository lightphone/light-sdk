import java.util.Properties
import kotlin.apply

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.light.sdk)
    alias(libs.plugins.ksp)
}

group = property("sdkGroup") as String
version = property("sdkVersion") as String

android {
    namespace = "com.thelightphone.sdk"
    compileSdk = rootProject.ext["compileSdk"] as Int

    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val vapidPublicKey = localProps.getProperty("vapidPublicKey", "")

    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        buildConfigField("String", "LIGHT_VAPID_KEY", "\"$vapidPublicKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
        targetCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(rootProject.ext["jvmTarget"] as String))
    }
}

dependencies {
    api(project(":sdk:shared"))
    api(project(":sdk:ui"))

    api(libs.compose.activity)
    api(libs.kotlinx.coroutines)
    api(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.datastore.preferences)
    api(libs.ktor.client.core)
    api(libs.ktor.client.okhttp)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.serialization.json)
    implementation(libs.unifiedpush.connector)
    implementation(libs.androidx.splashscreen)
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime)
    lintChecks(project(":lint-rules"))

    testImplementation(libs.kotlin.test)
}

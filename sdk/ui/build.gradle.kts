plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

group = property("sdkGroup") as String
version = property("sdkVersion") as String

android {
    namespace = "com.thelightphone.sdk.ui"
    compileSdk = rootProject.ext["compileSdk"] as Int

    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        consumerProguardFiles("consumer-rules.pro")
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

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = property("sdkGroup") as String
                artifactId = "sdk-ui"
                version = property("sdkVersion") as String
            }
        }
    }
}

dependencies {
    api(libs.light.keyboard)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.compose.activity)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.mlkit.barcode.scanning)
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.runtime)
    debugApi(libs.compose.ui.tooling)
    api(libs.compose.ui.tooling.preview)
    testImplementation(libs.kotlin.test)
}

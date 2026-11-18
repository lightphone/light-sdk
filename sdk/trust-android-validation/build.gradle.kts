plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.lighttrustvalidation"
    compileSdk = rootProject.ext["compileSdk"] as Int
    defaultConfig {
        applicationId = "com.example.lighttrustvalidation"
        minSdk = rootProject.ext["minSdk"] as Int
        targetSdk = rootProject.ext["targetSdk"] as Int
        versionCode = 1
        versionName = "1"
        testInstrumentationRunner = "com.example.lighttrustvalidation.VectorInstrumentation"
    }
    testBuildType = "release"
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/trust-fixtures"))
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

val copyTrustFixtures by tasks.registering(Sync::class) {
    from(rootProject.file("signer/tests/vectors/bundle")) {
        include("*/bundle.json", "*/bundle.sig", "INSECURE-bundle-public.pem")
    }
    into(layout.buildDirectory.dir("generated/trust-fixtures"))
}
tasks.named("preBuild") { dependsOn(copyTrustFixtures) }

dependencies { implementation(project(":sdk:trust")) }

plugins { alias(libs.plugins.kotlin.jvm) }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":sdk:digest"))
    api(project(":sdk:trust"))
    implementation(libs.apksig)
    testImplementation(libs.kotlin.test)
}

tasks.test { useJUnitPlatform() }

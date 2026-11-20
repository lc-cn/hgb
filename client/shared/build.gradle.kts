// The console's pages and state, written once for the Android App and the Windows client (C40, C41).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
kotlin {
    jvmToolchain(17)
    android {
        namespace = "io.github.yysassist.shared"
        compileSdk = 37
        minSdk = 30
    }
    jvm("desktop")
    sourceSets {
        commonMain.dependencies {
            api(project(":core:engine"))
            api(libs.cmp.runtime)
            api(libs.cmp.foundation)
            api(libs.cmp.ui)
            api(libs.cmp.material3)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

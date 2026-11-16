import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}
android {
    namespace = "io.github.yysassist"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "io.github.yysassist"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
if (providers.gradleProperty("includeOcrIntegrationModels").orNull == "true") {
    val modelAssets = rootProject.file(".local/maa/prepared/assets")
    android.sourceSets.getByName("androidTest").assets.directories.add(modelAssets.path)
    val verifyOcrIntegrationModels by tasks.registering {
        val lockFile = rootProject.file("vision/inputs.lock.json")
        inputs.file(lockFile); inputs.dir(modelAssets)
        doLast {
            val lock = groovy.json.JsonSlurper().parse(lockFile) as Map<*, *>
            val model = lock["models"] as Map<*, *>
            (model["files"] as List<*>).forEach { value ->
                val pin = value as Map<*, *>; val file = modelAssets.resolve("maa-model/${pin["path"]}")
                check(file.isFile && file.length() == (pin["bytes"] as Number).toLong() &&
                    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } == pin["sha256"]) { "Changed OCR integration fixture: ${pin["path"]}" }
            }
            check(groovy.json.JsonSlurper().parse(modelAssets.resolve("model-manifest.json")) == model)
            val expected = (model["files"] as List<*>).map { "maa-model/${(it as Map<*, *>)["path"]}" }.toSet() +
                setOf("model-manifest.json", "maa-license/LICENSE.md")
            check(modelAssets.walkTopDown().filter { it.isFile }.map { it.relativeTo(modelAssets).invariantSeparatorsPath }.toSet() == expected) { "Unexpected OCR fixture assets" }
        }
    }
    tasks.named("preBuild").configure { dependsOn(verifyOcrIntegrationModels) }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":vision"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.datastore)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.compose.ui.tooling)
}

import java.security.MessageDigest

plugins { alias(libs.plugins.android.application) }
android {
    namespace = "io.github.yysassist.visionprobe"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "io.github.yysassist.visionprobe"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-vision-probe"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main").kotlin.directories.add(rootProject.file("third_party/paddle-ocr-sdk/src/main/java").path)
    sourceSets.getByName("main").assets.directories.add(rootProject.file(".local/vision-poc/assets").path)
    sourceSets.getByName("androidTest").assets.directories.add(rootProject.file(".local/vision-poc/private-test-assets").path)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
val verifyVisionModels by tasks.registering {
    val folder = rootProject.file(".local/vision-poc/assets")
    inputs.dir(folder).optional()
    doLast {
        val manifest = folder.resolve("models-manifest.json")
        check(manifest.isFile) { "Prepare fixed OCR models first: python3 tools/qa/prepare_vision_models.py" }
        val expected = mapOf(
            "models/det/inference.onnx" to "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8",
            "models/rec/inference.onnx" to "9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6",
            "models/rec/inference.yml" to "66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1",
        )
        expected.forEach { (path, digest) ->
            val file = folder.resolve(path)
            check(file.isFile && MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) } == digest) { "Missing or altered probe model: $path" }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyVisionModels) }
dependencies {
    implementation(libs.vision.onnx)
    implementation(libs.vision.opencv)
    implementation(libs.vision.mlkit)
    implementation(libs.coroutines.android)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
}

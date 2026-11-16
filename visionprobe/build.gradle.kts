import java.security.MessageDigest
import groovy.json.JsonSlurper

plugins { alias(libs.plugins.android.application) }
val modelVariant = providers.gradleProperty("visionModel").orElse("v6-tiny").get()
require(modelVariant in setOf("v6-tiny", "v6-small")) { "Unknown visionModel: $modelVariant" }
val modelFolder = rootProject.file(if (modelVariant == "v6-tiny") ".local/vision-poc/assets" else ".local/vision-poc/assets-$modelVariant")
val modelLock = rootProject.file("visionprobe/models.lock.json")
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
    sourceSets.getByName("main").assets.directories.add(modelFolder.path)
    sourceSets.getByName("androidTest").assets.directories.add(rootProject.file(".local/vision-poc/private-test-assets").path)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
val verifyVisionModels by tasks.registering {
    val folder = modelFolder
    val lockFile = modelLock
    val selectedVariant = modelVariant
    inputs.dir(folder).optional()
    inputs.file(lockFile)
    inputs.property("modelVariant", selectedVariant)
    doLast {
        val manifest = folder.resolve("models-manifest.json")
        check(manifest.isFile) { "Prepare models first: python3 tools/qa/prepare_vision_models.py --variant $selectedVariant" }
        val lock = JsonSlurper().parse(lockFile) as Map<*, *>
        val spec = (lock["variants"] as Map<*, *>)[selectedVariant] as Map<*, *>
        val expected = spec["files"] as Map<*, *>
        val provenance = JsonSlurper().parse(manifest) as Map<*, *>
        check(provenance["variant"] == selectedVariant && provenance["source"] == lock["baseUrl"] &&
            provenance["sourceDocument"] == lock["sourceDocument"] && provenance["archives"] == spec["archives"]) { "Altered probe provenance" }
        val records = provenance["files"] as List<*>
        check(records.associate { entry -> (entry as Map<*, *>).let { it["path"] to mapOf("bytes" to it["bytes"], "sha256" to it["sha256"]) } } == expected && records.size == expected.size) { "Altered probe file manifest" }
        expected.forEach { (key, value) ->
            val path = key as String; val pin = value as Map<*, *>
            val file = folder.resolve(path)
            check(file.isFile && file.length() == (pin["bytes"] as Number).toLong() && MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) } == pin["sha256"]) { "Missing or altered probe model: $path" }
        }
        check(folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(folder).invariantSeparatorsPath }.toSet() == expected.keys + "models-manifest.json") { "Unpinned file in probe assets" }
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

import java.security.MessageDigest
import groovy.json.JsonSlurper

plugins { alias(libs.plugins.android.application) }
val preparedMaa = rootProject.file(".local/maa/prepared")
val maaLock = rootProject.file("vision/inputs.lock.json")
val maaModelPins = ((JsonSlurper().parse(maaLock) as Map<*, *>)["models"] as Map<*, *>)["files"] as List<*>
android {
    namespace = "io.github.yysassist.maaprobe"
    compileSdk { version = release(37) }
    ndkVersion = "27.1.12297006"
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "io.github.yysassist.maaprobe"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-maa-probe"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.add("arm64-v8a") }
        externalNativeBuild { cmake { arguments.addAll(listOf("-DMAA_ROOT=${preparedMaa.path}", "-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")) } }
        listOf("det.onnx" to "DETECTOR_SHA256", "rec.onnx" to "RECOGNIZER_SHA256", "keys.txt" to "DICTIONARY_SHA256").forEach { (name, field) ->
            val pin = maaModelPins.map { it as Map<*, *> }.single { it["path"] == name }
            buildConfigField("String", field, "\"${pin["sha256"]}\"")
        }
    }
    sourceSets.getByName("main").assets.directories.add(preparedMaa.resolve("assets").path)
    if (providers.gradleProperty("includePrivateVisionSamples").orNull == "true")
        sourceSets.getByName("androidTest").assets.directories.add(rootProject.file(".local/vision-poc/private-test-assets").path)
    // Probe-only region/graphic-state experiment; private frames/templates never enter Git.
    if (providers.gradleProperty("includePrivateRegionExperiment").orNull == "true")
        sourceSets.getByName("androidTest").assets.directories.add(rootProject.file(".local/vision-poc/region-experiment-assets").path)
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    // Preserve upstream bytes for this experiment's artifact identity checks.
    packaging { jniLibs.keepDebugSymbols.add("**/*.so") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
val verifyMaaInputs by tasks.registering {
    val folder = preparedMaa; val lockFile = maaLock
    inputs.dir(folder).optional(); inputs.file(lockFile)
    doLast {
        val lock = JsonSlurper().parse(lockFile) as Map<*, *>
        val expected = mutableMapOf<String, Map<*, *>>()
        (lock["distributionFiles"] as Map<*, *>).values.forEach { value ->
            val pin = value as Map<*, *>; expected[pin["destination"] as String] = pin
        }
        val models = lock["models"] as Map<*, *>
        (models["files"] as List<*>).forEach { value -> val pin = value as Map<*, *>; expected["assets/maa-model/${pin["path"]}"] = pin }
        expected["assets/maa-license/LICENSE.md"] = lock["license"] as Map<*, *>
        expected["native-assets/maa-license/LICENSE.md"] = lock["license"] as Map<*, *>
        expected.forEach { (path, pin) ->
            val file = folder.resolve(path)
            check(file.isFile && file.length() == (pin["bytes"] as Number).toLong() && MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } == pin["sha256"]) { "Missing or changed Maa input: $path; run python3 tools/qa/prepare_maa_inputs.py" }
        }
        check(JsonSlurper().parse(folder.resolve("assets/model-manifest.json")) == models) { "Changed Maa model provenance" }
        check(folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(folder).invariantSeparatorsPath }.toSet() == expected.keys + setOf("inputs-manifest.json", "assets/model-manifest.json")) { "Unpinned Maa probe file" }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyMaaInputs) }
dependencies {
    implementation(project(":vision"))
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
}

import java.security.MessageDigest
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}
// This deployment's addresses and repositories (config/instance.json); HGB_* environment variables override them, so a
// fork builds its own App by setting repository variables (see .github/workflows/build.yml).
val instanceDefaults = JsonSlurper().parseText(providers.fileContents(
    rootProject.layout.projectDirectory.file("config/instance.json")).asText.get()) as Map<*, *>
fun instance(key: String, env: String): String =
    (providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() } ?: instanceDefaults[key] as String?)?.trim()?.trimEnd('/')
        ?.takeIf { it.isNotBlank() } ?: error("config/instance.json has no $key")
val githubClientId = instance("githubClientId", "HGB_GITHUB_CLIENT_ID")
val instanceApiUrl = instance("apiUrl", "HGB_API_URL")
val instanceSiteUrl = instance("siteUrl", "HGB_SITE_URL")
val instanceCodeRepo = instance("codeRepo", "HGB_CODE_REPO")
val instanceTaskRepo = instance("taskRepo", "HGB_TASK_REPO")
check(Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+").matches(instanceCodeRepo) && Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+").matches(instanceTaskRepo)) {
    "Repositories must look like owner/name"
}
android {
    namespace = "io.github.yysassist"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "io.github.yysassist"
        minSdk = 30
        targetSdk = 37
        // A release build gets these from the tag (see .github/workflows/release-app.yml); local builds stay dev.
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("appVersionName").orNull ?: "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GITHUB_CLIENT_ID", JsonOutput.toJson(githubClientId))
        buildConfigField("String", "API_URL", JsonOutput.toJson(instanceApiUrl))
        buildConfigField("String", "SITE_URL", JsonOutput.toJson(instanceSiteUrl))
        buildConfigField("String", "CODE_REPO", JsonOutput.toJson(instanceCodeRepo))
        buildConfigField("String", "TASK_REPO", JsonOutput.toJson(instanceTaskRepo))
    }
    buildFeatures { compose = true; buildConfig = true }
    // The release key never lives in the repository: CI writes it from the HGB_ANDROID_KEYSTORE secret and passes its
    // path and password here. Without them only debug builds are signed.
    val releaseStore = providers.environmentVariable("HGB_SIGNING_STORE_FILE").orNull
    if (releaseStore != null) signingConfigs.create("release") {
        storeFile = file(releaseStore)
        storeType = "pkcs12"
        storePassword = providers.environmentVariable("HGB_SIGNING_STORE_PASSWORD").get()
        keyAlias = providers.environmentVariable("HGB_SIGNING_KEY_ALIAS").orElse("hgb").get()
        keyPassword = storePassword
    }
    buildTypes.getByName("release") { if (releaseStore != null) signingConfig = signingConfigs.getByName("release") }
    // -PsignDebugWithRelease: a debug build (with the adb-only task trigger) that installs over the released App on a
    // developer's own phone, keeping its data and permissions. Only with the release key at hand, never in CI.
    if (releaseStore != null && providers.gradleProperty("signDebugWithRelease").isPresent)
        buildTypes.getByName("debug") { signingConfig = signingConfigs.getByName("release") }
    // The vision libraries are ~140 MB stored, ~40 MB compressed. Compressing them makes the download small; Android
    // extracts them at install, which costs about the same space on the phone.
    packaging { jniLibs { useLegacyPackaging = true } }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
// Private game frames for offline task replay; never committed, never in a default test APK.
if (providers.gradleProperty("includePrivateReplayFrames").orNull == "true")
    android.sourceSets.getByName("androidTest").assets.directories.add(rootProject.file(".local/vision-poc/region-experiment-assets").path)
if (providers.gradleProperty("includeOcrIntegrationModels").orNull == "true") {
    val modelAssets = rootProject.file(".local/maa/prepared/assets")
    android.sourceSets.getByName("androidTest").assets.directories.add(modelAssets.path)
    val verifyOcrIntegrationModels by tasks.registering {
        val lockFile = rootProject.file("client/android/vision/inputs.lock.json")
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
    implementation(project(":core:engine"))
    implementation(project(":client:shared"))
    implementation(project(":client:android:vision"))
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

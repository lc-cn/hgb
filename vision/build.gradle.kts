import java.security.MessageDigest
import groovy.json.JsonSlurper

plugins { alias(libs.plugins.android.library) }
val preparedMaa = rootProject.file(".local/maa/prepared")
val maaLock = project.file("inputs.lock.json")
android {
    namespace = "io.github.yysassist.vision"
    compileSdk { version = release(37) }
    ndkVersion = "27.1.12297006"
    buildFeatures { buildConfig = true }
    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters.add("arm64-v8a") }
        externalNativeBuild { cmake { arguments.addAll(listOf("-DMAA_ROOT=${preparedMaa.path}", "-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")) } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addStaticSourceDirectory(preparedMaa.resolve("jniLibs").path)
    // Native code notices only. Models and their manifests belong to cloud task versions.
    variant.sources.assets?.addStaticSourceDirectory(preparedMaa.resolve("native-assets").path)
}
val verifyMaaNativeInputs by tasks.registering {
    val folder = preparedMaa; val lockFile = maaLock
    inputs.dir(folder.resolve("jniLibs")).optional(); inputs.dir(folder.resolve("include")).optional()
    inputs.dir(folder.resolve("native-assets")).optional(); inputs.file(lockFile)
    doLast {
        val lock = JsonSlurper().parse(lockFile) as Map<*, *>
        val expected = mutableMapOf<String, Map<*, *>>()
        (lock["distributionFiles"] as Map<*, *>).values.forEach { value ->
            val pin = value as Map<*, *>; expected[pin["destination"] as String] = pin
        }
        expected["native-assets/maa-license/LICENSE.md"] = lock["license"] as Map<*, *>
        expected.forEach { (path, pin) ->
            val file = folder.resolve(path)
            check(file.isFile && file.length() == (pin["bytes"] as Number).toLong()) { "Missing Maa native input: $path; run python3 tools/qa/prepare_maa_inputs.py --native-only" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == pin["sha256"]) { "Changed Maa native input: $path" }
        }
        val actual = listOf("jniLibs", "include", "native-assets").flatMap { name ->
            folder.resolve(name).walkTopDown().filter { it.isFile }.map { it.relativeTo(folder).invariantSeparatorsPath }.toList()
        }.toSet()
        check(actual == expected.keys) { "Unpinned Maa native input" }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyMaaNativeInputs) }

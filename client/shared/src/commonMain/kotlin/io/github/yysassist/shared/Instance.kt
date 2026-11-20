package io.github.yysassist.shared

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The deployment this build belongs to: its API, its site (home page, docs, downloads) and its code repository. Each
 * end supplies it from its build (config/instance.json, or a fork's HGB_* variables), so links never point at
 * someone else's deployment.
 */
data class Instance(val apiUrl: String, val siteUrl: String, val codeRepo: String) {
    val docsUrl get() = "$siteUrl/docs/"
    val downloadUrl get() = "$siteUrl/#download"
    val issuesUrl get() = "https://github.com/$codeRepo/issues"
    val privacyUrl get() = "$siteUrl/privacy/"
    val termsUrl get() = "$siteUrl/terms/"
}
val LocalInstance = staticCompositionLocalOf<Instance> { error("no Instance provided") }

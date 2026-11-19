plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17) }
application { mainClass.set("io.github.yysassist.server.MainKt") }
dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.bouncycastle)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.coroutines.test)
}
tasks.test { useJUnit(); testLogging { events("failed", "skipped") } }

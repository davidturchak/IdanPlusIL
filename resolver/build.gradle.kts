plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    }
}

sourceSets {
    // Live channel-health harness. Run on demand, never part of `check`.
    create("liveCheck") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

val liveCheckImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.implementation.get())
}
val liveCheckRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations.runtimeOnly.get())
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.register<JavaExec>("liveCheck") {
    group = "verification"
    description = "Resolves every visible channel against the live network and reports OK / DEGRADED / FAIL."
    mainClass.set("com.idanplusil.resolver.livecheck.LiveCheckMainKt")
    classpath = sourceSets["liveCheck"].runtimeClasspath
    // Never cache: the whole point is to hit the network.
    outputs.upToDateWhen { false }
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}

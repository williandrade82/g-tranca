// Simulador headless bot × bot (CLI).
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

application {
    mainClass.set("com.gtranca.sim.MainKt")
}

dependencies {
    implementation(project(":ai"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

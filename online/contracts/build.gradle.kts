// Contratos do jogo online (DTOs @Serializable, versao de protocolo, id da mesa). Usado por servicos e :online-client.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

dependencies {
    api(libs.kotlinx.serialization.json)
    api(project(":core-engine")) // so tipos (Card, Action, PlayerView...); o motor nao e alterado

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

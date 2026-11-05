// Cliente do jogo online (docs/plano-online.md): HTTP do lobby + WebSocket da partida. Kotlin/JVM puro (testável sem Android);
// usa os DTOs de online/contracts (módulo :online-contracts) sem duplicá-los. Só o :app depende dele.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

dependencies {
    api(project(":online-contracts"))
    api(libs.kotlinx.coroutines.core)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.websockets)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Prova real opcional (OnlineLiveIntegrationTest): ligada pela variável GTRANCA_ONLINE_SERVER (endereço do servidor).
    inputs.property("liveServer", providers.environmentVariable("GTRANCA_ONLINE_SERVER").orElse(""))
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

application {
    mainClass.set("com.gtranca.online.admin.MainKt")
}

dependencies {
    // So leitura: Health, ServerInfo, ApiError/ErrorCode, AccountLimits (§18.7 espelha §18.1). Nada novo entra ali.
    implementation(project(":contracts"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    // Painel (Sec18.7): sondar /health de lobby/game/economy-service (mesma biblioteca que o lobby-service usa
    // para falar com o economy-service, ver EconomyGateway.kt).
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.bcrypt)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.embedded.postgres)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

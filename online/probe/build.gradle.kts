// Cliente de linha de comando para PROVAR o fluxo online no cluster (nao e um servico; nao vai em imagem).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

application {
    mainClass.set("com.gtranca.online.probe.MainKt")
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":ai"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.logback.classic)
}

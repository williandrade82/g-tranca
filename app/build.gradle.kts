import java.util.Properties

/** Commits antes do primeiro versionado: o commit 87 do histórico vira a versão 0.1. */
val VERSION_BASE_COMMITS = 86

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Rotas tipadas do navigation-compose.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.gtranca"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Identificador definitivo na loja (não muda depois de publicado). O código continua no pacote com.gtranca.
        applicationId = "br.com.funnyandplay.mestredacanastra"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // Versão 0.N até a primeira publicação: N sobe a cada commit (contagem do git a partir do commit 87 = 0.1).
        // Gere o APK depois de commitar para que a versão bata com o hash no nome do arquivo.
        val build = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
            .standardOutput.asText.map { it.trim().toInt() - VERSION_BASE_COMMITS }.getOrElse(1).coerceAtLeast(1)
        versionCode = build
        versionName = "0.$build"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Assinatura de release: lida de keystore.properties (fora do git). Sem o arquivo, o release sai assinado com a
    // chave de debug, só para testar no celular o build otimizado (R8); nunca publique esse.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }
    }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Testes locais (JVM) com JUnit 5 + Kotest, como nos módulos puros.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    implementation(project(":core-engine"))
    implementation(project(":ai"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Testes de UI Compose no emulador/dispositivo.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

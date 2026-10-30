pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Permite ao Gradle baixar o JDK 17 da toolchain caso não o encontre instalado.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "g-tranca"

include(":core-engine")
include(":ai")
include(":sim")
include(":data")
include(":app")
include(":net")

// Jogo online (docs/plano-online.md): o :online-client usa os DTOs de online/contracts SEM duplicá-los. O módulo é incluído
// por caminho (nenhum arquivo de online/ muda); não se usa includeBuild porque o build de online/ também inclui :core-engine.
include(":online-contracts")
project(":online-contracts").projectDir = file("online/contracts")
include(":online-client")

// Build PROPRIO do jogo online (nao faz parte do settings.gradle.kts do app).
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "g-tranca-online"

include(":contracts")
include(":game-service")
include(":lobby-service")
include(":probe")

// Motor e robos do app: usados SOMENTE LEITURA, por caminho (nenhum arquivo do app e alterado).
// Layout esperado: <raiz>/core-engine, <raiz>/ai e <raiz>/online (no Docker, o contexto e a raiz do repositorio).
include(":core-engine")
project(":core-engine").projectDir = file("../core-engine")
include(":ai")
project(":ai").projectDir = file("../ai")

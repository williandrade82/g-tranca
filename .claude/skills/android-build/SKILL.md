---
name: android-build
description: Compilar, testar e instalar o G-Tranca no Windows (Gradle, JDK 21 com toolchain 17, Android SDK, emulador/ADB). Use para rodar testes, gerar o APK, instalar no dispositivo ou diagnosticar falhas de build e de ambiente.
---

# Build e testes do G-Tranca (Windows)

## Pré-requisitos
- **JDK 21** para rodar o Gradle: `JAVA_HOME` = `C:\Program Files\Java\jdk-21` (**sem** `\bin` no final).
- **JDK 17** para compilar: os módulos usam `kotlin { jvmToolchain(17) }`; o Gradle detecta o `C:\Program Files\Java\jdk-17` instalado. Não troque o `JAVA_HOME` para o 17.
- **Android SDK** em `%LOCALAPPDATA%\Android\Sdk`: definir `ANDROID_HOME` e adicionar `%ANDROID_HOME%\platform-tools` e `%ANDROID_HOME%\emulator` ao PATH (para `adb`, `emulator` e o MCP `mobile-mcp`).
- Plataforma instalada para compilar: `android-37.0`; build-tools `36.0.0`. Emulador recomendado para testes: AVD `Pixel_4_API_33`.
- `local.properties` com `sdk.dir=C\:\\Users\\<usuario>\\AppData\\Local\\Android\\Sdk` (não versionar).

Verificação rápida:
```powershell
& "$env:JAVA_HOME\bin\java" -version; "ANDROID_HOME=$env:ANDROID_HOME"; adb version; .\gradlew.bat --version
```

## Comandos
| Objetivo | Comando |
|---|---|
| Testes do motor (rápido) | `.\gradlew.bat :core-engine:test` |
| Testes dos bots | `.\gradlew.bat :ai:test` |
| Todos os testes JVM | `.\gradlew.bat test` |
| Lint Android | `.\gradlew.bat :app:lintDebug` |
| APK debug | `.\gradlew.bat :app:assembleDebug` |
| Instalar no dispositivo | `.\gradlew.bat :app:installDebug` |
| Testes instrumentados | `.\gradlew.bat :app:connectedDebugAndroidTest` |

## Diagnóstico
- Relatórios de teste: `<modulo>\build\reports\tests\test\index.html`.
- Para rodar um único teste: `--tests "*NomeDoTeste*"`.
- Erro de versão de Java: confira `JAVA_HOME` e `org.gradle.java.home` em `gradle.properties`.
- `SDK location not found`: falta `local.properties` ou `ANDROID_HOME`.
- Ao terminar, informe o comando executado e o resultado (passou/falhou, com as primeiras linhas do erro).

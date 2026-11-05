plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// online/contracts também é construído pelo build próprio de online/: build separado para os dois não disputarem a pasta.
project(":online-contracts") {
    layout.buildDirectory = rootProject.layout.buildDirectory.dir("online-contracts")
}

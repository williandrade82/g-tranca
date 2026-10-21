package com.gtranca.net.nearby

/**
 * Permissões de tempo de execução do Nearby Connections por versão do Android (o `minSdk` é 26). As de instalação
 * (Wi-Fi, Bluetooth legado) estão no manifesto. Pedidas **só** ao entrar no modo multijogador ([MultiplayerAccessGate]).
 * - API 26–30: localização (o Android exige para achar aparelhos por Bluetooth/Wi-Fi).
 * - API 31–32: Bluetooth (procurar, anunciar, conectar) e localização (a busca por Wi-Fi ainda a exige).
 * - API 33+: Bluetooth e "Dispositivos Wi-Fi por perto" (`neverForLocation`); localização deixa de ser pedida.
 */
object NearbyPermissions {
    const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    const val BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
    const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"

    private val bluetooth = listOf(BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT)
    private val location = listOf(FINE_LOCATION, COARSE_LOCATION)

    fun required(sdk: Int): List<String> = when {
        sdk >= 33 -> bluetooth + NEARBY_WIFI_DEVICES
        sdk >= 31 -> bluetooth + location
        else -> location
    }

    /** A busca só funciona com a localização do sistema ligada (até a API 32). */
    fun needsLocationOn(sdk: Int): Boolean = sdk <= 32
}

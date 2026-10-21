package com.gtranca.net.nearby

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Etapa 5: permissões do Nearby nas três faixas de API (`minSdk` 26). */
class NearbyPermissionsTest {
    @Test
    fun `cada faixa de API pede o que o Nearby precisa`() {
        NearbyPermissions.required(26) shouldContainExactlyInAnyOrder listOf(NearbyPermissions.FINE_LOCATION, NearbyPermissions.COARSE_LOCATION)
        NearbyPermissions.required(30) shouldNotContain NearbyPermissions.BLUETOOTH_SCAN
        NearbyPermissions.required(31) shouldContainExactlyInAnyOrder listOf(
            NearbyPermissions.BLUETOOTH_SCAN, NearbyPermissions.BLUETOOTH_ADVERTISE, NearbyPermissions.BLUETOOTH_CONNECT,
            NearbyPermissions.FINE_LOCATION, NearbyPermissions.COARSE_LOCATION,
        )
        NearbyPermissions.required(33) shouldContain NearbyPermissions.NEARBY_WIFI_DEVICES
        NearbyPermissions.required(36) shouldNotContain NearbyPermissions.FINE_LOCATION
        NearbyPermissions.required(36) shouldNotContain NearbyPermissions.ACCESS_LOCAL_NETWORK
        NearbyPermissions.required(37) shouldContain NearbyPermissions.ACCESS_LOCAL_NETWORK
        NearbyPermissions.needsLocationOn(32) shouldBe true
        NearbyPermissions.needsLocationOn(33) shouldBe false
    }
}

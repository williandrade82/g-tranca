package com.gtranca.net.nearby

import com.gtranca.net.PresenceAdvert
import com.gtranca.net.TableAdvert
import com.gtranca.net.newPresenceKey
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** §16.7, §16.10 o anúncio da mesa no `endpointInfo` do Nearby: cabe no limite e preserva o que identifica a mesa. */
class AdvertCodecTest {

    @Test
    fun `ida e volta preserva todos os campos`() {
        val advert = TableAdvert("ab3kx9mp", "Mesa da vó", "Joana", inProgress = true, listed = false, hostId = 300, epoch = 2, provisionalRank = 1)
        AdvertCodec.decode(AdvertCodec.encode(advert)) shouldBe advert
    }

    @Test
    fun `nomes longos e acentuados sao cortados por caractere e o resto fica intacto`() {
        val advert = TableAdvert("ab3kx9mp", "Mesa ".repeat(10) + "ção çãé 😀😀😀", "Maria Conceição ".repeat(4), hostId = 7, epoch = 1)
        val bytes = AdvertCodec.encode(advert)
        bytes.size shouldBeLessThanOrEqual AdvertCodec.MAX_BYTES
        val back = AdvertCodec.decode(bytes)!!
        back.copy(tableName = advert.tableName, hostName = advert.hostName) shouldBe advert
        advert.hostName shouldStartWith back.hostName
        advert.tableName shouldStartWith back.tableName
        back.hostName.contains('�') shouldBe false
        back.tableName.contains('�') shouldBe false
    }

    @Test
    fun `qualquer anuncio cabe no limite e volta com a identidade da mesa`() = runTest {
        val arb = Arb.bind(Arb.string(0..60), Arb.string(0..60), Arb.boolean(), Arb.boolean(), Arb.int(0..100_000), Arb.int(0..50), Arb.int(0..2)) {
            table, host, inProgress, listed, hostId, epoch, rank ->
            TableAdvert(newTableId(java.util.Random(hostId.toLong())), table, host, inProgress, listed, hostId, epoch, rank)
        }
        checkAll(300, arb) { advert ->
            val bytes = AdvertCodec.encode(advert)
            bytes.size shouldBeLessThanOrEqual AdvertCodec.MAX_BYTES
            val back = AdvertCodec.decode(bytes)!!
            back.tableId shouldBe advert.tableId
            back.hostId shouldBe advert.hostId
            back.epoch shouldBe advert.epoch
            back.provisionalRank shouldBe advert.provisionalRank
            back.listed shouldBe advert.listed
            back.inProgress shouldBe advert.inProgress
        }
    }

    @Test
    fun `bytes de outro formato ou corrompidos sao ignorados`() {
        AdvertCodec.decode(byteArrayOf()) shouldBe null
        AdvertCodec.decode(byteArrayOf(2, 0, 0, 0, 0)) shouldBe null
        val good = AdvertCodec.encode(TableAdvert("abcdefgh", "Mesa", "Ana"))
        AdvertCodec.decode(good.copyOf(good.size - 3)) shouldBe null
        repeat(200) { AdvertCodec.decode(Random(it).nextBytes(Random(it).nextInt(1, 40))) }
    }

    @Test
    fun `§16_2 mesa com 3 convidados e nomes longos acentuados cabe no limite e preserva as chaves`() {
        val keys = List(3) { newPresenceKey(java.util.Random(it.toLong())) }
        val advert = TableAdvert("ab3kx9mp", "Mesa de " + "Conceição ".repeat(8), "Maria Conceição 😀".repeat(4), hostId = 70_000, epoch = 3, invited = keys)
        val bytes = AdvertCodec.encode(advert)
        bytes.size shouldBeLessThanOrEqual AdvertCodec.MAX_BYTES
        val back = AdvertCodec.decode(bytes)!!
        back.invited shouldBe keys
        back.tableId shouldBe advert.tableId
        AdvertCodec.decodePresence(bytes) shouldBe null // um anúncio de mesa não é de presença
    }

    @Test
    fun `§16_2 presenca de jogador proximo e distinta do anuncio de mesa e cabe no limite`() {
        val presence = PresenceAdvert(newPresenceKey(java.util.Random(1)), "José Conceição 😀 ".repeat(10))
        val bytes = AdvertCodec.encodePresence(presence)
        bytes.size shouldBeLessThanOrEqual AdvertCodec.MAX_BYTES
        val back = AdvertCodec.decodePresence(bytes)!!
        back.key shouldBe presence.key
        presence.name shouldStartWith back.name
        back.name.contains('�') shouldBe false
        AdvertCodec.decode(bytes) shouldBe null // a presença não aparece como mesa
        AdvertCodec.decodePresence(AdvertCodec.encodePresence(PresenceAdvert("k1", "Ana"))) shouldBe PresenceAdvert("k1", "Ana")
    }
}

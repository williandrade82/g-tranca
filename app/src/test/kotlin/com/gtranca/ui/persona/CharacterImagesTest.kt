package com.gtranca.ui.persona

import com.gtranca.game.BotCharacter
import com.gtranca.game.BotLevel
import com.gtranca.game.BotProfile
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §14.5 cada personagem (perfil × nível) tem as 4 poses. */
class CharacterImagesTest {

    @Test
    fun `§14_5 as 48 imagens existem, uma para cada perfil, nivel e pose`() {
        IMAGES.size shouldBe BotProfile.entries.size * BotLevel.entries.size * Pose.entries.size
        IMAGES.size shouldBe 48
        IMAGES.values.toList().shouldBeUnique()
        BotProfile.entries.forEach { profile ->
            BotLevel.entries.forEach { level ->
                Pose.entries.forEach { pose -> characterRes(BotCharacter(profile, level), pose) }
            }
        }
    }
}

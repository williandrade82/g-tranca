package com.gtranca.engine

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Garante que os testes do motor rodam na toolchain Java 17 definida no build. */
class BuildSetupTest {
    @Test
    fun `testes rodam na toolchain Java 17`() {
        Runtime.version().feature() shouldBe 17
    }
}

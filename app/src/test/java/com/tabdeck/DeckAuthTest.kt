package com.tabdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// PC(deck_app.py의 auth_proof)와 앱(DeckAuth.proof)이 똑같은 값을 계산하는지 확인
// 기대값은 PC 쪽 파이썬 코드로 계산한 것
class DeckAuthTest {
    @Test
    fun proofMatchesPc() {
        assertEquals(
            "2ee8f831c1a411b40127fbf9fcee5e04407f9637f1173cca9fcedda5cf809ce3",
            DeckAuth.proof("TestKey1", "app", "00112233445566778899aabbccddeeff", "ffeeddccbbaa99887766554433221100")
        )
        assertEquals(
            "0398d81e7f69da5e8f592e85a5f9544f3353a8fb8bbf6cfe9881d59b37a7e28c",
            DeckAuth.proof("한글키", "pc", "aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
        )
    }

    @Test
    fun nonceIsRandomHex() {
        val a = DeckAuth.newNonce()
        val b = DeckAuth.newNonce()
        assertEquals(32, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertFalse(a == b)
    }

    @Test
    fun sameComparesExactly() {
        assertTrue(DeckAuth.same("abc", "abc"))
        assertFalse(DeckAuth.same("abc", "abd"))
        assertFalse(DeckAuth.same("abc", "abcd"))
    }
}

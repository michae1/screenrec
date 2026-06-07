package com.example.screenrec.recorder

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AudioMixerTest {

    private fun samples(vararg s: Short): ByteArray {
        val out = ByteArray(s.size * 2)
        for (i in s.indices) {
            out[i * 2] = (s[i].toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s[i].toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun sumsTwoSignals() {
        val a = samples(100, -200)
        val b = samples(50, 200)
        assertArrayEquals(samples(150, 0), AudioMixer.mix(a, b, a.size))
    }

    @Test
    fun clampsPositiveOverflow() {
        val a = samples(30000)
        val b = samples(30000)
        assertArrayEquals(samples(Short.MAX_VALUE), AudioMixer.mix(a, b, a.size))
    }

    @Test
    fun clampsNegativeOverflow() {
        val a = samples(-30000)
        val b = samples(-30000)
        assertArrayEquals(samples(Short.MIN_VALUE), AudioMixer.mix(a, b, a.size))
    }

    @Test
    fun mixesOnlyRequestedLength() {
        val a = samples(100, 999)
        val b = samples(50, 999)
        assertArrayEquals(samples(150), AudioMixer.mix(a, b, 2))
    }
}

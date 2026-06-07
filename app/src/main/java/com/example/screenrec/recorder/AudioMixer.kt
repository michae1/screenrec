package com.example.screenrec.recorder

/** Mixes two 16-bit little-endian PCM buffers by summing samples with clamping. */
object AudioMixer {
    fun mix(a: ByteArray, b: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var i = 0
        while (i + 1 < length) {
            val s1 = (((a[i + 1].toInt()) shl 8) or (a[i].toInt() and 0xFF)).toShort().toInt()
            val s2 = (((b[i + 1].toInt()) shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
            var sum = s1 + s2
            if (sum > Short.MAX_VALUE) sum = Short.MAX_VALUE.toInt()
            if (sum < Short.MIN_VALUE) sum = Short.MIN_VALUE.toInt()
            out[i] = (sum and 0xFF).toByte()
            out[i + 1] = ((sum shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }
}

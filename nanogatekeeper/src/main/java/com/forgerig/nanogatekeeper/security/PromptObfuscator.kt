package com.forgerig.nanogatekeeper.security

object PromptObfuscator {
    private const val K1: Byte = 0x5A
    private const val K2: Byte = 0x3C

    fun mask(plain: ByteArray): ByteArray {
        val out = ByteArray(plain.size)
        for (i in plain.indices) {
            val k: Byte = if (i % 2 == 0) K1 else K2
            out[i] = (plain[i].toInt() xor k.toInt()).toByte()
        }
        return out
    }

    fun unmask(masked: ByteArray): ByteArray = mask(masked)

    fun decodeToString(masked: ByteArray): String {
        val raw = unmask(masked)
        val s = String(raw, Charsets.UTF_8)
        java.util.Arrays.fill(raw, 0)
        return s
    }

    fun maskStringToLiteral(plain: String): String {
        val m = mask(plain.toByteArray(Charsets.UTF_8))
        return m.joinToString(", ", "byteArrayOf(", ")") { it.toString() }
    }
}

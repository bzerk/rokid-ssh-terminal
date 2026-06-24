package com.clawsses.glasses.terminal

import java.io.ByteArrayOutputStream

class AnsiColorRemapper {
    private var buffer = ByteArray(0)

    fun reset() { buffer = ByteArray(0) }

    fun process(data: ByteArray, length: Int): ByteArray {
        val input = if (buffer.isNotEmpty()) buffer + data.copyOf(length) else data.copyOf(length)
        buffer = ByteArray(0)
        val out = ByteArrayOutputStream(input.size)
        var i = 0
        while (i < input.size) {
            val b = input[i]
            if (b == 0x1B.toByte() && i + 1 < input.size && input[i + 1] == '['.code.toByte()) {
                var j = i + 2
                while (j < input.size && (input[j] < 0x40 || input[j] > 0x7E)) j++
                if (j >= input.size) { buffer = input.copyOfRange(i, input.size); break }
                if (input[j] == 'm'.code.toByte()) {
                    val mapped = remapSgr(String(input, i + 2, j - i - 2, Charsets.US_ASCII))
                    out.write(mapped)
                } else {
                    out.write(input, i, j - i + 1)
                }
                i = j + 1
            } else {
                out.write(b.toInt()); i++
            }
        }
        return out.toByteArray()
    }

    private fun remapSgr(params: String): ByteArray {
        if (params.isEmpty()) return "[0m".toByteArray()
        val codes = params.split(";").map { it.toIntOrNull() ?: 0 }
        val out = mutableListOf<String>()
        var i = 0
        while (i < codes.size) {
            when (val c = codes[i]) {
                0 -> { out.add("0"); i++ }
                in FG_LEVEL -> { out.add(FG_LEVEL[c]!!); i++ }
                39 -> { out.add("39"); i++ }
                in 40..47, in 100..107 -> { out.add("49"); i++ }
                49 -> { out.add("49"); i++ }
                38 -> when {
                    i + 2 < codes.size && codes[i + 1] == 5 -> { out.add(color256Level(codes[i + 2])); i += 3 }
                    i + 4 < codes.size && codes[i + 1] == 2 -> { out.add(lumaLevel(rgbLuma(codes[i + 2], codes[i + 3], codes[i + 4]))); i += 5 }
                    else -> i++
                }
                48 -> when {
                    i + 2 < codes.size && codes[i + 1] == 5 -> { out.add("49"); i += 3 }
                    i + 4 < codes.size && codes[i + 1] == 2 -> { out.add("49"); i += 6 }
                    else -> i++
                }
                else -> i++
            }
        }
        if (out.isEmpty()) return ByteArray(0)
        return "[${out.joinToString(";")}m".toByteArray()
    }

    companion object {
        private const val L_BRIGHT = "38;2;255;255;255"
        private const val L_MED    = "38;2;160;160;160"
        private const val L_DIM    = "38;2;70;70;70"

        private fun lumaLevel(luma: Int) = when { luma >= 160 -> L_BRIGHT; luma >= 70 -> L_MED; else -> L_DIM }
        private fun rgbLuma(r: Int, g: Int, b: Int) = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

        private fun color256Level(n: Int): String = when {
            n in 0..7   -> FG_LEVEL[n + 30] ?: L_MED
            n in 8..15  -> FG_LEVEL[n - 8 + 90] ?: L_MED
            n in 16..231 -> { val idx = n - 16
                val r = if (idx / 36 == 0) 0 else 55 + (idx / 36) * 40
                val g = if ((idx / 6) % 6 == 0) 0 else 55 + ((idx / 6) % 6) * 40
                val b = if (idx % 6 == 0) 0 else 55 + (idx % 6) * 40
                lumaLevel(rgbLuma(r, g, b)) }
            else -> lumaLevel(8 + (n - 232) * 10)
        }

        val FG_LEVEL = mapOf(
            30 to L_DIM, 31 to L_DIM, 32 to L_MED, 33 to L_MED,
            34 to L_DIM, 35 to L_DIM, 36 to L_MED, 37 to L_MED,
            90 to L_DIM, 91 to L_MED, 92 to L_BRIGHT, 93 to L_BRIGHT,
            94 to L_MED, 95 to L_MED, 96 to L_BRIGHT, 97 to L_BRIGHT
        )
    }
}

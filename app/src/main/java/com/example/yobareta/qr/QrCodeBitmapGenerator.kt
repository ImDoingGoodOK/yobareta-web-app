package com.example.yobareta.qr

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Self-contained pure-Kotlin QR Code generator for Android.
 * Generates high-contrast Bitmaps for patient check-in URLs with zero external dependencies.
 */
object QrCodeBitmapGenerator {

    /**
     * Generates an Android [Bitmap] containing a scannable QR code for [content].
     *
     * @param content The URL or text string to encode (e.g. "https://...?ticket=101&name=...").
     * @param sizePx Desired bitmap width and height in pixels (e.g. 512).
     * @param foregroundColor ARGB color for dark modules (default: black).
     * @param backgroundColor ARGB color for light modules (default: white).
     */
    fun encodeToBitmap(
        content: String,
        sizePx: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): Bitmap {
        val matrix = generateQrMatrix(content)
        val matrixSize = matrix.size
        val scale = (sizePx / matrixSize).coerceAtLeast(1)
        val actualSize = matrixSize * scale

        val bitmap = Bitmap.createBitmap(actualSize, actualSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(actualSize * actualSize)

        for (y in 0 until actualSize) {
            val my = y / scale
            val yOffset = y * actualSize
            for (x in 0 until actualSize) {
                val mx = x / scale
                pixels[yOffset + x] = if (matrix[my][mx]) foregroundColor else backgroundColor
            }
        }

        bitmap.setPixels(pixels, 0, actualSize, 0, 0, actualSize, actualSize)
        return bitmap
    }

    // -------------------------------------------------------------------------
    // Minimal ISO/IEC 18004 QR Code Matrix Encoder (Versions 1-6, ECC Level M)
    // -------------------------------------------------------------------------

    private fun generateQrMatrix(text: String): Array<BooleanArray> {
        val dataBytes = text.toByteArray(StandardCharsets.UTF_8)
        // Select smallest fitting version (Level M)
        val version = selectVersion(dataBytes.size)
        val totalCodewords = TOTAL_CODEWORDS[version]
        val dataCodewords = DATA_CODEWORDS_M[version]
        val eccCodewords = totalCodewords - dataCodewords

        // 1. Bit Buffer: Mode (Byte: 0100) + Char Count + Data + Terminator + Padding
        val bitBuffer = BitBuffer()
        bitBuffer.appendBits(0b0100, 4) // Byte mode
        val countBits = if (version <= 9) 8 else 16
        bitBuffer.appendBits(dataBytes.size, countBits)
        for (b in dataBytes) {
            bitBuffer.appendBits(b.toInt() and 0xFF, 8)
        }

        // Terminator (up to 4 zero bits)
        val totalDataBits = dataCodewords * 8
        val terminatorBits = (totalDataBits - bitBuffer.bitLength).coerceIn(0, 4)
        bitBuffer.appendBits(0, terminatorBits)

        // Pad to byte boundary
        while (bitBuffer.bitLength % 8 != 0) {
            bitBuffer.appendBits(0, 1)
        }

        // Pad bytes (0xEC, 0x11) until full
        val padBytes = byteArrayOf(0xEC.toByte(), 0x11.toByte())
        var padIndex = 0
        while (bitBuffer.byteLength < dataCodewords) {
            bitBuffer.appendBits(padBytes[padIndex].toInt() and 0xFF, 8)
            padIndex = (padIndex + 1) % 2
        }

        val dataBytesArray = bitBuffer.toByteArray()

        // 2. Reed-Solomon Error Correction Code
        val eccBytes = computeReedSolomon(dataBytesArray, eccCodewords)
        val finalSequence = ByteArray(totalCodewords)
        System.arraycopy(dataBytesArray, 0, finalSequence, 0, dataBytesArray.size)
        System.arraycopy(eccBytes, 0, finalSequence, dataBytesArray.size, eccBytes.size)

        // 3. Grid Construction
        val size = version * 4 + 17
        val grid = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        // Finder Patterns
        drawFinderPattern(grid, isFunction, 0, 0)
        drawFinderPattern(grid, isFunction, size - 7, 0)
        drawFinderPattern(grid, isFunction, 0, size - 7)

        // Timing Patterns
        for (i in 8 until size - 8) {
            val color = (i % 2 == 0)
            if (!isFunction[6][i]) {
                grid[6][i] = color
                isFunction[6][i] = true
            }
            if (!isFunction[i][6]) {
                grid[i][6] = color
                isFunction[i][6] = true
            }
        }

        // Dark module
        grid[size - 8][8] = true
        isFunction[size - 8][8] = true

        // Alignment Patterns (Version >= 2)
        if (version >= 2) {
            val alignPos = ALIGNMENT_PATTERN_POS[version]
            for (r in alignPos) {
                for (c in alignPos) {
                    if (!isFunction[r][c]) {
                        drawAlignmentPattern(grid, isFunction, r, c)
                    }
                }
            }
        }

        // Format info reservation
        for (i in 0..8) {
            isFunction[8][i] = true
            isFunction[i][8] = true
            isFunction[8][size - 1 - i] = true
            isFunction[size - 1 - i][8] = true
        }

        // 4. Place Data Codewords (zigzag upward/downward)
        var bitIndex = 0
        val totalBits = finalSequence.size * 8
        var right = size - 1
        while (right > 0) {
            if (right == 6) right-- // Skip vertical timing column
            val upward = ((right + 1) / 2) % 2 == 1
            val rows = if (upward) (size - 1 downTo 0) else (0 until size)

            for (y in rows) {
                for (x in right downTo right - 1) {
                    if (!isFunction[y][x]) {
                        var bit = false
                        if (bitIndex < totalBits) {
                            val byteVal = finalSequence[bitIndex / 8].toInt() and 0xFF
                            val bitVal = (byteVal ushr (7 - (bitIndex % 8))) and 1
                            bit = (bitVal == 1)
                            bitIndex++
                        }
                        // Mask pattern 0: (row + col) % 2 == 0
                        val mask = ((y + x) % 2 == 0)
                        grid[y][x] = if (mask) !bit else bit
                    }
                }
            }
            right -= 2
        }

        // 5. Draw Format Information (ECC Level M, Mask 0 -> Format bits: 101010000010010)
        drawFormatBits(grid, size, 0b101010000010010)

        // Add 4-module quiet zone around the QR code
        val quietZone = 4
        val fullSize = size + quietZone * 2
        val bordered = Array(fullSize) { BooleanArray(fullSize) }
        for (y in 0 until size) {
            for (x in 0 until size) {
                bordered[y + quietZone][x + quietZone] = grid[y][x]
            }
        }

        return bordered
    }

    private fun drawFinderPattern(grid: Array<BooleanArray>, isFunc: Array<BooleanArray>, startX: Int, startY: Int) {
        for (y in -1..7) {
            for (x in -1..7) {
                val gx = startX + x
                val gy = startY + y
                if (gx in grid.indices && gy in grid.indices) {
                    val isBorder = (x in 0..6 && (y == 0 || y == 6)) || (y in 0..6 && (x == 0 || x == 6))
                    val isCenter = (x in 2..4 && y in 2..4)
                    grid[gy][gx] = (isBorder || isCenter)
                    isFunc[gy][gx] = true
                }
            }
        }
    }

    private fun drawAlignmentPattern(grid: Array<BooleanArray>, isFunc: Array<BooleanArray>, centerX: Int, centerY: Int) {
        for (y in -2..2) {
            for (x in -2..2) {
                val gx = centerX + x
                val gy = centerY + y
                val isBorder = (Math.abs(x) == 2 || Math.abs(y) == 2)
                val isCenter = (x == 0 && y == 0)
                grid[gy][gx] = (isBorder || isCenter)
                isFunc[gy][gx] = true
            }
        }
    }

    private fun drawFormatBits(grid: Array<BooleanArray>, size: Int, formatBits: Int) {
        for (i in 0..14) {
            val bit = ((formatBits ushr (14 - i)) and 1) == 1
            // Top-left format placement
            val (r1, c1) = FORMAT_POS_TOP_LEFT[i]
            grid[r1][c1] = bit

            // Split bottom-left and top-right placement
            if (i < 7) {
                grid[size - 1 - i][8] = bit
            } else {
                grid[8][size - 15 + i] = bit
            }
        }
    }

    private val FORMAT_POS_TOP_LEFT = arrayOf(
        0 to 8, 1 to 8, 2 to 8, 3 to 8, 4 to 8, 5 to 8, 7 to 8, 8 to 8,
        8 to 7, 8 to 5, 8 to 4, 8 to 3, 8 to 2, 8 to 1, 8 to 0
    )

    private fun selectVersion(byteCount: Int): Int {
        for (v in 1..6) {
            if (byteCount <= DATA_CODEWORDS_M[v] - 2) { // 2 bytes for byte header & count
                return v
            }
        }
        return 6
    }

    private val DATA_CODEWORDS_M = intArrayOf(0, 16, 28, 44, 64, 86, 108)
    private val TOTAL_CODEWORDS = intArrayOf(0, 26, 44, 70, 100, 134, 172)
    private val ALIGNMENT_PATTERN_POS = arrayOf(
        intArrayOf(),
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34)
    )

    // Reed-Solomon Error Correction Code Implementation
    private fun computeReedSolomon(data: ByteArray, eccCount: Int): ByteArray {
        val gen = makeGenerator(eccCount)
        val remainder = IntArray(eccCount)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor remainder[0]
            System.arraycopy(remainder, 1, remainder, 0, eccCount - 1)
            remainder[eccCount - 1] = 0
            for (j in 0 until eccCount) {
                remainder[j] = remainder[j] xor gfMultiply(gen[j], factor)
            }
        }
        val out = ByteArray(eccCount)
        for (i in 0 until eccCount) {
            out[i] = remainder[i].toByte()
        }
        return out
    }

    private fun makeGenerator(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            val root = GF_EXP[i]
            for (j in poly.indices) {
                next[j] = next[j] xor gfMultiply(poly[j], root)
                next[j + 1] = next[j + 1] xor poly[j]
            }
            poly = next
        }
        return poly.copyOfRange(0, degree)
    }

    private fun gfMultiply(x: Int, y: Int): Int {
        if (x == 0 || y == 0) return 0
        return GF_EXP[(GF_LOG[x] + GF_LOG[y]) % 255]
    }

    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            GF_EXP[i] = x
            GF_EXP[i + 255] = x
            GF_LOG[x] = i
            x = (x shl 1)
            if (x >= 256) x = x xor 0x11D
        }
    }

    private class BitBuffer {
        private val bytes = ByteArrayOutputStream()
        var bitLength = 0
            private set

        val byteLength: Int
            get() = (bitLength + 7) / 8

        private var currentByte = 0
        private var numBitsInCurrent = 0

        fun appendBits(value: Int, numBits: Int) {
            for (i in numBits - 1 downTo 0) {
                val bit = (value ushr i) and 1
                currentByte = (currentByte shl 1) or bit
                numBitsInCurrent++
                if (numBitsInCurrent == 8) {
                    bytes.write(currentByte)
                    currentByte = 0
                    numBitsInCurrent = 0
                }
                bitLength++
            }
        }

        fun toByteArray(): ByteArray {
            if (numBitsInCurrent > 0) {
                val padded = currentByte shl (8 - numBitsInCurrent)
                bytes.write(padded)
            }
            return bytes.toByteArray()
        }
    }
}

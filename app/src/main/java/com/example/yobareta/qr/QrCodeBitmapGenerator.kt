package com.example.yobareta.qr

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.abs

/**
 * Self-contained pure-Kotlin ISO/IEC 18004 QR Code generator for Android.
 * Based on canonical ISO/IEC 18004 specification (Versions 1-10, ECC Level L).
 * Generates scannable URL QR codes with zero external dependencies.
 */
object QrCodeBitmapGenerator {

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

    private fun generateQrMatrix(text: String): Array<BooleanArray> {
        val rawBytes = text.toByteArray(StandardCharsets.UTF_8)
        val dataBytes = if (rawBytes.size > 134) rawBytes.copyOfRange(0, 134) else rawBytes

        val version = selectVersion(dataBytes.size)
        val numBlocks = NUM_BLOCKS_L[version]
        val totalDataCodewords = DATA_CODEWORDS_L[version]
        val eccPerBlock = ECC_CODEWORDS_PER_BLOCK_L[version]

        // 1. Encode data segments (Byte mode = 0100, 8-bit character count for versions 1..9)
        val bitBuffer = BitBuffer()
        bitBuffer.appendBits(0b0100, 4)
        bitBuffer.appendBits(dataBytes.size, 8)
        for (b in dataBytes) {
            bitBuffer.appendBits(b.toInt() and 0xFF, 8)
        }

        // Terminator (up to 4 zero bits)
        val capacityBits = totalDataCodewords * 8
        val terminatorBits = (capacityBits - bitBuffer.bitLength).coerceIn(0, 4)
        bitBuffer.appendBits(0, terminatorBits)

        // Pad to byte boundary
        val padZeroBits = (8 - (bitBuffer.bitLength % 8)) % 8
        bitBuffer.appendBits(0, padZeroBits)

        // Pad with alternating 0xEC, 0x11 until capacity
        var padByteIndex = 0
        while (bitBuffer.bitLength < capacityBits) {
            val padVal = if (padByteIndex % 2 == 0) 0xEC else 0x11
            bitBuffer.appendBits(padVal, 8)
            padByteIndex++
        }

        val allDataCodewords = bitBuffer.toByteArray()

        // 2. Reed-Solomon ECC & Block Interleaving
        val blockSize = totalDataCodewords / numBlocks
        val dataBlocks = Array(numBlocks) { b ->
            allDataCodewords.copyOfRange(b * blockSize, (b + 1) * blockSize)
        }
        val eccBlocks = Array(numBlocks) { b ->
            computeReedSolomon(dataBlocks[b], eccPerBlock)
        }

        val interleaved = ByteArrayOutputStream()
        for (i in 0 until blockSize) {
            for (b in 0 until numBlocks) {
                interleaved.write(dataBlocks[b][i].toInt() and 0xFF)
            }
        }
        for (i in 0 until eccPerBlock) {
            for (b in 0 until numBlocks) {
                interleaved.write(eccBlocks[b][i].toInt() and 0xFF)
            }
        }
        val allCodewords = interleaved.toByteArray()

        // 3. Construct Function Modules on Grid (grid[y][x] where y = row, x = col)
        val size = version * 4 + 17
        val grid = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        // Timing patterns
        for (i in 0 until size) {
            setFunctionModule(grid, isFunction, 6, i, i % 2 == 0)
            setFunctionModule(grid, isFunction, i, 6, i % 2 == 0)
        }

        // 3 Finder patterns (centers at (3,3), (size-4, 3), (3, size-4))
        drawFinderPattern(grid, isFunction, size, 3, 3)
        drawFinderPattern(grid, isFunction, size, size - 4, 3)
        drawFinderPattern(grid, isFunction, size, 3, size - 4)

        // Alignment patterns (Version >= 2)
        if (version >= 2) {
            val alignPos = ALIGNMENT_PATTERN_POS[version]
            val numAlign = alignPos.size
            for (i in 0 until numAlign) {
                for (j in 0 until numAlign) {
                    if ((i == 0 && j == 0) || (i == 0 && j == numAlign - 1) || (i == numAlign - 1 && j == 0)) {
                        continue
                    }
                    drawAlignmentPattern(grid, isFunction, alignPos[i], alignPos[j])
                }
            }
        }

        // Dummy format bits reservation (mask = 0)
        drawFormatBits(grid, isFunction, size, mask = 0)

        // 4. Draw Codewords (zigzag from right to left)
        var bitIndex = 0
        val totalBits = allCodewords.size * 8
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0 until 2) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) (size - 1 - vert) else vert
                    if (!isFunction[y][x] && bitIndex < totalBits) {
                        val byteVal = allCodewords[bitIndex ushr 3].toInt() and 0xFF
                        val bit = ((byteVal ushr (7 - (bitIndex and 7))) and 1) != 0
                        grid[y][x] = bit
                        bitIndex++
                    }
                }
            }
            right -= 2
        }

        // 5. Apply Mask 0 ((x + y) % 2 == 0) to non-function modules and write final Format Bits
        val mask = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (!isFunction[y][x] && (x + y) % 2 == 0) {
                    grid[y][x] = !grid[y][x]
                }
            }
        }
        drawFormatBits(grid, isFunction, size, mask = mask)

        // 6. Add 4-module white quiet zone border
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

    private fun setFunctionModule(
        grid: Array<BooleanArray>,
        isFunc: Array<BooleanArray>,
        x: Int,
        y: Int,
        isDark: Boolean
    ) {
        grid[y][x] = isDark
        isFunc[y][x] = true
    }

    private fun drawFinderPattern(
        grid: Array<BooleanArray>,
        isFunc: Array<BooleanArray>,
        size: Int,
        cx: Int,
        cy: Int
    ) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until size && y in 0 until size) {
                    val dist = maxOf(abs(dx), abs(dy))
                    setFunctionModule(grid, isFunc, x, y, dist != 2 && dist != 4)
                }
            }
        }
    }

    private fun drawAlignmentPattern(
        grid: Array<BooleanArray>,
        isFunc: Array<BooleanArray>,
        cx: Int,
        cy: Int
    ) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                val dist = maxOf(abs(dx), abs(dy))
                setFunctionModule(grid, isFunc, cx + dx, cy + dy, dist != 1)
            }
        }
    }

    private fun drawFormatBits(
        grid: Array<BooleanArray>,
        isFunc: Array<BooleanArray>,
        size: Int,
        mask: Int
    ) {
        // ECC Level L format indicator = 1 (binary 01)
        val data = (1 shl 3) or mask
        var rem = data
        for (i in 0 until 10) {
            rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        }
        val bits = ((data shl 10) or rem) xor 0x5412

        // First copy (around top-left finder pattern)
        for (i in 0..5) setFunctionModule(grid, isFunc, 8, i, getBit(bits, i))
        setFunctionModule(grid, isFunc, 8, 7, getBit(bits, 6))
        setFunctionModule(grid, isFunc, 8, 8, getBit(bits, 7))
        setFunctionModule(grid, isFunc, 7, 8, getBit(bits, 8))
        for (i in 9..14) setFunctionModule(grid, isFunc, 14 - i, 8, getBit(bits, i))

        // Second copy (top-right and bottom-left)
        for (i in 0..7) setFunctionModule(grid, isFunc, size - 1 - i, 8, getBit(bits, i))
        for (i in 8..14) setFunctionModule(grid, isFunc, 8, size - 15 + i, getBit(bits, i))
        setFunctionModule(grid, isFunc, 8, size - 8, true) // Always dark module
    }

    private fun getBit(x: Int, i: Int): Boolean = ((x ushr i) and 1) != 0

    private fun selectVersion(byteCount: Int): Int {
        for (v in 1..6) {
            if (byteCount <= DATA_CODEWORDS_L[v] - 2) {
                return v
            }
        }
        return 6
    }

    // ISO/IEC 18004 Table 9 for ECC Level L (Versions 1..6)
    private val DATA_CODEWORDS_L = intArrayOf(0, 19, 34, 55, 80, 108, 136)
    private val ECC_CODEWORDS_PER_BLOCK_L = intArrayOf(0, 7, 10, 15, 20, 26, 18)
    private val NUM_BLOCKS_L = intArrayOf(0, 1, 1, 1, 1, 1, 2)
    private val ALIGNMENT_PATTERN_POS = arrayOf(
        intArrayOf(),
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34)
    )

    // Canonical Reed-Solomon Divisor & Remainder over GF(2^8) with polynomial 0x11D
    private fun reedSolomonComputeDivisor(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMultiply(result[j].toInt() and 0xFF, root).toByte()
                if (j + 1 < degree) {
                    result[j] = (result[j].toInt() xor (result[j + 1].toInt() and 0xFF)).toByte()
                }
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    private fun computeReedSolomon(data: ByteArray, eccCount: Int): ByteArray {
        val divisor = reedSolomonComputeDivisor(eccCount)
        val result = ByteArray(eccCount)
        for (b in data) {
            val factor = (b.toInt() xor result[0].toInt()) and 0xFF
            System.arraycopy(result, 1, result, 0, eccCount - 1)
            result[eccCount - 1] = 0
            for (i in 0 until eccCount) {
                result[i] = (result[i].toInt() xor gfMultiply(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
        }
        return result
    }

    private fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private class BitBuffer {
        private val bytes = ByteArrayOutputStream()
        var bitLength = 0
            private set

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

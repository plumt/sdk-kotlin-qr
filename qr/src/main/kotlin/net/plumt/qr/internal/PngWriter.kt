package net.plumt.qr.internal

import net.plumt.qr.CodeMatrix
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** [CodeMatrix] → 1비트 흑백 PNG(PNG 명세 1.2). 어두운 칸이 0(검정), 밝은 칸이 1(흰색)이다. */
internal object PngWriter {

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    fun write(matrix: CodeMatrix): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val header = ByteArrayOutputStream().also { buf ->
            DataOutputStream(buf).apply {
                writeInt(matrix.width)
                writeInt(matrix.height)
                writeByte(1) // 비트 깊이
                writeByte(0) // 흑백
                writeByte(0) // 압축: deflate
                writeByte(0) // 필터: 적응형
                writeByte(0) // 비월 없음
            }
        }.toByteArray()
        chunk(out, "IHDR", header)
        chunk(out, "IDAT", imageData(matrix))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun imageData(matrix: CodeMatrix): ByteArray {
        val rowBytes = (matrix.width + 7) / 8
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw).use { deflate ->
            val line = ByteArray(rowBytes + 1)
            for (y in 0 until matrix.height) {
                line.fill(0)
                val row = matrix.row(y)
                for (x in row.indices) {
                    if (!row[x]) line[1 + x / 8] = (line[1 + x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
                }
                deflate.write(line)
            }
        }
        return raw.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        DataOutputStream(out).apply {
            writeInt(data.size)
            write(typeBytes)
            write(data)
            writeInt(crc.value.toInt())
        }
    }
}

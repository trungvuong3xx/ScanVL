package com.scanvl.app.pdf

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream

/**
 * Đọc kích thước + số kênh màu của JPEG chỉ từ phần header (vài trăm byte đầu),
 * không giải mã ảnh -> gần như tức thì và không tốn RAM.
 */
data class JpegInfo(val width: Int, val height: Int, val components: Int) {

    companion object {
        fun read(file: File): JpegInfo? = try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 8 * 1024)).use { input ->
                if (input.readUnsignedByte() != 0xFF || input.readUnsignedByte() != 0xD8) return null
                while (true) {
                    var b = input.readUnsignedByte()
                    if (b != 0xFF) continue
                    // Bỏ qua các byte đệm 0xFF liên tiếp
                    do { b = input.readUnsignedByte() } while (b == 0xFF)
                    val marker = b
                    when {
                        // Marker không có phần độ dài
                        marker == 0x01 || marker in 0xD0..0xD8 -> continue
                        // Hết ảnh / bắt đầu dữ liệu nén mà chưa gặp SOF -> file lỗi
                        marker == 0xD9 || marker == 0xDA -> return null
                        // SOF0..SOF15 (trừ DHT C4, JPG C8, DAC CC)
                        marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> {
                            input.readUnsignedShort() // length
                            input.readUnsignedByte()  // precision
                            val height = input.readUnsignedShort()
                            val width = input.readUnsignedShort()
                            val components = input.readUnsignedByte()
                            return if (width > 0 && height > 0) JpegInfo(width, height, components) else null
                        }
                        else -> {
                            val length = input.readUnsignedShort()
                            skipFully(input, length - 2)
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        } catch (e: EOFException) {
            null
        } catch (e: Exception) {
            null
        }

        private fun skipFully(input: DataInputStream, count: Int) {
            var remaining = count
            while (remaining > 0) {
                val skipped = input.skipBytes(remaining)
                if (skipped <= 0) {
                    input.readUnsignedByte()
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
        }
    }
}

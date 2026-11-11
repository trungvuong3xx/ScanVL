package com.scanvl.app.pdf

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Bộ ghi PDF tốc độ cao:
 * - Nhúng NGUYÊN byte JPEG vào PDF (bộ lọc /DCTDecode) -> không giải mã, không nén lại,
 *   chất lượng ảnh giữ nguyên 100%.
 * - Ghi dạng stream qua buffer 64 KB -> RAM dùng gần như cố định dù 10 hay 100 trang,
 *   không thể OutOfMemoryError.
 * - Xoay trang bằng thuộc tính /Rotate của PDF -> không xử lý pixel.
 *
 * 10 trang (~5 MB) chỉ tốn thời gian copy file: thường < 100 ms.
 */
object FastPdfWriter {

    /** Chiều rộng trang PDF = khổ A4 (595.28pt), chiều cao theo tỉ lệ ảnh. */
    private const val PAGE_WIDTH_PT = 595.28f
    private const val BUFFER_SIZE = 64 * 1024

    data class PageInput(val jpeg: File, val rotation: Int)

    /** @return số trang đã ghi thành công. */
    fun write(output: File, pages: List<PageInput>): Int {
        // Đọc header trước, bỏ qua trang hỏng để PDF luôn hợp lệ
        val valid = pages.mapNotNull { page ->
            JpegInfo.read(page.jpeg)?.let { info -> page to info }
        }
        if (valid.isEmpty()) return 0

        val objectCount = 2 + valid.size * 3
        val offsets = LongArray(objectCount + 1)

        CountingOutputStream(BufferedOutputStream(FileOutputStream(output), BUFFER_SIZE)).use { out ->
            out.writeAscii("%PDF-1.4\n")
            out.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

            offsets[1] = out.count
            out.writeAscii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

            offsets[2] = out.count
            val kids = valid.indices.joinToString(" ") { i -> "${3 + i * 3} 0 R" }
            out.writeAscii("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count ${valid.size} >>\nendobj\n")

            val copyBuffer = ByteArray(BUFFER_SIZE)
            valid.forEachIndexed { index, (page, info) ->
                val pageObj = 3 + index * 3
                val contentObj = pageObj + 1
                val imageObj = pageObj + 2

                val w = PAGE_WIDTH_PT
                val h = PAGE_WIDTH_PT * info.height / info.width
                val ws = fmt(w)
                val hs = fmt(h)
                val rotate = ((page.rotation % 360) + 360) % 360

                // Page
                offsets[pageObj] = out.count
                out.writeAscii(
                    "$pageObj 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $ws $hs] /Rotate $rotate " +
                        "/Resources << /XObject << /Im0 $imageObj 0 R >> >> /Contents $contentObj 0 R >>\nendobj\n"
                )

                // Content stream: vẽ ảnh phủ kín trang
                val content = "q $ws 0 0 $hs 0 0 cm /Im0 Do Q"
                offsets[contentObj] = out.count
                out.writeAscii(
                    "$contentObj 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n"
                )

                // Image XObject: byte JPEG gốc
                val colorSpace = when (info.components) {
                    1 -> "/DeviceGray"
                    4 -> "/DeviceCMYK /Decode [1 0 1 0 1 0 1 0]"
                    else -> "/DeviceRGB"
                }
                offsets[imageObj] = out.count
                out.writeAscii(
                    "$imageObj 0 obj\n<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                        "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode " +
                        "/Length ${page.jpeg.length()} >>\nstream\n"
                )
                FileInputStream(page.jpeg).use { input ->
                    while (true) {
                        val read = input.read(copyBuffer)
                        if (read < 0) break
                        out.write(copyBuffer, 0, read)
                    }
                }
                out.writeAscii("\nendstream\nendobj\n")
            }

            // Bảng xref: mỗi dòng đúng 20 byte
            val xrefOffset = out.count
            val xref = StringBuilder(objectCount * 20 + 64)
            xref.append("xref\n0 ").append(objectCount + 1).append('\n')
            xref.append("0000000000 65535 f \n")
            for (i in 1..objectCount) {
                xref.append(String.format(Locale.US, "%010d 00000 n \n", offsets[i]))
            }
            xref.append("trailer\n<< /Size ").append(objectCount + 1).append(" /Root 1 0 R >>\n")
            xref.append("startxref\n").append(xrefOffset).append("\n%%EOF\n")
            out.writeAscii(xref.toString())
        }
        return valid.size
    }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.2f", value)

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        fun writeAscii(text: String) {
            write(text.toByteArray(Charsets.ISO_8859_1))
        }
    }
}

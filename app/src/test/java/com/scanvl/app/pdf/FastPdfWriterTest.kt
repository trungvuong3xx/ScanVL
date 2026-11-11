package com.scanvl.app.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Fixture: page_a4.jpg = ảnh tài liệu 2480x3508 (A4 @300dpi), ~1.4 MB – nặng hơn ảnh ML Kit thực tế.
 */
class FastPdfWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(name: String): File {
        val out = tmp.newFile(name)
        javaClass.classLoader!!.getResourceAsStream("page_a4.jpg")!!.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    @Test
    fun jpegInfo_readsHeaderOnly() {
        val info = JpegInfo.read(fixture("a.jpg"))
        assertNotNull(info)
        assertEquals(2480, info!!.width)
        assertEquals(3508, info.height)
        assertEquals(3, info.components)
    }

    @Test
    fun tenPages_validPdf_under1s() {
        val pages = (1..10).map { i ->
            FastPdfWriter.PageInput(fixture("p$i.jpg"), rotation = if (i == 3) 90 else 0)
        }
        val out = File(tmp.root, "out.pdf")

        FastPdfWriter.write(out, pages) // warm-up JIT
        val start = System.nanoTime()
        val written = FastPdfWriter.write(out, pages)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("FastPdfWriter: 10 trang, ${out.length() / 1024} KB trong $ms ms")

        assertEquals(10, written)
        assertTrue("Phải < 1000 ms, thực tế $ms ms", ms < 1000)

        // Mở lại bằng PDFBox để chắc chắn PDF đúng chuẩn
        PDDocument.load(out).use { doc ->
            assertEquals(10, doc.numberOfPages)
            assertEquals(0, doc.getPage(0).rotation)
            assertEquals(90, doc.getPage(2).rotation)
            val box = doc.getPage(0).mediaBox
            assertEquals(595.28f, box.width, 0.01f)
            assertEquals(595.28f * 3508 / 2480, box.height, 0.01f)
        }
    }

    @Test
    fun corruptPage_isSkipped() {
        val good = fixture("ok.jpg")
        val bad = tmp.newFile("bad.jpg").apply { writeText("not a jpeg") }
        val out = File(tmp.root, "mixed.pdf")
        val written = FastPdfWriter.write(
            out,
            listOf(FastPdfWriter.PageInput(good, 0), FastPdfWriter.PageInput(bad, 0))
        )
        assertEquals(1, written)
        PDDocument.load(out).use { assertEquals(1, it.numberOfPages) }
    }
}

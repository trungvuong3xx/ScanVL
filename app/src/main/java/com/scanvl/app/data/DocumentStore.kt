package com.scanvl.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.util.AtomicFile
import com.scanvl.app.pdf.FastPdfWriter
import com.scanvl.app.pdf.JpegInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Lưu trữ tài liệu trong bộ nhớ riêng của app (Scoped Storage, không cần quyền):
 *   files/docs/<docId>/<pageId>.jpg   ảnh từng trang
 *   files/docs_index.json             danh sách tài liệu (ghi nguyên tử, không hỏng khi tắt đột ngột)
 *   cache/share/<thời gian>/          file PDF / ảnh tạm để gửi
 */
class DocumentStore(context: Context) {

    private val appContext = context.applicationContext
    private val docsRoot = File(appContext.filesDir, "docs")
    private val shareRoot = File(appContext.cacheDir, "share")
    private val indexFile = AtomicFile(File(appContext.filesDir, "docs_index.json"))
    private val prefs = appContext.getSharedPreferences("scanvl_prefs", Context.MODE_PRIVATE)

    /**
     * Giới hạn số ảnh xử lý song song: đủ nhanh trên máy nhiều nhân
     * nhưng chặn được việc giải mã quá nhiều bitmap cùng lúc (nguyên nhân OOM).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val imageDispatcher = Dispatchers.Default.limitedParallelism(
        Runtime.getRuntime().availableProcessors().coerceIn(2, 3)
    )

    fun pageFile(docId: String, page: ScanPage): File = File(File(docsRoot, docId), page.fileName)

    // ---------------------------------------------------------------------
    // Tùy chọn người dùng (Mở thẳng camera, mức nén, âm thanh, tự cắt)
    // ---------------------------------------------------------------------
    var autoLaunchCamera: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAMERA, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CAMERA, value).apply()

    var isShutterSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHUTTER_SOUND, false)
        set(value) = prefs.edit().putBoolean(KEY_SHUTTER_SOUND, value).apply()

    var isAutoCropEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CROP, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CROP, value).apply()

    var exportQuality: ExportQuality
        get() = prefs.getString(KEY_QUALITY, null)?.let { name ->
            ExportQuality.entries.firstOrNull { it.name == name }
        } ?: ExportQuality.BALANCED
        set(value) = prefs.edit().putString(KEY_QUALITY, value.name).apply()

    // ---------------------------------------------------------------------
    // Index (JSON thuần, không dùng reflection -> khởi động nhanh, an toàn với R8)
    // ---------------------------------------------------------------------
    suspend fun load(): List<ScanDoc> = withContext(Dispatchers.IO) {
        try {
            if (!indexFile.baseFile.exists()) return@withContext emptyList()
            val now = System.currentTimeMillis()
            val array = JSONArray(String(indexFile.readFully(), Charsets.UTF_8))
            val loaded = ArrayList<ScanDoc>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val deletedAt = o.optLong("deletedAt", 0L).takeIf { it > 0L }
                // Tự động dọn các tài liệu trong Thùng rác đã quá 30 ngày
                if (deletedAt != null && now - deletedAt > TRASH_RETENTION_MS) continue
                val pagesJson = o.getJSONArray("pages")
                loaded.add(
                    ScanDoc(
                        id = o.getString("id"),
                        title = o.getString("title"),
                        createdAt = o.getLong("createdAt"),
                        pages = List(pagesJson.length()) { j ->
                            val p = pagesJson.getJSONObject(j)
                            ScanPage(
                                id = p.getString("id"),
                                fileName = p.getString("file"),
                                rotation = p.optInt("rot", 0)
                            )
                        },
                        deletedAt = deletedAt
                    )
                )
            }
            loaded
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Gọi trên luồng IO. Đồng bộ hóa để 2 lần ghi không bao giờ chồng lên nhau. */
    @Synchronized
    fun save(docs: List<ScanDoc>) {
        val array = JSONArray()
        docs.forEach { doc ->
            val pages = JSONArray()
            doc.pages.forEach { p ->
                pages.put(JSONObject().put("id", p.id).put("file", p.fileName).put("rot", p.rotation))
            }
            val obj = JSONObject()
                .put("id", doc.id)
                .put("title", doc.title)
                .put("createdAt", doc.createdAt)
                .put("pages", pages)
            if (doc.deletedAt != null) obj.put("deletedAt", doc.deletedAt)
            array.put(obj)
        }
        val stream = indexFile.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            indexFile.finishWrite(stream)
        } catch (e: Exception) {
            indexFile.failWrite(stream)
            e.printStackTrace()
        }
    }

    // ---------------------------------------------------------------------
    // Nhập ảnh từ Camera tùy biến hoặc ML Kit
    // ---------------------------------------------------------------------
    suspend fun importCapturedPhotos(docId: String, photos: List<CapturedPhoto>): List<ScanPage> = coroutineScope {
        val dir = File(docsRoot, docId).apply { mkdirs() }
        photos.map { photo ->
            async(imageDispatcher) {
                val pageId = UUID.randomUUID().toString().substring(0, 12)
                val dest = File(dir, "$pageId.jpg")
                if (importOne(Uri.fromFile(photo.file), dest)) {
                    // Dọn dẹp file tạm trong cache
                    photo.file.delete()
                    ScanPage(id = pageId, fileName = dest.name, rotation = photo.rotation)
                } else null
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun importPages(docId: String, uris: List<Uri>): List<ScanPage> = coroutineScope {
        val dir = File(docsRoot, docId).apply { mkdirs() }
        uris.map { uri ->
            async(imageDispatcher) {
                val pageId = UUID.randomUUID().toString().substring(0, 12)
                val dest = File(dir, "$pageId.jpg")
                if (importOne(uri, dest)) ScanPage(id = pageId, fileName = dest.name) else null
            }
        }.awaitAll().filterNotNull()
    }

    private fun importOne(uri: Uri, dest: File): Boolean {
        return try {
            val path = uri.path
            val moved = uri.scheme == "file" && path != null && File(path).renameTo(dest)
            if (!moved) {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output, 64 * 1024) }
                } ?: return false
            }

            // Giới hạn ảnh lưu gốc ở mức HIGH (2560px) để vừa nét vừa không phình bộ nhớ
            val info = JpegInfo.read(dest) ?: return dest.exists()
            if (maxOf(info.width, info.height) > ExportQuality.HIGH.maxEdgePx) {
                val tmp = File(dest.parentFile, dest.name + ".tmp")
                if (reencodePage(dest, tmp, info, ExportQuality.HIGH.maxEdgePx, 0, ExportQuality.HIGH.jpegQuality) && !tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                }
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ---------------------------------------------------------------------
    // Xuất file để gửi (hỗ trợ 3 mức dung lượng: Nhỏ / Cân bằng / Cao)
    // ---------------------------------------------------------------------
    private fun newShareDir(): File {
        shareRoot.listFiles()?.forEach { it.deleteRecursively() }
        return File(shareRoot, System.currentTimeMillis().toString()).apply { mkdirs() }
    }

    suspend fun exportPdf(doc: ScanDoc, quality: ExportQuality): File? = withContext(Dispatchers.IO) {
        val dir = newShareDir()
        val file = File(dir, safeFileName(doc.title) + ".pdf")

        // Chuẩn bị từng trang theo mức chất lượng đã chọn (song song, giới hạn RAM)
        val inputs = coroutineScope {
            doc.pages.mapIndexed { idx, page ->
                async(imageDispatcher) {
                    val src = pageFile(doc.id, page)
                    if (quality == ExportQuality.HIGH) {
                        // Mức Cao: nhúng thẳng byte JPEG gốc, không giải mã lại (~14ms cho 10 trang)
                        FastPdfWriter.PageInput(src, page.rotation)
                    } else {
                        val info = JpegInfo.read(src)
                        val needsScale = info != null && maxOf(info.width, info.height) > quality.maxEdgePx
                        if (!needsScale && src.length() <= 380 * 1024) {
                            FastPdfWriter.PageInput(src, page.rotation)
                        } else if (info != null) {
                            val scaled = File(dir, "_p$idx.jpg")
                            if (reencodePage(src, scaled, info, quality.maxEdgePx, 0, quality.jpegQuality)) {
                                FastPdfWriter.PageInput(scaled, page.rotation)
                            } else {
                                FastPdfWriter.PageInput(src, page.rotation)
                            }
                        } else {
                            FastPdfWriter.PageInput(src, page.rotation)
                        }
                    }
                }
            }.awaitAll()
        }

        val written = FastPdfWriter.write(file, inputs)
        // Xóa các file JPEG trung gian nếu có
        dir.listFiles()?.forEach { if (it.name.startsWith("_p")) it.delete() }
        if (written > 0) file else null
    }

    suspend fun exportImages(doc: ScanDoc, quality: ExportQuality): List<File> = withContext(Dispatchers.IO) {
        val dir = newShareDir()
        val base = safeFileName(doc.title)
        val digits = doc.pages.size.toString().length.coerceAtLeast(2)
        coroutineScope {
            doc.pages.mapIndexed { index, page ->
                async(imageDispatcher) {
                    val src = pageFile(doc.id, page)
                    val name = if (doc.pages.size == 1) "$base.jpg"
                    else "${base}_${(index + 1).toString().padStart(digits, '0')}.jpg"
                    val dest = File(dir, name)
                    val rot = ((page.rotation % 360) + 360) % 360
                    val info = JpegInfo.read(src)
                    val needsScale = quality != ExportQuality.HIGH && info != null && maxOf(info.width, info.height) > quality.maxEdgePx
                    val ok = try {
                        if (rot == 0 && !needsScale) {
                            src.copyTo(dest, overwrite = true); true
                        } else if (info != null) {
                            reencodePage(src, dest, info, quality.maxEdgePx, rot, quality.jpegQuality)
                        } else {
                            src.copyTo(dest, overwrite = true); true
                        }
                    } catch (e: Exception) {
                        e.printStackTrace(); false
                    }
                    if (ok) dest else null
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Giải mã + thu nhỏ ngay trong decoder (inSampleSize + inDensity) + xoay nếu cần.
     * Không bao giờ nạp bitmap full-resolution vào RAM khi thu nhỏ.
     */
    private fun reencodePage(
        src: File,
        dest: File,
        info: JpegInfo,
        maxEdge: Int,
        rotation: Int,
        jpegQuality: Int
    ): Boolean {
        val longEdge = maxOf(info.width, info.height)
        var sample = 1
        while (longEdge / (sample * 2) >= maxEdge) sample *= 2
        val sampledEdge = longEdge / sample
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            if (sampledEdge > maxEdge) {
                inScaled = true
                inDensity = sampledEdge
                inTargetDensity = maxEdge
            }
        }
        val decoded = BitmapFactory.decodeFile(src.absolutePath, options) ?: return false
        val finalBitmap = if (rotation % 360 != 0) {
            val rotated = Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true
            )
            if (rotated !== decoded) decoded.recycle()
            rotated
        } else {
            decoded
        }
        return try {
            FileOutputStream(dest).buffered(64 * 1024).use {
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, it)
            }
        } finally {
            finalBitmap.recycle()
        }
    }

    // ---------------------------------------------------------------------
    // Dọn file mồ côi (trang/tài liệu đã xóa vĩnh viễn) – chạy nền lúc khởi động
    // ---------------------------------------------------------------------
    suspend fun sweepOrphans(docs: List<ScanDoc>, startedAt: Long) = withContext(Dispatchers.IO) {
        val byId = docs.associateBy { it.id }
        docsRoot.listFiles()?.forEach { dir ->
            val doc = byId[dir.name]
            if (doc == null) {
                if (dir.lastModified() < startedAt) dir.deleteRecursively()
            } else {
                val keep = doc.pages.mapTo(HashSet()) { it.fileName }
                dir.listFiles()?.forEach { f ->
                    if (f.name !in keep && f.lastModified() < startedAt) f.delete()
                }
            }
        }
        shareRoot.listFiles()?.forEach { if (it.lastModified() < startedAt) it.deleteRecursively() }
    }

    suspend fun deleteDocFiles(docId: String) = withContext(Dispatchers.IO) {
        File(docsRoot, docId).deleteRecursively()
    }

    companion object {
        const val TRASH_RETENTION_DAYS = 30
        const val TRASH_RETENTION_MS = TRASH_RETENTION_DAYS * 24L * 3600L * 1000L
        private const val KEY_AUTO_CAMERA = "auto_launch_camera"
        private const val KEY_SHUTTER_SOUND = "shutter_sound_enabled"
        private const val KEY_AUTO_CROP = "auto_crop_enabled"
        private const val KEY_QUALITY = "export_quality"

        fun safeFileName(title: String): String =
            title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().trim('.').ifEmpty { "ScanVL" }.take(80)
    }
}

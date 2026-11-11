package com.scanvl.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scanvl.app.data.DocumentStore
import com.scanvl.app.data.ExportQuality
import com.scanvl.app.data.ScanDoc
import com.scanvl.app.data.ScanPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Toàn bộ thao tác dữ liệu chạy nền; UI chỉ đọc state -> UI thread không bao giờ bị chặn.
 * Mọi thay đổi cập nhật state NGAY (UI phản hồi tức thì), việc ghi đĩa được gom lại (debounce).
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val store = DocumentStore(app)

    var docs by mutableStateOf<List<ScanDoc>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    /** Tài liệu đang mở trong màn hình Xem/Sửa (null = màn hình chính). */
    var openDocId by mutableStateOf<String?>(null)
        private set
    /** Thông báo tiến trình ngắn (null = rảnh). */
    var busy by mutableStateOf<String?>(null)
        private set

    var searchQuery by mutableStateOf("")
    var exportQuality by mutableStateOf(store.exportQuality)
        private set
    var autoLaunchCamera by mutableStateOf(store.autoLaunchCamera)
        private set

    // Lịch sử Undo/Redo cho tài liệu đang mở
    private val undoStack = mutableStateListOf<ScanDoc>()
    private val redoStack = mutableStateListOf<ScanDoc>()
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private var saveJob: Job? = null

    init {
        val startedAt = System.currentTimeMillis()
        viewModelScope.launch {
            docs = store.load()
            loaded = true
            store.sweepOrphans(docs, startedAt)
        }
    }

    fun selectDoc(id: String?) {
        if (openDocId != id) {
            undoStack.clear()
            redoStack.clear()
            openDocId = id
        }
    }

    fun doc(id: String?): ScanDoc? = id?.let { key -> docs.firstOrNull { it.id == key } }

    fun updateQuality(quality: ExportQuality) {
        exportQuality = quality
        store.exportQuality = quality
    }

    fun toggleAutoLaunchCamera() {
        val next = !autoLaunchCamera
        autoLaunchCamera = next
        store.autoLaunchCamera = next
    }

    // ------------------------------------------------------------------
    // Nhận kết quả quét
    // ------------------------------------------------------------------
    /** [targetDocId] = null -> tạo tài liệu mới; khác null -> thêm trang vào tài liệu đó. */
    fun importScan(uris: List<Uri>, targetDocId: String?) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy = "Đang lưu ${uris.size} trang…"
            val existing = doc(targetDocId)
            val docId = existing?.id ?: UUID.randomUUID().toString()
            val pages = store.importPages(docId, uris)
            if (pages.isNotEmpty()) {
                if (existing != null) {
                    editDoc(docId) { it.copy(pages = it.pages + pages) }
                } else {
                    val title = "Scan " + SimpleDateFormat("dd-MM HH'h'mm", Locale.getDefault()).format(Date())
                    docs = listOf(ScanDoc(docId, title, System.currentTimeMillis(), pages)) + docs
                    persist(immediate = true)
                }
                // Bước 2 trong luồng 3 bước: Mở thẳng màn hình Xem lại / Gửi ngay sau khi quét
                selectDoc(docId)
            }
            busy = null
        }
    }

    // ------------------------------------------------------------------
    // Sửa tài liệu (tự động đẩy vào lịch sử Undo/Redo)
    // ------------------------------------------------------------------
    fun rename(docId: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        editDoc(docId) { it.copy(title = clean) }
    }

    fun rotatePage(docId: String, pageId: String) = editDoc(docId) { doc ->
        doc.copy(pages = doc.pages.map { if (it.id == pageId) it.copy(rotation = (it.rotation + 90) % 360) else it })
    }

    fun movePage(docId: String, from: Int, to: Int) = editDoc(docId, debounceMs = 400, recordUndo = false) { doc ->
        if (from !in doc.pages.indices || to !in doc.pages.indices || from == to) doc
        else doc.copy(pages = doc.pages.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun deletePage(docId: String, pageId: String): Pair<Int, ScanPage>? {
        val d = doc(docId) ?: return null
        val index = d.pages.indexOfFirst { it.id == pageId }
        if (index < 0) return null
        val page = d.pages[index]
        editDoc(docId) { it.copy(pages = it.pages.filterNot { p -> p.id == pageId }) }
        return index to page
    }

    fun restorePage(docId: String, index: Int, page: ScanPage) = editDoc(docId, recordUndo = false) { d ->
        if (d.pages.any { it.id == page.id }) d
        else d.copy(pages = d.pages.toMutableList().apply { add(index.coerceIn(0, size), page) })
    }

    fun undo() {
        val id = openDocId ?: return
        val current = doc(id) ?: return
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.add(current)
        replaceDoc(id, prev)
    }

    fun redo() {
        val id = openDocId ?: return
        val current = doc(id) ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.add(current)
        replaceDoc(id, next)
    }

    // ------------------------------------------------------------------
    // Thùng rác 30 ngày
    // ------------------------------------------------------------------
    fun moveToTrash(docId: String): ScanDoc? {
        val target = doc(docId) ?: return null
        val now = System.currentTimeMillis()
        docs = docs.map { if (it.id == docId) it.copy(deletedAt = now) else it }
        if (openDocId == docId) selectDoc(null)
        persist(immediate = true)
        return target
    }

    fun restoreFromTrash(docId: String) {
        docs = docs.map { if (it.id == docId) it.copy(deletedAt = null) else it }
        persist(immediate = true)
    }

    fun deletePermanently(docId: String) {
        docs = docs.filterNot { it.id == docId }
        if (openDocId == docId) selectDoc(null)
        persist(immediate = true)
        viewModelScope.launch { store.deleteDocFiles(docId) }
    }

    fun emptyTrash() {
        val trashedIds = docs.filter { it.isInTrash }.map { it.id }
        if (trashedIds.isEmpty()) return
        docs = docs.filterNot { it.isInTrash }
        persist(immediate = true)
        viewModelScope.launch { trashedIds.forEach { store.deleteDocFiles(it) } }
    }

    // ------------------------------------------------------------------
    // Xuất file để gửi (chạy nền, áp dụng mức nén đã chọn)
    // ------------------------------------------------------------------
    fun exportPdf(docId: String, onReady: (File) -> Unit) {
        val d = doc(docId) ?: return
        if (d.pages.isEmpty()) return
        val q = exportQuality
        viewModelScope.launch {
            busy = "Đang tạo PDF (${q.label})…"
            val file = store.exportPdf(d, q)
            busy = null
            if (file != null) onReady(file)
        }
    }

    fun exportImages(docId: String, onReady: (List<File>) -> Unit) {
        val d = doc(docId) ?: return
        if (d.pages.isEmpty()) return
        val q = exportQuality
        viewModelScope.launch {
            busy = "Đang chuẩn bị ảnh (${q.label})…"
            val files = store.exportImages(d, q)
            busy = null
            if (files.isNotEmpty()) onReady(files)
        }
    }

    // ------------------------------------------------------------------
    private fun editDoc(
        docId: String,
        debounceMs: Long = 150,
        recordUndo: Boolean = true,
        transform: (ScanDoc) -> ScanDoc
    ) {
        val before = doc(docId) ?: return
        val after = transform(before)
        if (after == before) return
        if (recordUndo && openDocId == docId) {
            undoStack.add(before)
            if (undoStack.size > 25) undoStack.removeAt(0)
            redoStack.clear()
        }
        docs = docs.map { if (it.id == docId) after else it }
        persist(debounceMs = debounceMs)
    }

    private fun replaceDoc(docId: String, target: ScanDoc) {
        docs = docs.map { if (it.id == docId) target else it }
        persist(debounceMs = 150)
    }

    private fun persist(immediate: Boolean = false, debounceMs: Long = 150) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (!immediate) delay(debounceMs)
            val snapshot = docs
            withContext(Dispatchers.IO) { store.save(snapshot) }
        }
    }

    override fun onCleared() {
        if (loaded) store.save(docs)
        super.onCleared()
    }
}

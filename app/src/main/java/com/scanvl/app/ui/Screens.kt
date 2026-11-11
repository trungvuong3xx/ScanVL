package com.scanvl.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout. width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.scanvl.app.AppViewModel
import com.scanvl.app.ScanMode
import com.scanvl.app.data.DocumentStore
import com.scanvl.app.data.ExportQuality
import com.scanvl.app.data.ScanDoc
import com.scanvl.app.data.ScanPage
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

private enum class HomeTab { DOCS, TRASH }

// =====================================================================
// Gốc ứng dụng: Luồng 3 bước (Scan → Xem lại/chỉnh nhanh → Gửi)
// =====================================================================
@Composable
fun ScanVLApp(
    vm: AppViewModel,
    onScan: (ScanMode, String?) -> Unit,
    onSendPdf: (String) -> Unit,
    onSendImages: (String) -> Unit
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var renameTarget by remember { mutableStateOf<ScanDoc?>(null) }

    fun showUndo(message: String, onUndo: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = "Hoàn tác",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }

    val openDoc = vm.doc(vm.openDocId)?.takeIf { !it.isInTrash }
    BackHandler(enabled = openDoc != null) { vm.selectDoc(null) }

    if (openDoc == null) {
        HomeScreen(
            vm = vm,
            snackbar = snackbar,
            onScanSingle = { onScan(ScanMode.SINGLE, null) },
            onScanBatch = { onScan(ScanMode.BATCH, null) },
            onOpen = { vm.selectDoc(it.id) },
            onRename = { renameTarget = it },
            onTrash = { doc ->
                vm.moveToTrash(doc.id)?.let { trashed ->
                    showUndo("Đã chuyển \"${trashed.title}\" vào Thùng rác (30 ngày)") {
                        vm.restoreFromTrash(trashed.id)
                    }
                }
            },
            onSendPdf = { onSendPdf(it.id) },
            onSendImages = { onSendImages(it.id) }
        )
    } else {
        EditorScreen(
            doc = openDoc,
            busy = vm.busy,
            quality = vm.exportQuality,
            canUndo = vm.canUndo,
            canRedo = vm.canRedo,
            snackbar = snackbar,
            fileOf = { page -> vm.store.pageFile(openDoc.id, page) },
            onBack = { vm.selectDoc(null) },
            onRename = { renameTarget = openDoc },
            onUndo = { vm.undo() },
            onRedo = { vm.redo() },
            onQualityChange = { vm.updateQuality(it) },
            onAddPages = { onScan(ScanMode.BATCH, openDoc.id) },
            onTrashDoc = {
                vm.moveToTrash(openDoc.id)?.let { trashed ->
                    showUndo("Đã chuyển \"${trashed.title}\" vào Thùng rác") {
                        vm.restoreFromTrash(trashed.id)
                    }
                }
            },
            onRotate = { pageId -> vm.rotatePage(openDoc.id, pageId) },
            onDeletePage = { pageId ->
                vm.deletePage(openDoc.id, pageId)?.let { (index, page) ->
                    showUndo("Đã xóa trang ${index + 1}") { vm.restorePage(openDoc.id, index, page) }
                }
            },
            onMove = { from, to -> vm.movePage(openDoc.id, from, to) },
            onSendPdf = { onSendPdf(openDoc.id) },
            onSendImages = { onSendImages(openDoc.id) }
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { newTitle ->
                vm.rename(target.id, newTitle)
                renameTarget = null
            }
        )
    }
}

// =====================================================================
// MÀN HÌNH TÀI LIỆU & THÙNG RÁC 30 NGÀY
// Lưới thẻ thumbnail lớn, tìm kiếm tức thì, thanh điều hướng dưới với cụm nút Scan ở giữa
// =====================================================================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HomeScreen(
    vm: AppViewModel,
    snackbar: SnackbarHostState,
    onScanSingle: () -> Unit,
    onScanBatch: () -> Unit,
    onOpen: (ScanDoc) -> Unit,
    onRename: (ScanDoc) -> Unit,
    onTrash: (ScanDoc) -> Unit,
    onSendPdf: (ScanDoc) -> Unit,
    onSendImages: (ScanDoc) -> Unit
) {
    var tab by remember { mutableStateOf(HomeTab.DOCS) }
    val activeDocs = remember(vm.docs, vm.searchQuery) {
        val q = vm.searchQuery.trim()
        vm.docs.filter { !it.isInTrash && (q.isEmpty() || it.title.contains(q, ignoreCase = true)) }
    }
    val trashDocs = remember(vm.docs) { vm.docs.filter { it.isInTrash } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (tab == HomeTab.DOCS) "ScanVL" else "Thùng rác (30 ngày)",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    if (tab == HomeTab.DOCS) {
                        FilterChip(
                            selected = vm.autoLaunchCamera,
                            onClick = { vm.toggleAutoLaunchCamera() },
                            label = { Text("Vào thẳng Cam") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    } else if (trashDocs.isNotEmpty()) {
                        TextButton(onClick = { vm.emptyTrash() }) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text("Dọn sạch", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            HomeBottomBar(
                tab = tab,
                trashCount = trashDocs.size,
                onSelectTab = { tab = it },
                onScanSingle = onScanSingle,
                onScanBatch = onScanBatch
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            BusyBar(vm.busy)

            if (tab == HomeTab.DOCS) {
                // Thanh tìm kiếm + chọn nhanh mức nén mặc định
                SearchAndQualityHeader(
                    query = vm.searchQuery,
                    onQueryChange = { vm.searchQuery = it },
                    quality = vm.exportQuality,
                    onQualityChange = { vm.updateQuality(it) }
                )

                when {
                    !vm.loaded -> Unit
                    activeDocs.isEmpty() -> EmptyStateView(
                        hasSearch = vm.searchQuery.isNotBlank(),
                        onScanBatch = onScanBatch
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(activeDocs, key = { it.id }) { doc ->
                            DocGridCard(
                                doc = doc,
                                thumbFile = doc.pages.firstOrNull()?.let { vm.store.pageFile(doc.id, it) },
                                thumbRotation = doc.pages.firstOrNull()?.rotation ?: 0,
                                onOpen = { onOpen(doc) },
                                onRename = { onRename(doc) },
                                onTrash = { onTrash(doc) },
                                onSendPdf = { onSendPdf(doc) },
                                onSendImages = { onSendImages(doc) }
                            )
                        }
                    }
                }
            } else {
                TrashListView(
                    trashDocs = trashDocs,
                    thumbFile = { doc -> doc.pages.firstOrNull()?.let { vm.store.pageFile(doc.id, it) } },
                    onRestore = { vm.restoreFromTrash(it.id) },
                    onDeleteForever = { vm.deletePermanently(it.id) }
                )
            }
        }
    }
}

@Composable
private fun SearchAndQualityHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    quality: ExportQuality,
    onQualityChange: (ExportQuality) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Tìm tài liệu…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Xóa tìm kiếm")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        QualitySelectorRow(quality = quality, onQualityChange = onQualityChange)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocGridCard(
    doc: ScanDoc,
    thumbFile: File?,
    thumbRotation: Int,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onTrash: () -> Unit,
    onSendPdf: () -> Unit,
    onSendImages: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onRename)
    ) {
        Column {
            // Thumbnail lớn + badge số trang
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.82f)
                    .padding(8.dp)
            ) {
                PageThumb(
                    file = thumbFile,
                    rotation = thumbRotation,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                ) {
                    Text(
                        "${doc.pages.size} trang",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // Tên tài liệu (chạm để đổi tên nhanh)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRename)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        doc.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        dateFormat.format(Date(doc.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Đổi tên",
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Hàng nút 1 chạm: Gửi PDF / Gửi Ảnh / Xóa vào Thùng rác
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onSendPdf,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = "Gửi PDF", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("PDF")
                }
                TextButton(
                    onClick = onSendImages,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = "Gửi Ảnh", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Ảnh")
                }
                IconButton(onClick = onTrash, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Xóa",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeBottomBar(
    tab: HomeTab,
    trashCount: Int,
    onSelectTab: (HomeTab) -> Unit,
    onScanSingle: () -> Unit,
    onScanBatch: () -> Unit
) {
    Surface(tonalElevation = 4.dp, shadowElevation = 6.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Tab Tài liệu
            TextButton(
                onClick = { onSelectTab(HomeTab.DOCS) },
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = "Tài liệu",
                        tint = if (tab == HomeTab.DOCS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Tài liệu",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (tab == HomeTab.DOCS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Cụm nút Camera nổi bật ở giữa: [1 trang] + [Quét nhiều]
            FilledTonalButton(
                onClick = onScanSingle,
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("1 trang", fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = onScanBatch,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 14.dp)
            ) {
                Icon(Icons.Default.DocumentScanner, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Scan", fontWeight = FontWeight.Bold)
            }

            // Tab Thùng rác 30 ngày
            TextButton(
                onClick = { onSelectTab(HomeTab.TRASH) },
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BadgedBox(
                        badge = {
                            if (trashCount > 0) {
                                Badge { Text("$trashCount") }
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Thùng rác",
                            tint = if (tab == HomeTab.TRASH) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "Thùng rác",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (tab == HomeTab.TRASH) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TrashListView(
    trashDocs: List<ScanDoc>,
    thumbFile: (ScanDoc) -> File?,
    onRestore: (ScanDoc) -> Unit,
    onDeleteForever: (ScanDoc) -> Unit
) {
    if (trashDocs.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Thùng rác trống.\nTài liệu đã xóa sẽ được giữ tại đây 30 ngày trước khi tự động xóa vĩnh viễn.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    } else {
        val now = System.currentTimeMillis()
        LazyColumn(
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(trashDocs, key = { it.id }) { doc ->
                val elapsedMs = now - (doc.deletedAt ?: now)
                val remainingDays = ceil((DocumentStore.TRASH_RETENTION_MS - elapsedMs) / (24.0 * 3600 * 1000))
                    .toInt().coerceAtLeast(1)
                Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PageThumb(
                            file = thumbFile(doc),
                            rotation = doc.pages.firstOrNull()?.rotation ?: 0,
                            modifier = Modifier.width(50.dp).aspectRatio(0.75f)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(doc.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${doc.pages.size} trang · Tự xóa sau $remainingDays ngày",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalIconButton(onClick = { onRestore(doc) }) {
                            Icon(Icons.Default.RestoreFromTrash, contentDescription = "Khôi phục")
                        }
                        IconButton(onClick = { onDeleteForever(doc) }) {
                            Icon(
                                Icons.Default.DeleteForever,
                                contentDescription = "Xóa vĩnh viễn",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStateView(hasSearch: Boolean, onScanBatch: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (hasSearch) "Không tìm thấy tài liệu phù hợp."
                else "Chưa có tài liệu nào.\nBấm Scan để chụp và xuất PDF siêu tốc.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (!hasSearch) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onScanBatch, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.DocumentScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Quét ngay")
                }
            }
        }
    }
}

// =====================================================================
// MÀN HÌNH BƯỚC 2 & 3: XEM LẠI / CHỈNH NHANH → GỬI
// Preview lớn ở trên + dải thumbnail ngang phía dưới (kéo thả đổi thứ tự) + Undo/Redo + Gửi 1 chạm
// =====================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    doc: ScanDoc,
    busy: String?,
    quality: ExportQuality,
    canUndo: Boolean,
    canRedo: Boolean,
    snackbar: SnackbarHostState,
    fileOf: (ScanPage) -> File,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onQualityChange: (ExportQuality) -> Unit,
    onAddPages: () -> Unit,
    onTrashDoc: () -> Unit,
    onRotate: (String) -> Unit,
    onDeletePage: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSendPdf: () -> Unit,
    onSendImages: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val hasPages = doc.pages.isNotEmpty()
    val pagerState = rememberPagerState(initialPage = 0) { doc.pages.size.coerceAtLeast(1) }
    val stripState = rememberLazyListState()
    val reorderStripState = rememberReorderableLazyListState(stripState) { from, to ->
        onMove(from.index, to.index)
    }

    // Giữ trang đang chọn hợp lệ khi xóa trang cuối
    val currentIndex = pagerState.currentPage.coerceIn(0, (doc.pages.size - 1).coerceAtLeast(0))
    val currentPage = doc.pages.getOrNull(currentIndex)

    LaunchedEffect(currentIndex) {
        if (currentIndex in doc.pages.indices) {
            stripState.animateScrollToItem(currentIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                title = {
                    // Chạm vào tên -> mở hộp thoại đổi tên (tự focus + bôi đen toàn bộ)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onRename)
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            doc.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Edit, contentDescription = "Đổi tên", modifier = Modifier.size(17.dp))
                    }
                },
                actions = {
                    IconButton(onClick = onUndo, enabled = canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Hoàn tác")
                    }
                    IconButton(onClick = onRedo, enabled = canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Làm lại")
                    }
                    IconButton(onClick = onTrashDoc) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Chuyển vào thùng rác")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 4.dp, shadowElevation = 6.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    QualitySelectorRow(quality = quality, onQualityChange = onQualityChange)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onSendPdf,
                            enabled = hasPages,
                            modifier = Modifier.weight(1.2f).height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Gửi PDF", fontWeight = FontWeight.Bold)
                        }
                        FilledTonalButton(
                            onClick = onSendImages,
                            enabled = hasPages,
                            modifier = Modifier.weight(1f).height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Gửi Ảnh", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            BusyBar(busy)

            if (!hasPages) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    FilledTonalButton(onClick = onAddPages) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Quét thêm trang")
                    }
                }
            } else {
                // 1. Vùng xem trang lớn (vuốt ngang để xem từng trang)
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val fitRotated = minOf(maxWidth, maxHeight) / maxOf(maxWidth, maxHeight)
                    HorizontalPager(
                        state = pagerState,
                        key = { idx -> doc.pages.getOrNull(idx)?.id ?: idx },
                        modifier = Modifier.fillMaxSize()
                    ) { pageIdx ->
                        val page = doc.pages.getOrNull(pageIdx) ?: return@HorizontalPager
                        AsyncImage(
                            model = fileOf(page),
                            contentDescription = "Trang ${pageIdx + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                                .graphicsLayer {
                                    rotationZ = page.rotation.toFloat()
                                    val s = if (page.rotation % 180 != 0) fitRotated else 1f
                                    scaleX = s
                                    scaleY = s
                                }
                        )
                    }

                    // Nhãn Trang X / N
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                    ) {
                        Text(
                            "Trang ${currentIndex + 1} / ${doc.pages.size}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }

                // 2. Thanh công cụ chỉnh nhanh cho trang đang chọn
                if (currentPage != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { onRotate(currentPage.id) }) {
                            Icon(Icons.Default.Rotate90DegreesCw, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Xoay 90°")
                        }
                        TextButton(onClick = onAddPages) {
                            Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Thêm trang")
                        }
                        TextButton(onClick = { onDeletePage(currentPage.id) }) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Xóa trang", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                // 3. Dải thumbnail ngang phía dưới (chạm để chuyển trang, giữ & kéo để đổi thứ tự)
                LazyRow(
                    state = stripState,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().height(94.dp)
                ) {
                    items(doc.pages, key = { it.id }) { page ->
                        ReorderableItem(reorderStripState, key = page.id) { isDragging ->
                            val idx = doc.pages.indexOf(page)
                            val isSelected = idx == currentIndex
                            val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(0.75f)
                                    .graphicsLayer {
                                        val scale = if (isDragging) 1.08f else 1f
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(width = if (isSelected) 2.5.dp else 0.dp, color = borderColor, shape = RoundedCornerShape(10.dp))
                                    .longPressDraggableHandle(
                                        onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                                    )
                                    .clickable {
                                        scope.launch { pagerState.scrollToPage(idx) }
                                    }
                            ) {
                                PageThumb(
                                    file = fileOf(page),
                                    rotation = page.rotation,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Surface(
                                    shape = RoundedCornerShape(bottomEnd = 6.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.6f),
                                    modifier = Modifier.align(Alignment.TopStart)
                                ) {
                                    Text(
                                        "${idx + 1}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// BỘ CHỌN 3 MỨC DUNG LƯỢNG (Nhỏ / Cân bằng / Cao)
// =====================================================================
@Composable
private fun QualitySelectorRow(
    quality: ExportQuality,
    onQualityChange: (ExportQuality) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "Dung lượng:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ExportQuality.entries.forEach { option ->
            FilterChip(
                selected = quality == option,
                onClick = { onQualityChange(option) },
                label = { Text("${option.label} (${option.hint})", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(30.dp)
            )
        }
    }
}

// =====================================================================
// ĐỔI TÊN – tự focus, bôi đen sẵn toàn bộ, bàn phím bật ngay, Enter là lưu
// =====================================================================
@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Đổi tên tài liệu") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { onConfirm(value.text) }),
                trailingIcon = {
                    if (value.text.isNotEmpty()) {
                        IconButton(onClick = { value = TextFieldValue("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Xóa chữ")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = value.text.isNotBlank()) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )

    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboard?.show()
    }
}

// =====================================================================
// Thành phần dùng chung
// =====================================================================
@Composable
private fun BusyBar(busy: String?) {
    if (busy != null) {
        Column(Modifier.fillMaxWidth()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                busy,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun PageThumb(file: File?, rotation: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (file != null) {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = rotation.toFloat()
                        val s = if (rotation % 180 != 0) 0.75f else 1f
                        scaleX = s
                        scaleY = s
                    }
            )
        }
    }
}

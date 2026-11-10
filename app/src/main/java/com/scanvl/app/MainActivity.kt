package com.scanvl.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.log10
import kotlin.math.pow

// =========================================================================
// 1. DATA MODEL: Đại diện cho tài liệu đã quét và lưu trữ
// =========================================================================
data class ScannedDocument(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val pdfPath: String,
    val thumbnailPath: String?,
    val pageCount: Int,
    val createdAt: Long,
    val fileSizeBytes: Long
)

// =========================================================================
// 2. MAIN ACTIVITY: Quản lý vòng đời, Scanner Launcher và Giao diện Compose
// =========================================================================
class MainActivity : ComponentActivity() {

    // Danh sách state các tài liệu để Compose quan sát và cập nhật giao diện tức thì
    private val scannedDocsState = mutableStateOf<List<ScannedDocument>>(emptyList())
    private val isProcessingState = mutableStateOf(false)

    // Đăng ký Activity Result Launcher để nhận kết quả từ Google Document Scanner
    private val scannerLauncher: ActivityResultLauncher<IntentSenderRequest> =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(data)
                if (scanningResult != null) {
                    processScanningResult(scanningResult)
                } else {
                    Toast.makeText(this, "Không nhận được dữ liệu quét!", Toast.LENGTH_SHORT).show()
                }
            } else if (result.resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(this, "Đã hủy quét tài liệu", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Quét tài liệu thất bại", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tải danh sách tài liệu đã lưu từ bộ nhớ máy
        loadSavedDocuments()

        setContent {
            MaterialTheme {
                val documents = scannedDocsState.value
                val isProcessing = isProcessingState.value

                DashboardScreen(
                    documents = documents,
                    isProcessing = isProcessing,
                    onStartScan = { startGoogleDocumentScanner() },
                    onOpenPdf = { doc -> openPdfFile(doc) },
                    onSharePdf = { doc -> sharePdfFile(doc) },
                    onDeletePdf = { doc -> deleteScannedDocument(doc) }
                )
            }
        }
    }

    /**
     * Khởi tạo và thiết lập các thông số cho Google Document Scanner:
     * - Multi-page scanning (Tối đa 20 trang).
     * - Chế độ SCANNER_MODE_FULL: Tự động phát hiện mép, người dùng chỉnh 4 góc, bộ lọc màu (B&W, Magic Color...).
     * - Cho phép import thêm ảnh có sẵn từ bộ sưu tập.
     * - Định dạng xuất kết quả: Cả PDF và JPEG.
     */
    private fun startGoogleDocumentScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setPageLimit(20)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setGalleryImportAllowed(true)
            .build()

        val scannerClient = GmsDocumentScanning.getClient(options)

        scannerClient.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                try {
                    val request = IntentSenderRequest.Builder(intentSender).build()
                    scannerLauncher.launch(request)
                } catch (e: Exception) {
                    Toast.makeText(this, "Lỗi khi mở giao diện Scanner: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Không thể kết nối Google Document Scanner: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Xử lý kết quả quét trả về từ Google Play Services:
     * Sao chép file PDF và ảnh Thumbnail trang 1 từ bộ nhớ đệm tạm thời của Google
     * sang Scoped Storage vĩnh viễn của ứng dụng.
     */
    private fun processScanningResult(scanningResult: GmsDocumentScanningResult) {
        lifecycleScope.launch(Dispatchers.IO) {
            isProcessingState.value = true

            try {
                val pdf = scanningResult.pdf
                val pages = scanningResult.pages.orEmpty()

                if (pdf == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Lỗi: Không tìm thấy file PDF được tạo", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val displayTitle = "Tài liệu_$timeStamp"

                // Thư mục lưu trữ theo chuẩn Scoped Storage (Thư mục Documents nội bộ của ứng dụng)
                val baseDir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "ScanVL").apply {
                    if (!exists()) mkdirs()
                }

                val targetPdfFile = File(baseDir, "$displayTitle.pdf")
                val targetThumbFile = File(baseDir, "${displayTitle}_thumb.jpg")

                // 1. Sao chép nội dung PDF
                contentResolver.openInputStream(pdf.uri)?.use { inputStream ->
                    FileOutputStream(targetPdfFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                // 2. Sao chép ảnh trang đầu tiên làm thumbnail đại diện
                var thumbPath: String? = null
                if (pages.isNotEmpty()) {
                    val firstPageUri = pages[0].imageUri
                    contentResolver.openInputStream(firstPageUri)?.use { inputStream ->
                        FileOutputStream(targetThumbFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                            thumbPath = targetThumbFile.absolutePath
                        }
                    }
                }

                val newDoc = ScannedDocument(
                    title = displayTitle,
                    pdfPath = targetPdfFile.absolutePath,
                    thumbnailPath = thumbPath,
                    pageCount = pdf.pageCount,
                    createdAt = System.currentTimeMillis(),
                    fileSizeBytes = targetPdfFile.length()
                )

                // Cập nhật danh sách tài liệu
                val updatedList = listOf(newDoc) + scannedDocsState.value
                scannedDocsState.value = updatedList
                saveDocumentsToPrefs(updatedList)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Đã lưu tài liệu: ${newDoc.pageCount} trang", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Lỗi khi lưu trữ tài liệu: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                isProcessingState.value = false
            }
        }
    }

    /**
     * Mở file PDF bằng các ứng dụng đọc PDF có sẵn trên máy (Google Drive, Adobe Reader...)
     */
    private fun openPdfFile(doc: ScannedDocument) {
        val file = File(doc.pdfPath)
        if (!file.exists()) {
            Toast.makeText(this, "File PDF không còn tồn tại trên máy!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val contentUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Chưa cài đặt ứng dụng đọc PDF trên thiết bị", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Không thể mở file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Chia sẻ file PDF nhanh qua Zalo, Gmail, Drive, Messenger... bằng FileProvider an toàn.
     */
    private fun sharePdfFile(doc: ScannedDocument) {
        val file = File(doc.pdfPath)
        if (!file.exists()) {
            Toast.makeText(this, "File PDF không tồn tại!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "${doc.title}.pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Chia sẻ tài liệu PDF qua..."))
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi khi chia sẻ: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Xóa tài liệu khỏi bộ nhớ và cập nhật danh sách
     */
    private fun deleteScannedDocument(doc: ScannedDocument) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Xóa file vật lý
            File(doc.pdfPath).takeIf { it.exists() }?.delete()
            doc.thumbnailPath?.let { path ->
                File(path).takeIf { it.exists() }?.delete()
            }

            // Cập nhật danh sách
            val updated = scannedDocsState.value.filter { it.id != doc.id }
            scannedDocsState.value = updated
            saveDocumentsToPrefs(updated)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Đã xóa tài liệu", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // =========================================================================
    // Quản lý SharedPreferences lưu trữ Metadata tài liệu
    // =========================================================================
    private fun saveDocumentsToPrefs(list: List<ScannedDocument>) {
        val prefs = getSharedPreferences("ScanVL_Docs", Context.MODE_PRIVATE)
        val json = Gson().toJson(list)
        prefs.edit().putString("docs_list", json).apply()
    }

    private fun loadSavedDocuments() {
        val prefs = getSharedPreferences("ScanVL_Docs", Context.MODE_PRIVATE)
        val json = prefs.getString("docs_list", null) ?: return
        try {
            val type = object : TypeToken<List<ScannedDocument>>() {}.type
            val savedList: List<ScannedDocument> = Gson().fromJson(json, type)
            // Lọc chỉ giữ lại những tài liệu mà file PDF thực sự còn tồn tại trên máy
            val validList = savedList.filter { File(it.pdfPath).exists() }
            scannedDocsState.value = validList
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// =========================================================================
// 3. COMPOSE UI: Màn hình Dashboard, Danh sách tài liệu và Nút quét FAB
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    documents: List<ScannedDocument>,
    isProcessing: Boolean,
    onStartScan: () -> Unit,
    onOpenPdf: (ScannedDocument) -> Unit,
    onSharePdf: (ScannedDocument) -> Unit,
    onDeletePdf: (ScannedDocument) -> Unit
) {
    var documentToDelete by remember { mutableStateOf<ScannedDocument?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "ScanVL",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Quét Tài Liệu Chuyên Nghiệp",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (documents.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(end = 16.dp)
                        ) {
                            Text(
                                text = "${documents.size} tài liệu",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onStartScan,
                icon = {
                    Icon(
                        imageVector = Icons.Default.DocumentScanner,
                        contentDescription = "Quét tài liệu"
                    )
                },
                text = { Text("Quét mới", fontWeight = FontWeight.SemiBold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (documents.isEmpty() && !isProcessing) {
                // Giao diện khi chưa có tài liệu nào
                EmptyDashboardView(onStartScan = onStartScan)
            } else {
                // Danh sách các tài liệu đã quét
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(documents, key = { it.id }) { doc ->
                        DocumentItemCard(
                            doc = doc,
                            onOpen = { onOpenPdf(doc) },
                            onShare = { onSharePdf(doc) },
                            onDelete = { documentToDelete = doc }
                        )
                    }
                    // Spacer dưới cùng để nội dung không bị che bởi FAB
                    item {
                        Spacer(modifier = Modifier.height(72.dp))
                    }
                }
            }

            // Hiển thị vòng xoay xử lý khi đang nén/sao chép tài liệu PDF
            AnimatedVisibility(
                visible = isProcessing,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                    shadowElevation = 8.dp,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Đang tối ưu & xuất PDF...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    // Hộp thoại xác nhận xóa tài liệu
    documentToDelete?.let { targetDoc ->
        AlertDialog(
            onDismissRequest = { documentToDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Xác nhận xóa tài liệu") },
            text = { Text("Bạn có chắc chắn muốn xóa '${targetDoc.title}'? File PDF sẽ bị xóa khỏi bộ nhớ máy vĩnh viễn.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePdf(targetDoc)
                        documentToDelete = null
                    }
                ) {
                    Text("Xóa", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { documentToDelete = null }) {
                    Text("Hủy")
                }
            }
        )
    }
}

// =========================================================================
// 4. COMPONENT: Thẻ hiển thị thông tin từng tài liệu (Card)
// =========================================================================
@Composable
fun DocumentItemCard(
    doc: ScannedDocument,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val formattedDate = remember(doc.createdAt) {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(doc.createdAt))
    }
    val formattedSize = remember(doc.fileSizeBytes) {
        formatBytes(doc.fileSizeBytes)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onOpen() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Ảnh thu nhỏ Thumbnail của trang đầu tiên
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!doc.thumbnailPath.isNullOrEmpty() && File(doc.thumbnailPath).exists()) {
                    AsyncImage(
                        model = File(doc.thumbnailPath),
                        contentDescription = "Thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = "PDF File",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Thông tin chi tiết: Tên, Số trang, Dung lượng, Ngày giờ
            Column(
                modifier = Modifier
                    .weight(1f)
            ) {
                Text(
                    text = doc.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Badge số trang
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "${doc.pageCount} trang",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Kích thước file
                    Text(
                        text = "•  $formattedSize",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Các nút hành động: Xem, Chia sẻ, Xóa
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilledTonalButton(
                        onClick = onOpen,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Xem", fontSize = 12.sp)
                    }

                    IconButton(
                        onClick = onShare,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Chia sẻ",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Xóa",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// 5. COMPONENT: Giao diện trống khi chưa có tài liệu nào
// =========================================================================
@Composable
fun EmptyDashboardView(onStartScan: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.size(110.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.DocumentScanner,
                    contentDescription = null,
                    modifier = Modifier.size(54.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Chưa có tài liệu nào",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Bấm nút quét bên dưới để chụp tài liệu, hợp đồng, biên lai hoặc hóa đơn và tự động xuất ra file PDF chất lượng cao.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(28.dp))

        OutlinedButton(
            onClick = onStartScan,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(imageVector = Icons.Default.Description, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Bắt đầu quét ngay")
        }
    }
}

// =========================================================================
// 6. HELPER: Định dạng kích thước dung lượng file (Bytes -> KB / MB)
// =========================================================================
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    return "${DecimalFormat("#,##0.#").format(value)} ${units[digitGroups]}"
}

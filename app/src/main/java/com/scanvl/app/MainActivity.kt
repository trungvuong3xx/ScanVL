package com.scanvl.app

import android.content.ClipData
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.scanvl.app.ui.ScanVLApp
import java.io.File

/** Hai chế độ quét theo nhu cầu thực tế. */
enum class ScanMode(val pageLimit: Int) {
    SINGLE(1),
    BATCH(20)
}

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    /** Tài liệu cần thêm trang (null = quét tài liệu mới). Giữ lại qua xoay màn hình / process death. */
    private var pendingTargetDocId: String? = null
    private var lastMode: ScanMode = ScanMode.BATCH
    private var launching = false
    private var autoLaunchedThisSession = false

    /**
     * ZERO-LATENCY: IntentSender của scanner được lấy sẵn ngay khi mở app.
     * Bấm nút quét -> mở camera ngay lập tức, không phải chờ IPC tới Google Play Services.
     */
    private val prefetched = HashMap<ScanMode, IntentSender>()
    private val clients = HashMap<ScanMode, GmsDocumentScanner>()

    private val scannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            launching = false
            val target = pendingTargetDocId
            pendingTargetDocId = null

            if (result.resultCode == RESULT_OK) {
                val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                val uris = scan?.pages.orEmpty().map { it.imageUri }
                vm.importScan(uris, target)
            } else if (result.data?.hasExtra(ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION) == true) {
                // IntentSender nạp sẵn đã hết hạn -> lấy cái mới và mở lại ngay
                pendingTargetDocId = target
                launching = true
                launchFresh(lastMode)
                return@registerForActivityResult
            }
            prefetchAll()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingTargetDocId = savedInstanceState?.getString(KEY_TARGET)
        autoLaunchedThisSession = savedInstanceState?.getBoolean(KEY_AUTO_LAUNCHED, false) ?: false

        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                ScanVLApp(
                    vm = vm,
                    onScan = { mode, targetDocId -> startScan(mode, targetDocId) },
                    onSendPdf = { docId -> vm.exportPdf(docId) { file -> shareFiles(listOf(file), "application/pdf") } },
                    onSendImages = { docId -> vm.exportImages(docId) { files -> shareFiles(files, "image/jpeg") } }
                )
            }
        }

        // Nguyên tắc cốt lõi #1: Mở app là vào thẳng camera (chỉ kích hoạt 1 lần khi cold start nếu bật tùy chọn)
        if (!autoLaunchedThisSession && vm.autoLaunchCamera) {
            autoLaunchedThisSession = true
            startScan(ScanMode.BATCH, null)
        } else {
            prefetchAll()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TARGET, pendingTargetDocId)
        outState.putBoolean(KEY_AUTO_LAUNCHED, autoLaunchedThisSession)
    }

    // ------------------------------------------------------------------
    // Google ML Kit Document Scanner
    // ------------------------------------------------------------------
    private fun client(mode: ScanMode): GmsDocumentScanner = clients.getOrPut(mode) {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                // FULL: tự bắt mép giấy, auto-capture, chỉnh 4 góc, bộ lọc (Gốc / Đen trắng / Tài liệu), xoay
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setGalleryImportAllowed(true)
                .setPageLimit(mode.pageLimit)
                // Chỉ lấy JPEG: ML Kit trả kết quả nhanh hơn vì không phải dựng PDF.
                // PDF được app tự dựng siêu tốc lúc gửi (nhúng thẳng JPEG hoặc nén theo mức chọn).
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .build()
        )
    }

    private fun prefetchAll() {
        ScanMode.entries.forEach { mode ->
            if (prefetched[mode] == null) {
                client(mode).getStartScanIntent(this)
                    .addOnSuccessListener { sender -> prefetched[mode] = sender }
            }
        }
    }

    private fun startScan(mode: ScanMode, targetDocId: String?) {
        if (launching) return // chống bấm đúp mở 2 scanner
        launching = true
        lastMode = mode
        pendingTargetDocId = targetDocId

        val ready = prefetched.remove(mode)
        if (ready != null) {
            launchSender(ready)
        } else {
            launchFresh(mode)
        }
    }

    private fun launchFresh(mode: ScanMode) {
        client(mode).getStartScanIntent(this)
            .addOnSuccessListener { sender -> launchSender(sender) }
            .addOnFailureListener { e ->
                launching = false
                pendingTargetDocId = null
                Toast.makeText(this, "Không mở được trình quét: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
    }

    private fun launchSender(sender: IntentSender) {
        try {
            scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (e: Exception) {
            launching = false
            pendingTargetDocId = null
            Toast.makeText(this, "Không mở được trình quét: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------
    // Gửi file (Zalo, Gmail, Drive, Messenger…) qua FileProvider
    // ------------------------------------------------------------------
    private fun shareFiles(files: List<File>, mimeType: String) {
        if (files.isEmpty()) return
        try {
            val authority = "$packageName.fileprovider"
            val uris = files.map { FileProvider.getUriForFile(this, authority, it) }

            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            intent.type = mimeType
            // ClipData bắt buộc để cấp quyền đọc cho TẤT CẢ file khi gửi nhiều ảnh
            intent.clipData = ClipData.newRawUri(null, uris[0]).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            startActivity(Intent.createChooser(intent, null))
        } catch (e: Exception) {
            Toast.makeText(this, "Không gửi được: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val KEY_TARGET = "pending_target_doc"
        const val KEY_AUTO_LAUNCHED = "auto_launched_session"
    }
}

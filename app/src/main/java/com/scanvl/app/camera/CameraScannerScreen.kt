package com.scanvl.app.camera

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.scanvl.app.data.CapturedPhoto
import com.scanvl.app.data.DocumentFilter
import com.scanvl.app.data.DocumentStore
import com.scanvl.app.data.QuickSendAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

enum class CameraFlashMode {
    OFF, ON, AUTO
}

enum class CameraScanTab {
    SINGLE, BATCH
}

/**
 * Giao diện Quét Tài Liệu chuẩn:
 * - Cân bằng điện thoại (Spirit Level tròn có dấu cộng xanh và bóng nước)
 * - Chụp đơn (1 trang) & Chụp hàng loạt (nhiều trang liên tục)
 * - Xem thumbnail ảnh vừa chụp với badge đỏ, nút "Sửa đổi" mở màn hình xem/sửa/xóa
 * - Nút "Hoàn thành" màu ngọc lam
 */
@Composable
fun CameraScannerScreen(
    initialTab: CameraScanTab = CameraScanTab.BATCH,
    onClose: () -> Unit,
    onFinishScan: (List<CapturedPhoto>, QuickSendAction) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Kiểm tra quyền Camera
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            Toast.makeText(context, "Cần cấp quyền Camera để quét tài liệu", Toast.LENGTH_SHORT).show()
            onClose()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("Đang yêu cầu quyền camera...", color = Color.White)
        }
        return
    }

    // State Camera & Sensor & Settings
    val store = remember { DocumentStore(context) }
    var isShutterSoundEnabled by remember { mutableStateOf(store.isShutterSoundEnabled) }
    var isAutoCropEnabled by remember { mutableStateOf(store.isAutoCropEnabled) }
    var isAutoSnapLevelEnabled by remember { mutableStateOf(store.isAutoSnapLevelEnabled) }
    var selectedFilter by remember { mutableStateOf(store.defaultFilter) }

    val levelState by rememberDeviceLevel()
    var scanTab by remember { mutableStateOf(initialTab) }
    var flashMode by remember { mutableStateOf(CameraFlashMode.OFF) }
    var isHdMode by remember { mutableStateOf(true) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showLevelIndicator by remember { mutableStateOf(true) }

    val capturedPhotos = remember { mutableStateListOf<CapturedPhoto>() }
    var isReviewing by remember { mutableStateOf(false) }
    var reviewStartIndex by remember { mutableIntStateOf(0) }
    var quickSendPhoto by remember { mutableStateOf<CapturedPhoto?>(null) }
    var lastAutoSnapTime by remember { mutableLongStateOf(0L) }

    // Shutter animation state
    var isShooting by remember { mutableStateOf(false) }
    val shutterFlashAlpha = remember { Animatable(0f) }
    val shutterButtonScale = remember { Animatable(1f) }

    // CameraX objects
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var liveCornersNorm by remember { mutableStateOf<DocumentCornersNorm?>(null) }
    var missedDetectionFrames by remember { mutableIntStateOf(0) }
    var lastAnalysisTime by remember { mutableLongStateOf(0L) }

    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetResolution(android.util.Size(480, 640))
            .build()
    }
    var cameraControl: Camera? by remember { mutableStateOf(null) }

    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(Unit) {
        onDispose {
            sound.release()
            analysisExecutor.shutdown()
        }
    }

    // Haptic feedback khi điện thoại đạt mức cân bằng hoàn hảo
    var wasBalanced by remember { mutableStateOf(false) }
    LaunchedEffect(levelState.isBalanced) {
        if (levelState.isBalanced && !wasBalanced) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        wasBalanced = levelState.isBalanced
    }

    // Hàm chụp ảnh chính: TỐC ĐỘ CAO, KHÔNG KHÓA NÚT CHỤP (Non-blocking Burst Capture)
    fun performShutter() {
        if (isShooting) return
        isShooting = true

        // Chớp animation & âm thanh (nếu bật)
        scope.launch {
            if (isShutterSoundEnabled) {
                sound.play(MediaActionSound.SHUTTER_CLICK)
            }
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            shutterButtonScale.animateTo(0.85f, tween(70))
            shutterButtonScale.animateTo(1f, tween(90))
        }
        scope.launch {
            shutterFlashAlpha.snapTo(0.6f)
            shutterFlashAlpha.animateTo(0f, tween(150))
        }

        // Chụp và lưu ảnh vào cache session
        takePhoto(
            context = context,
            imageCapture = imageCapture,
            onSuccess = { file ->
                // MỞ KHÓA NÚT CHỤP NGAY LẬP TỨC: Người dùng có thể bấm chụp tiếp tục không trễ
                isShooting = false

                val filterToApply = selectedFilter
                val item = CapturedPhoto(file = file, filter = filterToApply)
                capturedPhotos.add(item)

                // Nếu là chế độ chụp đơn: mở ngay thanh 1 chạm Gửi PDF / Gửi Ảnh
                if (scanTab == CameraScanTab.SINGLE) {
                    quickSendPhoto = item
                }

                // Xử lý nắn viền & bộ lọc ngầm trên background thread không gây đơ UI
                scope.launch(Dispatchers.Default) {
                    if (isAutoCropEnabled || filterToApply != DocumentFilter.ORIGINAL) {
                        DocumentCropper.processFile(
                            file = file,
                            corners = null,
                            filter = filterToApply
                        )
                        withContext(Dispatchers.Main) {
                            val idx = capturedPhotos.indexOfFirst { it.file.absolutePath == file.absolutePath }
                            if (idx >= 0) {
                                capturedPhotos[idx] = capturedPhotos[idx].copy(id = UUID.randomUUID().toString())
                            }
                        }
                    }
                }
            },
            onError = {
                isShooting = false
                Toast.makeText(context, "Lỗi khi chụp: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Tự động chụp khi điện thoại đạt mức cân bằng và đứng yên (Auto-Snap on Steady Level)
    LaunchedEffect(levelState.isBalanced, isAutoSnapLevelEnabled) {
        if (!isAutoSnapLevelEnabled) return@LaunchedEffect
        if (levelState.isBalanced) {
            val startTime = System.currentTimeMillis()
            while (levelState.isBalanced) {
                val elapsed = System.currentTimeMillis() - startTime
                val now = System.currentTimeMillis()
                if (elapsed >= 450 && now - lastAutoSnapTime >= 1800 && !isShooting && !isReviewing && quickSendPhoto == null) {
                    lastAutoSnapTime = now
                    performShutter()
                    break
                }
                delay(50)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Live Camera Preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    // Phân tích hình ảnh thời gian thực để nhận diện và đóng khung viền mép giấy
                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        val now = System.currentTimeMillis()
                        if (now - lastAnalysisTime >= 90 && isAutoCropEnabled && !isShooting && !isReviewing && quickSendPhoto == null) {
                            lastAnalysisTime = now
                            try {
                                val rotation = imageProxy.imageInfo.rotationDegrees
                                val rawBmp = imageProxy.toBitmap()
                                // Xoay ảnh về đúng chiều màn hình (Portrait) trước khi nhận diện
                                val rotatedBmp = if (rotation != 0) {
                                    val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                                    val r = android.graphics.Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true)
                                    if (r !== rawBmp) rawBmp.recycle()
                                    r
                                } else {
                                    rawBmp
                                }

                                val detected = DocumentCropper.detectCornersNorm(rotatedBmp)
                                rotatedBmp.recycle()

                                scope.launch(Dispatchers.Main) {
                                    if (detected.isDetected) {
                                        missedDetectionFrames = 0
                                        val prev = liveCornersNorm
                                        if (prev == null || !prev.isDetected) {
                                            liveCornersNorm = detected
                                        } else {
                                            // Làm mượt bằng Exponential Moving Average (EMA) để khung không rung lắc
                                            val a = 0.40f
                                            liveCornersNorm = DocumentCornersNorm(
                                                topLeft = android.graphics.PointF(prev.topLeft.x * (1 - a) + detected.topLeft.x * a, prev.topLeft.y * (1 - a) + detected.topLeft.y * a),
                                                topRight = android.graphics.PointF(prev.topRight.x * (1 - a) + detected.topRight.x * a, prev.topRight.y * (1 - a) + detected.topRight.y * a),
                                                bottomRight = android.graphics.PointF(prev.bottomRight.x * (1 - a) + detected.bottomRight.x * a, prev.bottomRight.y * (1 - a) + detected.bottomRight.y * a),
                                                bottomLeft = android.graphics.PointF(prev.bottomLeft.x * (1 - a) + detected.bottomLeft.x * a, prev.bottomLeft.y * (1 - a) + detected.bottomLeft.y * a),
                                                isDetected = true
                                            )
                                        }
                                    } else {
                                        missedDetectionFrames++
                                        if (missedDetectionFrames > 4) {
                                            liveCornersNorm = DocumentCornersNorm.defaultGuide()
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        imageProxy.close()
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        cameraProvider.unbindAll()
                        cameraControl = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // 1.5. KHUNG NHẬN DIỆN TÀI LIỆU THỜI GIAN THỰC (Real-time Document Framing Box)
        if (isAutoCropEnabled && !isShooting && !isReviewing && quickSendPhoto == null) {
            LiveDocumentFrameOverlay(
                cornersNorm = liveCornersNorm,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Chớp trắng khi chụp
        if (shutterFlashAlpha.value > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = shutterFlashAlpha.value }
                    .background(Color.White)
            )
        }

        // 3. CÂN BẰNG ĐIỆN THOẠI (Spirit Level indicator - góc trên bên trái)
        if (showLevelIndicator) {
            LevelIndicatorOverlay(
                levelState = levelState,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 64.dp, start = 16.dp)
                    .align(Alignment.TopStart)
            )

            // Thước đo góc nghiêng bên cạnh phải
            TiltAngleGauge(
                tiltAngle = levelState.tiltAngle,
                isBalanced = levelState.isBalanced,
                modifier = Modifier
                    .padding(end = 12.dp, top = 180.dp)
                    .align(Alignment.TopEnd)
            )
        }

        // 4. TOP BAR: [X] bên trái, [Flash] [Bộ lọc] [HD] [...] bên phải
        TopCameraBar(
            flashMode = flashMode,
            isHdMode = isHdMode,
            showMoreMenu = showMoreMenu,
            showLevelIndicator = showLevelIndicator,
            isShutterSoundEnabled = isShutterSoundEnabled,
            isAutoCropEnabled = isAutoCropEnabled,
            isAutoSnapLevelEnabled = isAutoSnapLevelEnabled,
            selectedFilter = selectedFilter,
            onClose = onClose,
            onToggleFlash = {
                flashMode = when (flashMode) {
                    CameraFlashMode.OFF -> CameraFlashMode.ON
                    CameraFlashMode.ON -> CameraFlashMode.AUTO
                    CameraFlashMode.AUTO -> CameraFlashMode.OFF
                }
                val torchOn = (flashMode == CameraFlashMode.ON)
                cameraControl?.cameraControl?.enableTorch(torchOn)
            },
            onToggleHd = { isHdMode = !isHdMode },
            onToggleMoreMenu = { showMoreMenu = !showMoreMenu },
            onToggleLevel = { showLevelIndicator = !showLevelIndicator },
            onToggleSound = {
                val next = !isShutterSoundEnabled
                isShutterSoundEnabled = next
                store.isShutterSoundEnabled = next
                Toast.makeText(context, if (next) "Âm thanh chụp: Bật" else "Âm thanh chụp: Đã tắt (Im lặng)", Toast.LENGTH_SHORT).show()
            },
            onToggleAutoCrop = {
                val next = !isAutoCropEnabled
                isAutoCropEnabled = next
                store.isAutoCropEnabled = next
                Toast.makeText(context, if (next) "Tự động cắt viền: Bật" else "Tự động cắt viền: Tắt (Giữ toàn khung)", Toast.LENGTH_SHORT).show()
            },
            onToggleAutoSnapLevel = {
                val next = !isAutoSnapLevelEnabled
                isAutoSnapLevelEnabled = next
                store.isAutoSnapLevelEnabled = next
                Toast.makeText(context, if (next) "Tự chụp khi cân bằng: Bật" else "Tự chụp khi cân bằng: Tắt", Toast.LENGTH_SHORT).show()
            },
            onCycleFilter = {
                val next = when (selectedFilter) {
                    DocumentFilter.MAGIC_DOC -> DocumentFilter.BW
                    DocumentFilter.BW -> DocumentFilter.ORIGINAL
                    DocumentFilter.ORIGINAL -> DocumentFilter.MAGIC_DOC
                }
                selectedFilter = next
                store.defaultFilter = next
                Toast.makeText(context, "Bộ lọc: ${next.label}", Toast.LENGTH_SHORT).show()
            }
        )

        // 5. BOTTOM CONTROLS: Mode [Chụp đơn | Hàng loạt], Shutter, Thumbnail [Sửa đổi], Nút [Hoàn thành]
        BottomControlsBar(
            scanTab = scanTab,
            capturedCount = capturedPhotos.size,
            latestPhoto = capturedPhotos.lastOrNull(),
            isShooting = isShooting,
            shutterScale = shutterButtonScale.value,
            onSelectTab = { scanTab = it },
            onShutterClick = { performShutter() },
            onOpenReview = {
                if (capturedPhotos.isNotEmpty()) {
                    reviewStartIndex = capturedPhotos.size - 1
                    isReviewing = true
                }
            },
            onFinish = {
                if (capturedPhotos.isNotEmpty()) {
                    onFinishScan(capturedPhotos.toList(), QuickSendAction.NONE)
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // 6. THANH 1 CHẠM GỬI NHANH CHO CHẾ ĐỘ CHỤP ĐƠN (Zero-Latency 2-tap UX)
        if (quickSendPhoto != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.BottomCenter
            ) {
                QuickSendSheet(
                    photo = quickSendPhoto!!,
                    onSendPdf = {
                        val p = quickSendPhoto!!
                        quickSendPhoto = null
                        onFinishScan(listOf(p), QuickSendAction.SEND_PDF)
                    },
                    onSendImages = {
                        val p = quickSendPhoto!!
                        quickSendPhoto = null
                        onFinishScan(listOf(p), QuickSendAction.SEND_IMAGES)
                    },
                    onEdit = {
                        val p = quickSendPhoto!!
                        quickSendPhoto = null
                        reviewStartIndex = capturedPhotos.indexOfFirst { it.file.absolutePath == p.file.absolutePath }.coerceAtLeast(0)
                        isReviewing = true
                    },
                    onRetake = {
                        val p = quickSendPhoto!!
                        quickSendPhoto = null
                        capturedPhotos.removeAll { it.file.absolutePath == p.file.absolutePath }
                        p.file.delete()
                    }
                )
            }
        }
    }

    // 7. Dialog Xem ảnh vừa chụp, Sửa (Xoay, Cắt viền, Đổi bộ lọc) hoặc Xóa
    if (isReviewing && capturedPhotos.isNotEmpty()) {
        SessionReviewDialog(
            photos = capturedPhotos,
            initialIndex = reviewStartIndex,
            onDismiss = { isReviewing = false },
            onRotate = { idx ->
                val cur = capturedPhotos[idx]
                capturedPhotos[idx] = cur.copy(rotation = (cur.rotation + 90) % 360)
            },
            onCrop = { idx ->
                val cur = capturedPhotos[idx]
                scope.launch(Dispatchers.Default) {
                    DocumentCropper.processFile(cur.file, filter = cur.filter)
                    withContext(Dispatchers.Main) {
                        capturedPhotos[idx] = cur.copy(id = UUID.randomUUID().toString())
                        Toast.makeText(context, "Đã tự động cắt và nắn phẳng trang ${idx + 1}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onApplyFilter = { idx, filter ->
                val cur = capturedPhotos[idx]
                capturedPhotos[idx] = cur.copy(filter = filter, id = UUID.randomUUID().toString())
                scope.launch(Dispatchers.Default) {
                    DocumentCropper.filterFile(cur.file, filter)
                    withContext(Dispatchers.Main) {
                        capturedPhotos[idx] = cur.copy(id = UUID.randomUUID().toString())
                    }
                }
            },
            onDelete = { idx ->
                val removed = capturedPhotos.removeAt(idx)
                removed.file.delete()
                if (capturedPhotos.isEmpty()) {
                    isReviewing = false
                }
            },
            onFinish = {
                isReviewing = false
                onFinishScan(capturedPhotos.toList(), QuickSendAction.NONE)
            },
            onSendPdf = {
                isReviewing = false
                onFinishScan(capturedPhotos.toList(), QuickSendAction.SEND_PDF)
            },
            onSendImages = {
                isReviewing = false
                onFinishScan(capturedPhotos.toList(), QuickSendAction.SEND_IMAGES)
            }
        )
    }
}

// =========================================================================
// CÂN BẰNG ĐIỆN THOẠI (Bong bóng nước + Dấu cộng tâm màu xanh)
// =========================================================================
@Composable
private fun LevelIndicatorOverlay(
    levelState: DeviceLevelState,
    modifier: Modifier = Modifier
) {
    val ringColor = if (levelState.isBalanced) Color(0xFF00E676) else Color.White.copy(alpha = 0.8f)
    val crossColor = if (levelState.isBalanced) Color(0xFF00E676) else Color(0xFF00BFA5)

    Box(
        modifier = modifier
            .size(76.dp),
        contentAlignment = Alignment.Center
    ) {
        // Vòng tròn ngoài & Dấu cộng trung tâm
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f - 4f

            // Vòng tròn ngoài
            drawCircle(
                color = ringColor,
                radius = radius,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )

            // Dấu cộng trung tâm
            val crossSize = 10.dp.toPx()
            drawLine(
                color = crossColor,
                start = Offset(center.x - crossSize, center.y),
                end = Offset(center.x + crossSize, center.y),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = crossColor,
                start = Offset(center.x, center.y - crossSize),
                end = Offset(center.x, center.y + crossSize),
                strokeWidth = 2.dp.toPx()
            )
        }

        // Bóng nước di chuyển theo độ nghiêng
        val maxOffsetPx = 28.dp
        val bubbleOffsetXPx = (levelState.bubbleX * maxOffsetPx.value)
        val bubbleOffsetYPx = (levelState.bubbleY * maxOffsetPx.value)

        Box(
            modifier = Modifier
                .offset { IntOffset(bubbleOffsetXPx.roundToInt(), bubbleOffsetYPx.roundToInt()) }
                .size(16.dp)
                .clip(CircleShape)
                .background(if (levelState.isBalanced) Color(0xFF00E676) else Color.White.copy(alpha = 0.9f))
                .border(
                    width = 1.dp,
                    color = if (levelState.isBalanced) Color.White else Color.Black.copy(alpha = 0.4f),
                    shape = CircleShape
                )
        )
    }
}

@Composable
private fun TiltAngleGauge(
    tiltAngle: Float,
    isBalanced: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isBalanced) Color(0xCC00BFA5) else Color.Black.copy(alpha = 0.55f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isBalanced) Color(0xFF00E676) else Color(0xFFFF5252))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${tiltAngle.roundToInt()}°",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// =========================================================================
// TOP CAMERA BAR
// =========================================================================
@Composable
private fun TopCameraBar(
    flashMode: CameraFlashMode,
    isHdMode: Boolean,
    showMoreMenu: Boolean,
    showLevelIndicator: Boolean,
    isShutterSoundEnabled: Boolean,
    isAutoCropEnabled: Boolean,
    isAutoSnapLevelEnabled: Boolean,
    selectedFilter: DocumentFilter,
    onClose: () -> Unit,
    onToggleFlash: () -> Unit,
    onToggleHd: () -> Unit,
    onToggleMoreMenu: () -> Unit,
    onToggleLevel: () -> Unit,
    onToggleSound: () -> Unit,
    onToggleAutoCrop: () -> Unit,
    onToggleAutoSnapLevel: () -> Unit,
    onCycleFilter: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Đóng", tint = Color.White)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Flash
            IconButton(onClick = onToggleFlash) {
                val icon = when (flashMode) {
                    CameraFlashMode.OFF -> Icons.Default.FlashOff
                    CameraFlashMode.ON -> Icons.Default.FlashOn
                    CameraFlashMode.AUTO -> Icons.Default.FlashAuto
                }
                Icon(icon, contentDescription = "Flash", tint = if (flashMode != CameraFlashMode.OFF) Color(0xFFFFEB3B) else Color.White)
            }

            // Bộ lọc / Làm đẹp (Bấm đổi nhanh Gốc -> Tài liệu -> Đen trắng)
            IconButton(onClick = onCycleFilter) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = "Bộ lọc: ${selectedFilter.label}",
                    tint = if (selectedFilter != DocumentFilter.ORIGINAL) Color(0xFF00BFA5) else Color.White
                )
            }

            // HD Button
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isHdMode) Color(0xFF00BFA5) else Color.White.copy(alpha = 0.3f),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleHd)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "HD",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(4.dp))

            // More Menu (...)
            Box {
                IconButton(onClick = onToggleMoreMenu) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Cài đặt máy ảnh", tint = Color.White)
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = onToggleMoreMenu
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(if (isShutterSoundEnabled) "🔊 Âm thanh khi chụp: Bật" else "🔇 Âm thanh khi chụp: Đã tắt")
                        },
                        onClick = {
                            onToggleSound()
                            onToggleMoreMenu()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (isAutoCropEnabled) "✂️ Tự động cắt viền: Bật" else "✂️ Tự động cắt viền: Tắt")
                        },
                        onClick = {
                            onToggleAutoCrop()
                            onToggleMoreMenu()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (showLevelIndicator) "🎯 Cân bằng điện thoại: Bật" else "🎯 Cân bằng điện thoại: Tắt")
                        },
                        onClick = {
                            onToggleLevel()
                            onToggleMoreMenu()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (isAutoSnapLevelEnabled) "⚡ Tự chụp khi cân bằng: Bật" else "⚡ Tự chụp khi cân bằng: Tắt")
                        },
                        onClick = {
                            onToggleAutoSnapLevel()
                            onToggleMoreMenu()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text("🎨 Bộ lọc: ${selectedFilter.label} (Chạm để đổi)")
                        },
                        onClick = {
                            onCycleFilter()
                            onToggleMoreMenu()
                        }
                    )
                }
            }
        }
    }
}

// =========================================================================
// THANH GỬI NHANH 1 CHẠM (CHO CHẾ ĐỘ CHỤP ĐƠN - ZERO-LATENCY UX)
// =========================================================================
@Composable
private fun QuickSendSheet(
    photo: CapturedPhoto,
    onSendPdf: () -> Unit,
    onSendImages: () -> Unit,
    onEdit: () -> Unit,
    onRetake: () -> Unit
) {
    Surface(
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = 16.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Thanh gạt nhỏ
            Box(
                modifier = Modifier
                    .size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f))
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mini preview của ảnh vừa chụp
                Box(
                    modifier = Modifier
                        .size(width = 50.dp, height = 66.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .clickable(onClick = onEdit)
                ) {
                    AsyncImage(
                        model = photo.file,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationZ = photo.rotation.toFloat() }
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Đã quét 1 trang",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Bộ lọc: ${photo.filter.label} • 2 chạm là gửi",
                        color = Color(0xFF00BFA5),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // 2 Nút gửi lớn 1 chạm
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSendPdf,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFA5)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("⚡ Gửi PDF", fontWeight = FontWeight.Bold, color = Color.White)
                }

                FilledTonalButton(
                    onClick = onSendImages,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF333333),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("⚡ Gửi Ảnh", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Hàng nút phụ: [Chụp lại] & [Chỉnh sửa]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onRetake) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Chụp lại", color = Color(0xFFFF5252))
                }

                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Crop, contentDescription = null, tint = Color(0xFF00BFA5), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Chỉnh sửa / Cắt", color = Color(0xFF00BFA5))
                }
            }
        }
    }
}

// =========================================================================
// BOTTOM CONTROLS BAR: Mode Tabs, Shutter, Thumbnail [Sửa đổi], [Hoàn thành]
// =========================================================================
@Composable
private fun BottomControlsBar(
    scanTab: CameraScanTab,
    capturedCount: Int,
    latestPhoto: CapturedPhoto?,
    isShooting: Boolean,
    shutterScale: Float,
    onSelectTab: (CameraScanTab) -> Unit,
    onShutterClick: () -> Unit,
    onOpenReview: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Tab chọn: [Chụp đơn] | [Hàng loạt]
        Row(
            modifier = Modifier.padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CameraTabItem(
                title = "Chụp đơn",
                isSelected = scanTab == CameraScanTab.SINGLE,
                onClick = { onSelectTab(CameraScanTab.SINGLE) }
            )
            CameraTabItem(
                title = "Hàng loạt",
                isSelected = scanTab == CameraScanTab.BATCH,
                onClick = { onSelectTab(CameraScanTab.BATCH) }
            )
        }

        // Hàng nút chính: [Retake/Undo] [Nút Chụp] [Thumbnail + Sửa đổi / Hoàn thành]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Nút bên trái (Quay lại / Undo)
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = {
                        if (capturedCount > 0) onOpenReview()
                    },
                    enabled = capturedCount > 0
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Xem lại",
                        tint = if (capturedCount > 0) Color.White else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Nút Chụp chính ở giữa (Vòng tròn ngoài ngọc lam, nút trong trắng)
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .scale(shutterScale)
                    .clip(CircleShape)
                    .border(width = 4.dp, color = Color(0xFF00BFA5), shape = CircleShape)
                    .clickable(enabled = !isShooting, onClick = onShutterClick),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }

            // Cụm bên phải: Thumbnail + Badge đỏ + Nút "Sửa đổi" & Nút "Hoàn thành"
            Box(
                modifier = Modifier.size(width = 84.dp, height = 76.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (capturedCount > 0 && latestPhoto != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable(onClick = onOpenReview)
                    ) {
                        // Thumbnail có badge đếm số trang đỏ
                        Box(
                            modifier = Modifier
                                .size(width = 44.dp, height = 44.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.DarkGray)
                        ) {
                            AsyncImage(
                                model = latestPhoto.file,
                                contentDescription = "Xem ảnh vừa chụp",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { rotationZ = latestPhoto.rotation.toFloat() }
                            )

                            // Badge đỏ đếm số ảnh
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFFF5252),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .size(18.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "$capturedCount",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        // Nhãn "Sửa đổi" nền ngọc lam
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF00BFA5),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = "Sửa đổi",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }

        // Nút "Hoàn thành" ngọc lam (khi đã có ít nhất 1 ảnh chụp)
        AnimatedVisibility(
            visible = capturedCount > 0,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF00BFA5),
                modifier = Modifier
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onFinish)
            ) {
                Text(
                    text = "Hoàn thành (${capturedCount})",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun CameraTabItem(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = title,
            color = if (isSelected) Color(0xFF00BFA5) else Color.White.copy(alpha = 0.7f),
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 15.sp
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .width(24.dp)
                    .height(2.5.dp)
                    .background(Color(0xFF00BFA5), RoundedCornerShape(2.dp))
            )
        }
    }
}

// =========================================================================
// HELPER: Chụp ảnh bằng CameraX ImageCapture và lưu file
// =========================================================================
private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onSuccess: (File) -> Unit,
    onError: (Exception) -> Unit
) {
    val dir = File(context.cacheDir, "scan_session").apply { if (!exists()) mkdirs() }
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val photoFile = File(dir, "SCAN_${timeStamp}.jpg")

    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onSuccess(photoFile)
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception)
            }
        }
    )
}

// =========================================================================
// KHUNG VIỀN NHẬN DIỆN TÀI LIỆU THỜI GIAN THỰC (REAL-TIME DOCUMENT FRAME)
// =========================================================================
@Composable
private fun LiveDocumentFrameOverlay(
    cornersNorm: DocumentCornersNorm?,
    modifier: Modifier = Modifier
) {
    val target = cornersNorm ?: remember { DocumentCornersNorm.defaultGuide() }

    val tlX by animateFloatAsState(target.topLeft.x, tween(90), label = "tlX")
    val tlY by animateFloatAsState(target.topLeft.y, tween(90), label = "tlY")
    val trX by animateFloatAsState(target.topRight.x, tween(90), label = "trX")
    val trY by animateFloatAsState(target.topRight.y, tween(90), label = "trY")
    val brX by animateFloatAsState(target.bottomRight.x, tween(90), label = "brX")
    val brY by animateFloatAsState(target.bottomRight.y, tween(90), label = "brY")
    val blX by animateFloatAsState(target.bottomLeft.x, tween(90), label = "blX")
    val blY by animateFloatAsState(target.bottomLeft.y, tween(90), label = "blY")

    val isDetected = cornersNorm?.isDetected == true
    val frameColor = if (isDetected) Color(0xFF00BFA5) else Color.White.copy(alpha = 0.35f)
    val cornerColor = if (isDetected) Color(0xFF00E676) else Color.White.copy(alpha = 0.75f)
    val fillColor = if (isDetected) Color(0x2800BFA5) else Color.Transparent

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Quy đổi tọa độ từ Camera sang màn hình chính xác theo ScaleType.FILL_CENTER
        val camW = 480f
        val camH = 640f
        val camAspect = camW / camH // 0.75 (Tỉ lệ ảnh camera sau khi xoay về Portrait)
        val viewAspect = w / h

        val scaledW: Float
        val scaledH: Float
        val offX: Float
        val offY: Float

        if (viewAspect < camAspect) {
            // Màn hình điện thoại hẹp và dài hơn ảnh camera -> preview khớp chiều cao, cắt 2 bên
            val scale = h / camH
            scaledW = camW * scale
            scaledH = h
            offX = -(scaledW - w) / 2f
            offY = 0f
        } else {
            // Màn hình rộng hơn ảnh camera -> preview khớp chiều rộng, cắt trên dưới
            val scale = w / camW
            scaledW = w
            scaledH = camH * scale
            offX = 0f
            offY = -(scaledH - h) / 2f
        }

        fun mapPoint(nx: Float, ny: Float): Offset {
            val px = nx * scaledW + offX
            val py = ny * scaledH + offY
            return Offset(px, py)
        }

        val p1 = mapPoint(tlX, tlY)
        val p2 = mapPoint(trX, trY)
        val p3 = mapPoint(brX, brY)
        val p4 = mapPoint(blX, blY)

        val path = Path().apply {
            moveTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            lineTo(p3.x, p3.y)
            lineTo(p4.x, p4.y)
            close()
        }

        // 1. Phủ màu mờ vùng tài liệu khi đã khóa thành công
        if (fillColor != Color.Transparent) {
            drawPath(path, color = fillColor)
        }

        // 2. Viền khung
        drawPath(
            path = path,
            color = frameColor,
            style = Stroke(width = if (isDetected) 2.8.dp.toPx() else 1.8.dp.toPx())
        )

        // 3. 4 góc định vị (Corner Reticle Brackets)
        val bracketLen = if (isDetected) 26.dp.toPx() else 20.dp.toPx()
        val bracketWidth = if (isDetected) 4.5.dp.toPx() else 3.dp.toPx()
        val cap = StrokeCap.Round

        // Góc trên - trái
        drawLine(cornerColor, p1, Offset(p1.x + bracketLen, p1.y), strokeWidth = bracketWidth, cap = cap)
        drawLine(cornerColor, p1, Offset(p1.x, p1.y + bracketLen), strokeWidth = bracketWidth, cap = cap)

        // Góc trên - phải
        drawLine(cornerColor, p2, Offset(p2.x - bracketLen, p2.y), strokeWidth = bracketWidth, cap = cap)
        drawLine(cornerColor, p2, Offset(p2.x, p2.y + bracketLen), strokeWidth = bracketWidth, cap = cap)

        // Góc dưới - phải
        drawLine(cornerColor, p3, Offset(p3.x - bracketLen, p3.y), strokeWidth = bracketWidth, cap = cap)
        drawLine(cornerColor, p3, Offset(p3.x, p3.y - bracketLen), strokeWidth = bracketWidth, cap = cap)

        // Góc dưới - trái
        drawLine(cornerColor, p4, Offset(p4.x + bracketLen, p4.y), strokeWidth = bracketWidth, cap = cap)
        drawLine(cornerColor, p4, Offset(p4.x, p4.y - bracketLen), strokeWidth = bracketWidth, cap = cap)
    }
}

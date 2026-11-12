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
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
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
    onFinishScan: (List<CapturedPhoto>) -> Unit
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

    // State Camera & Sensor
    val levelState by rememberDeviceLevel()
    var scanTab by remember { mutableStateOf(initialTab) }
    var flashMode by remember { mutableStateOf(CameraFlashMode.OFF) }
    var isHdMode by remember { mutableStateOf(true) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showLevelIndicator by remember { mutableStateOf(true) }

    val capturedPhotos = remember { mutableStateListOf<CapturedPhoto>() }
    var isReviewing by remember { mutableStateOf(false) }
    var reviewStartIndex by remember { mutableIntStateOf(0) }

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
    var cameraControl: Camera? by remember { mutableStateOf(null) }

    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(Unit) {
        onDispose { sound.release() }
    }

    // Haptic feedback khi điện thoại đạt mức cân bằng hoàn hảo
    var wasBalanced by remember { mutableStateOf(false) }
    LaunchedEffect(levelState.isBalanced) {
        if (levelState.isBalanced && !wasBalanced) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        wasBalanced = levelState.isBalanced
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

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        cameraProvider.unbindAll()
                        cameraControl = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

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
            showLevelIndicator = showLevelIndicator,
            onToggleLevel = { showLevelIndicator = !showLevelIndicator }
        )

        // 5. BOTTOM CONTROLS: Mode [Chụp đơn | Hàng loạt], Shutter, Thumbnail [Sửa đổi], Nút [Hoàn thành]
        BottomControlsBar(
            scanTab = scanTab,
            capturedCount = capturedPhotos.size,
            latestPhoto = capturedPhotos.lastOrNull(),
            isShooting = isShooting,
            shutterScale = shutterButtonScale.value,
            onSelectTab = { scanTab = it },
            onShutterClick = {
                if (isShooting) return@BottomControlsBar
                isShooting = true

                // Chớp animation
                scope.launch {
                    sound.play(MediaActionSound.SHUTTER_CLICK)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    shutterButtonScale.animateTo(0.85f, tween(80))
                    shutterButtonScale.animateTo(1f, tween(100))
                }
                scope.launch {
                    shutterFlashAlpha.snapTo(0.6f)
                    shutterFlashAlpha.animateTo(0f, tween(200))
                }

                // Chụp và lưu ảnh vào cache session
                takePhoto(
                    context = context,
                    imageCapture = imageCapture,
                    onSuccess = { file ->
                        val item = CapturedPhoto(file = file)
                        capturedPhotos.add(item)
                        isShooting = false

                        // Nếu là chế độ chụp đơn: mở ngay màn hình xem lại / hoàn thành
                        if (scanTab == CameraScanTab.SINGLE) {
                            reviewStartIndex = capturedPhotos.size - 1
                            isReviewing = true
                        }
                    },
                    onError = {
                        isShooting = false
                        Toast.makeText(context, "Lỗi khi chụp: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                )
            },
            onOpenReview = {
                if (capturedPhotos.isNotEmpty()) {
                    reviewStartIndex = capturedPhotos.size - 1
                    isReviewing = true
                }
            },
            onFinish = {
                if (capturedPhotos.isNotEmpty()) {
                    onFinishScan(capturedPhotos.toList())
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // 6. Dialog Xem ảnh vừa chụp, Sửa hoặc Xóa
    if (isReviewing && capturedPhotos.isNotEmpty()) {
        SessionReviewDialog(
            photos = capturedPhotos,
            initialIndex = reviewStartIndex,
            onDismiss = { isReviewing = false },
            onRotate = { idx ->
                val cur = capturedPhotos[idx]
                capturedPhotos[idx] = cur.copy(rotation = (cur.rotation + 90) % 360)
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
                onFinishScan(capturedPhotos.toList())
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
    onClose: () -> Unit,
    onToggleFlash: () -> Unit,
    onToggleHd: () -> Unit,
    onToggleMoreMenu: () -> Unit,
    onToggleLevel: () -> Unit
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

            // Bộ lọc / Làm đẹp
            IconButton(onClick = {}) {
                Icon(Icons.Default.AutoAwesome, contentDescription = "Bộ lọc", tint = Color.White)
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
                    Icon(Icons.Default.MoreVert, contentDescription = "Thêm", tint = Color.White)
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = onToggleMoreMenu
                ) {
                    DropdownMenuItem(
                        text = { Text(if (showLevelIndicator) "Tắt cân bằng điện thoại" else "Bật cân bằng điện thoại") },
                        onClick = {
                            onToggleLevel()
                            onToggleMoreMenu()
                        }
                    )
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

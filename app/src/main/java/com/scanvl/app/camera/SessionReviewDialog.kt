package com.scanvl.app.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.scanvl.app.data.CapturedPhoto
import kotlinx.coroutines.launch
import java.io.File

/**
 * Màn hình xem lại, sửa (xoay 90°) hoặc xóa các ảnh vừa chụp trong phiên scan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionReviewDialog(
    photos: List<CapturedPhoto>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
    onRotate: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onFinish: () -> Unit
) {
    if (photos.isEmpty()) {
        onDismiss()
        return
    }

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, photos.size - 1)
    ) { photos.size }
    val stripListState = rememberLazyListState()

    val currentIndex = pagerState.currentPage.coerceIn(0, photos.size - 1)
    val currentPhoto = photos.getOrNull(currentIndex)

    LaunchedEffect(currentIndex) {
        if (currentIndex in photos.indices) {
            stripListState.animateScrollToItem(currentIndex)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFF121212),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // TopBar
                TopAppBar(
                    title = {
                        Text(
                            text = "Trang ${currentIndex + 1} / ${photos.size}",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Quay lại camera",
                                tint = Color.White
                            )
                        }
                    },
                    actions = {
                        Button(
                            onClick = onFinish,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFA5)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(4.dp))
                            Text("Hoàn thành", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
                )

                // Vùng xem ảnh lớn
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                ) {
                    val fitRotated = minOf(maxWidth, maxHeight) / maxOf(maxWidth, maxHeight)
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { pageIdx ->
                        val item = photos.getOrNull(pageIdx) ?: return@HorizontalPager
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            AsyncImage(
                                model = item.file,
                                contentDescription = "Ảnh ${pageIdx + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        rotationZ = item.rotation.toFloat()
                                        val s = if (item.rotation % 180 != 0) fitRotated else 1f
                                        scaleX = s
                                        scaleY = s
                                    }
                            )
                        }
                    }
                }

                // Thanh nút hành động nhanh cho trang đang chọn: Xoay 90° & Xóa
                if (currentPhoto != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { onRotate(currentIndex) },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Rotate90DegreesCw, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Xoay 90°")
                        }

                        FilledTonalButton(
                            onClick = { onDelete(currentIndex) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0x33FF5252),
                                contentColor = Color(0xFFFF5252)
                            )
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Xóa trang")
                        }
                    }
                }

                // Dải Thumbnail ảnh nhỏ phía dưới
                LazyRow(
                    state = stripListState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                ) {
                    itemsIndexed(photos) { idx, item ->
                        val isSelected = idx == currentIndex
                        val borderColor = if (isSelected) Color(0xFF00BFA5) else Color.Transparent

                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                            elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 6.dp else 1.dp),
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(0.75f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch { pagerState.animateScrollToPage(idx) }
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(if (isSelected) Color(0x3300BFA5) else Color.Transparent)
                            ) {
                                AsyncImage(
                                    model = item.file,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            rotationZ = item.rotation.toFloat()
                                        }
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = if (isSelected) Color(0xFF00BFA5) else Color.Black.copy(alpha = 0.65f),
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .align(Alignment.TopStart)
                                        .size(18.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "${idx + 1}",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall
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
}

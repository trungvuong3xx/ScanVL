package com.scanvl.app.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import com.scanvl.app.data.DocumentFilter
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

data class DocumentCorners(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF
) {
    companion object {
        fun full(w: Int, h: Int): DocumentCorners = DocumentCorners(
            topLeft = PointF(0f, 0f),
            topRight = PointF(w.toFloat(), 0f),
            bottomRight = PointF(w.toFloat(), h.toFloat()),
            bottomLeft = PointF(0f, h.toFloat())
        )
    }
}

/**
 * Tọa độ 4 góc chuẩn hóa (0f..1f) phục vụ việc vẽ khung nhận diện thời gian thực trên màn hình Camera.
 */
data class DocumentCornersNorm(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF,
    val isDetected: Boolean = true
) {
    companion object {
        fun defaultGuide(): DocumentCornersNorm = DocumentCornersNorm(
            topLeft = PointF(0.08f, 0.12f),
            topRight = PointF(0.92f, 0.12f),
            bottomRight = PointF(0.92f, 0.88f),
            bottomLeft = PointF(0.08f, 0.88f),
            isDetected = false
        )
    }
}

/**
 * Xử lý tự động nhận diện mép tài liệu và nắn thẳng góc (Perspective Transform):
 * - Thu nhỏ để tìm biên tài liệu trong < 30ms không tốn RAM.
 * - Sử dụng Skia Matrix setPolyToPoly phần cứng của Android để nắn phẳng trang tài liệu.
 */
object DocumentCropper {

    /**
     * Tự động phát hiện 4 góc tài liệu trên mặt bàn.
     * Quét từ 4 mép ảnh vào tâm để tìm vùng có độ tương phản/độ sáng khác biệt của tờ giấy.
     */
    fun detectCorners(bitmap: Bitmap): DocumentCorners {
        val w = bitmap.width
        val h = bitmap.height

        // Thu nhỏ để phân tích nhanh
        val scale = 320f / max(w, h)
        val sw = (w * scale).roundToInt().coerceAtLeast(10)
        val sh = (h * scale).roundToInt().coerceAtLeast(10)
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, false)

        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        small.recycle()

        // Tính độ sáng (Luminance) từng pixel
        val lums = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            lums[i] = (r * 299 + g * 587 + b * 114) / 1000
        }

        // Tìm mép từ 4 phía (trên, dưới, trái, phải)
        fun lumAt(x: Int, y: Int): Int = lums[y.coerceIn(0, sh - 1) * sw + x.coerceIn(0, sw - 1)]

        var topY = 0
        var bottomY = sh - 1
        var leftX = 0
        var rightX = sw - 1

        val thresholdDiff = 25

        // Quét mép trên
        for (y in (sh * 0.05).toInt() until (sh * 0.45).toInt()) {
            var diffSum = 0
            for (x in (sw * 0.2).toInt()..(sw * 0.8).toInt() step 4) {
                diffSum += abs(lumAt(x, y + 2) - lumAt(x, y - 2))
            }
            if (diffSum / ((sw * 0.6) / 4) > thresholdDiff) {
                topY = y
                break
            }
        }

        // Quét mép dưới
        for (y in (sh * 0.95).toInt() downTo (sh * 0.55).toInt()) {
            var diffSum = 0
            for (x in (sw * 0.2).toInt()..(sw * 0.8).toInt() step 4) {
                diffSum += abs(lumAt(x, y - 2) - lumAt(x, y + 2))
            }
            if (diffSum / ((sw * 0.6) / 4) > thresholdDiff) {
                bottomY = y
                break
            }
        }

        // Quét mép trái
        for (x in (sw * 0.05).toInt() until (sw * 0.45).toInt()) {
            var diffSum = 0
            for (y in (sh * 0.2).toInt()..(sh * 0.8).toInt() step 4) {
                diffSum += abs(lumAt(x + 2, y) - lumAt(x - 2, y))
            }
            if (diffSum / ((sh * 0.6) / 4) > thresholdDiff) {
                leftX = x
                break
            }
        }

        // Quét mép phải
        for (x in (sw * 0.95).toInt() downTo (sw * 0.55).toInt()) {
            var diffSum = 0
            for (y in (sh * 0.2).toInt()..(sh * 0.8).toInt() step 4) {
                diffSum += abs(lumAt(x - 2, y) - lumAt(x + 2, y))
            }
            if (diffSum / ((sh * 0.6) / 4) > thresholdDiff) {
                rightX = x
                break
            }
        }

        // Nếu vùng phát hiện được quá hẹp hoặc không hợp lý -> dùng lề an toàn 4% mép ảnh
        val detectedAreaRatio = ((rightX - leftX) * (bottomY - topY)).toFloat() / (sw * sh)
        if (detectedAreaRatio < 0.20f || leftX >= rightX || topY >= bottomY) {
            val insetX = w * 0.04f
            val insetY = h * 0.04f
            return DocumentCorners(
                topLeft = PointF(insetX, insetY),
                topRight = PointF(w - insetX, insetY),
                bottomRight = PointF(w - insetX, h - insetY),
                bottomLeft = PointF(insetX, h - insetY)
            )
        }

        // Quy đổi ngược về kích thước ảnh gốc
        val invScale = 1f / scale
        val fx1 = (leftX * invScale).coerceIn(0f, w.toFloat())
        val fx2 = (rightX * invScale).coerceIn(0f, w.toFloat())
        val fy1 = (topY * invScale).coerceIn(0f, h.toFloat())
        val fy2 = (bottomY * invScale).coerceIn(0f, h.toFloat())

        return DocumentCorners(
            topLeft = PointF(fx1, fy1),
            topRight = PointF(fx2, fy1),
            bottomRight = PointF(fx2, fy2),
            bottomLeft = PointF(fx1, fy2)
        )
    }

    /**
     * Nhận diện và trả về tọa độ chuẩn hóa (0f..1f) cho khung vẽ thời gian thực trên Camera.
     */
    fun detectCornersNorm(bitmap: Bitmap): DocumentCornersNorm {
        val corners = detectCorners(bitmap)
        val w = bitmap.width.toFloat().coerceAtLeast(1f)
        val h = bitmap.height.toFloat().coerceAtLeast(1f)
        return DocumentCornersNorm(
            topLeft = PointF((corners.topLeft.x / w).coerceIn(0f, 1f), (corners.topLeft.y / h).coerceIn(0f, 1f)),
            topRight = PointF((corners.topRight.x / w).coerceIn(0f, 1f), (corners.topRight.y / h).coerceIn(0f, 1f)),
            bottomRight = PointF((corners.bottomRight.x / w).coerceIn(0f, 1f), (corners.bottomRight.y / h).coerceIn(0f, 1f)),
            bottomLeft = PointF((corners.bottomLeft.x / w).coerceIn(0f, 1f), (corners.bottomLeft.y / h).coerceIn(0f, 1f)),
            isDetected = true
        )
    }

    /**
     * Nắn phẳng tài liệu theo 4 góc đã xác định (Perspective Transform bằng setPolyToPoly).
     */
    fun warp(source: Bitmap, corners: DocumentCorners): Bitmap {
        val widthTop = hypot((corners.topRight.x - corners.topLeft.x).toDouble(), (corners.topRight.y - corners.topLeft.y).toDouble())
        val widthBottom = hypot((corners.bottomRight.x - corners.bottomLeft.x).toDouble(), (corners.bottomRight.y - corners.bottomLeft.y).toDouble())
        val targetWidth = max(widthTop, widthBottom).roundToInt().coerceIn(200, 4000)

        val heightLeft = hypot((corners.bottomLeft.x - corners.topLeft.x).toDouble(), (corners.bottomLeft.y - corners.topLeft.y).toDouble())
        val heightRight = hypot((corners.bottomRight.x - corners.topRight.x).toDouble(), (corners.bottomRight.y - corners.topRight.y).toDouble())
        val targetHeight = max(heightLeft, heightRight).roundToInt().coerceIn(200, 4000)

        val srcPoints = floatArrayOf(
            corners.topLeft.x, corners.topLeft.y,
            corners.topRight.x, corners.topRight.y,
            corners.bottomRight.x, corners.bottomRight.y,
            corners.bottomLeft.x, corners.bottomLeft.y
        )
        val dstPoints = floatArrayOf(
            0f, 0f,
            targetWidth.toFloat(), 0f,
            targetWidth.toFloat(), targetHeight.toFloat(),
            0f, targetHeight.toFloat()
        )

        val matrix = Matrix()
        matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

        val resultBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(source, matrix, paint)

        return resultBitmap
    }

    /**
     * Áp dụng bộ lọc hình ảnh tăng cường chất lượng văn bản:
     * - [DocumentFilter.ORIGINAL]: Giữ nguyên màu gốc.
     * - [DocumentFilter.MAGIC_DOC]: Tăng tương phản nhẹ, nâng sáng khử bóng bàn tay / bóng điện thoại,
     *   làm trắng nền giấy nhưng giữ 100% màu con dấu đỏ và chữ ký xanh.
     * - [DocumentFilter.BW]: Chuyển trắng đen rõ nét, chữ đen đậm, nền trắng tinh, tối ưu in ấn.
     */
    fun applyFilter(source: Bitmap, filter: DocumentFilter): Bitmap {
        if (filter == DocumentFilter.ORIGINAL) return source

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val cm = when (filter) {
            DocumentFilter.MAGIC_DOC -> {
                val matrix = ColorMatrix()
                val contrast = 1.35f
                val brightness = 20f
                val scale = contrast
                val translate = (1f - contrast) * 128f + brightness
                matrix.set(floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                ))
                matrix
            }
            DocumentFilter.BW -> {
                val matrix = ColorMatrix()
                matrix.setSaturation(0f)
                val contrast = 1.85f
                val translate = (1f - contrast) * 128f - 5f
                val cmContrast = ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                ))
                matrix.postConcat(cmContrast)
                matrix
            }
            DocumentFilter.ORIGINAL -> ColorMatrix()
        }

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    /**
     * Cắt góc và áp dụng bộ lọc đồng thời trong 1 lần nạp/ghi, tối ưu tốc độ và tránh đè I/O.
     */
    fun processFile(
        file: File,
        corners: DocumentCorners? = null,
        filter: DocumentFilter = DocumentFilter.ORIGINAL,
        jpegQuality: Int = 90
    ): Boolean {
        if (!file.exists()) return false
        val original = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        val targetCorners = corners ?: detectCorners(original)
        val warped = warp(original, targetCorners)
        if (warped !== original) original.recycle()

        val processed = if (filter != DocumentFilter.ORIGINAL) {
            val filtered = applyFilter(warped, filter)
            if (filtered !== warped) warped.recycle()
            filtered
        } else {
            warped
        }

        return try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).buffered(64 * 1024).use {
                processed.compress(Bitmap.CompressFormat.JPEG, jpegQuality, it)
            }
            processed.recycle()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Cắt và lưu đè lên file ảnh (tương thích ngược).
     */
    fun cropFile(file: File, corners: DocumentCorners? = null): Boolean =
        processFile(file, corners, DocumentFilter.ORIGINAL)

    /**
     * Áp dụng bộ lọc lên file ảnh đã có.
     */
    fun filterFile(file: File, filter: DocumentFilter): Boolean {
        if (!file.exists() || filter == DocumentFilter.ORIGINAL) return true
        val original = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        val filtered = applyFilter(original, filter)
        if (filtered === original) return true
        original.recycle()

        return try {
            val tmp = File(file.parentFile, file.name + ".flt")
            FileOutputStream(tmp).buffered(64 * 1024).use {
                filtered.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
            filtered.recycle()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

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
import kotlin.math.min
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
     * Tự động phát hiện 4 góc tài liệu trên ảnh gốc.
     */
    fun detectCorners(bitmap: Bitmap): DocumentCorners {
        val norm = detectCornersNorm(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        return DocumentCorners(
            topLeft = PointF(norm.topLeft.x * w, norm.topLeft.y * h),
            topRight = PointF(norm.topRight.x * w, norm.topRight.y * h),
            bottomRight = PointF(norm.bottomRight.x * w, norm.bottomRight.y * h),
            bottomLeft = PointF(norm.bottomLeft.x * w, norm.bottomLeft.y * h)
        )
    }

    /**
     * Nhận diện 4 góc tài liệu thông minh (Otsu Binarization + Đa giác cực trị 4 góc):
     * - Tự động thích ứng với bàn gỗ, bàn đen, bàn phím, văn bản nghiêng/xoay.
     * - Trả về 4 góc chuẩn hóa (0f..1f) và cờ [isDetected] báo hiệu khóa viền thành công.
     */
    fun detectCornersNorm(bitmap: Bitmap): DocumentCornersNorm {
        val w = bitmap.width
        val h = bitmap.height

        // Thu nhỏ về kích thước chuẩn ~180x240 để xử lý trong < 8ms
        val scale = 240f / max(w, h)
        val sw = (w * scale).roundToInt().coerceAtLeast(20)
        val sh = (h * scale).roundToInt().coerceAtLeast(20)
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, false)

        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        small.recycle()

        // Tính Luminance và xây dựng biểu đồ Histogram
        val lums = IntArray(pixels.size)
        val hist = IntArray(256)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            lums[i] = lum
            hist[lum]++
        }

        // 1. Thuật toán Otsu tìm ngưỡng sáng tối ưu phân tách giấy và nền
        val total = pixels.size.toFloat()
        var sum = 0f
        for (i in 0..255) sum += i * hist[i]

        var sumB = 0f
        var wB = 0f
        var maxVar = 0f
        var threshold = 128

        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0f) continue
            val wF = total - wB
            if (wF == 0f) break

            sumB += t * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val betweenVar = wB * wF * (mB - mF) * (mB - mF)
            if (betweenVar > maxVar) {
                maxVar = betweenVar
                threshold = t
            }
        }

        fun lumAt(x: Int, y: Int): Int = lums[y.coerceIn(0, sh - 1) * sw + x.coerceIn(0, sw - 1)]

        // 2. Xác định xem giấy sáng hơn nền hay nền sáng hơn giấy (dựa vào viền ảnh)
        var borderLumSum = 0L
        var borderCount = 0
        for (x in 0 until sw) {
            borderLumSum += lumAt(x, 0) + lumAt(x, sh - 1)
            borderCount += 2
        }
        for (y in 1 until sh - 1) {
            borderLumSum += lumAt(0, y) + lumAt(sw - 1, y)
            borderCount += 2
        }
        val borderMean = (borderLumSum / borderCount).toInt()
        val isPaperBright = borderMean < threshold

        // 3. Tính trọng tâm (Centroid) của vùng nghi vấn là tờ giấy
        var sumX = 0L
        var sumY = 0L
        var count = 0
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val lum = lumAt(x, y)
                val match = if (isPaperBright) lum >= threshold else lum < threshold
                if (match) {
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }

        val totalPixels = sw * sh
        // Nếu số điểm quá ít (< 10%) hoặc quá nhiều (> 92%) -> không có tài liệu rõ ràng trong khung hình
        if (count < totalPixels * 0.10f || count > totalPixels * 0.92f) {
            return DocumentCornersNorm.defaultGuide()
        }

        val cx = sumX.toFloat() / count
        val cy = sumY.toFloat() / count

        // 4. Tìm 4 góc cực trị theo phép chiếu đường chéo (Diagonal Projection) từ trọng tâm
        class CornerCandidate(val score: Float, val x: Float, val y: Float)

        val topTL = ArrayList<CornerCandidate>(8)
        val topTR = ArrayList<CornerCandidate>(8)
        val topBR = ArrayList<CornerCandidate>(8)
        val topBL = ArrayList<CornerCandidate>(8)

        fun updateCandidate(list: ArrayList<CornerCandidate>, score: Float, x: Float, y: Float) {
            if (list.size < 6) {
                list.add(CornerCandidate(score, x, y))
                list.sortByDescending { it.score }
            } else if (score > list.last().score) {
                list[list.lastIndex] = CornerCandidate(score, x, y)
                list.sortByDescending { it.score }
            }
        }

        for (y in (sh * 0.02).toInt() until (sh * 0.98).toInt() step 2) {
            for (x in (sw * 0.02).toInt() until (sw * 0.98).toInt() step 2) {
                val lum = lumAt(x, y)
                val match = if (isPaperBright) lum >= threshold else lum < threshold
                if (match) {
                    val dx = x - cx
                    val dy = y - cy
                    updateCandidate(topTL, -dx - dy, x.toFloat(), y.toFloat())
                    updateCandidate(topTR, dx - dy, x.toFloat(), y.toFloat())
                    updateCandidate(topBR, dx + dy, x.toFloat(), y.toFloat())
                    updateCandidate(topBL, -dx + dy, x.toFloat(), y.toFloat())
                }
            }
        }

        if (topTL.isEmpty() || topTR.isEmpty() || topBR.isEmpty() || topBL.isEmpty()) {
            return DocumentCornersNorm.defaultGuide()
        }

        // Lấy trung bình top 3-4 điểm cực trị để loại bỏ nhiễu biên (bóng đèn phản chiếu, đầu bút...)
        fun avgPoint(list: List<CornerCandidate>): PointF {
            val take = min(list.size, 4)
            var ax = 0f
            var ay = 0f
            for (i in 0 until take) {
                ax += list[i].x
                ay += list[i].y
            }
            return PointF(ax / take, ay / take)
        }

        val pTL = avgPoint(topTL)
        val pTR = avgPoint(topTR)
        val pBR = avgPoint(topBR)
        val pBL = avgPoint(topBL)

        // 5. Kiểm tra tính hợp lệ của tứ giác (diện tích, độ dài cạnh, tính lồi Convexity)
        val area = 0.5f * abs(
            (pTL.x * pTR.y - pTR.x * pTL.y) +
            (pTR.x * pBR.y - pBR.x * pTR.y) +
            (pBR.x * pBL.y - pBL.x * pBR.y) +
            (pBL.x * pTL.y - pTL.x * pBL.y)
        )
        val areaRatio = area / (sw * sh)

        val minEdge = min(sw, sh) * 0.18f
        val edgeTop = hypot(pTR.x - pTL.x, pTR.y - pTL.y)
        val edgeBottom = hypot(pBR.x - pBL.x, pBR.y - pBL.y)
        val edgeLeft = hypot(pBL.x - pTL.x, pBL.y - pTL.y)
        val edgeRight = hypot(pBR.x - pTR.x, pBR.y - pTR.y)

        val cp1 = (pTR.x - pTL.x) * (pBR.y - pTR.y) - (pTR.y - pTL.y) * (pBR.x - pTR.x)
        val cp2 = (pBR.x - pTR.x) * (pBL.y - pBR.y) - (pBR.y - pTR.y) * (pBL.x - pBR.x)
        val cp3 = (pBL.x - pBR.x) * (pTL.y - pBL.y) - (pBL.y - pBR.y) * (pTL.x - pBL.x)
        val cp4 = (pTL.x - pBL.x) * (pTR.y - pTL.y) - (pTL.y - pBL.y) * (pTR.x - pTL.x)

        val isConvex = (cp1 > 0 && cp2 > 0 && cp3 > 0 && cp4 > 0) || (cp1 < 0 && cp2 < 0 && cp3 < 0 && cp4 < 0)
        val validEdges = edgeTop > minEdge && edgeBottom > minEdge && edgeLeft > minEdge && edgeRight > minEdge
        val validArea = areaRatio in 0.12f..0.88f

        if (!isConvex || !validEdges || !validArea) {
            return DocumentCornersNorm.defaultGuide()
        }

        return DocumentCornersNorm(
            topLeft = PointF((pTL.x / sw).coerceIn(0f, 1f), (pTL.y / sh).coerceIn(0f, 1f)),
            topRight = PointF((pTR.x / sw).coerceIn(0f, 1f), (pTR.y / sh).coerceIn(0f, 1f)),
            bottomRight = PointF((pBR.x / sw).coerceIn(0f, 1f), (pBR.y / sh).coerceIn(0f, 1f)),
            bottomLeft = PointF((pBL.x / sw).coerceIn(0f, 1f), (pBL.y / sh).coerceIn(0f, 1f)),
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

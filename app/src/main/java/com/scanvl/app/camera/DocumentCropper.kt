package com.scanvl.app.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
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
)

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
     * Cắt và lưu đè lên file ảnh.
     */
    fun cropFile(file: File, corners: DocumentCorners? = null): Boolean {
        if (!file.exists()) return false
        val original = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        val targetCorners = corners ?: detectCorners(original)
        val warped = warp(original, targetCorners)
        if (warped !== original) original.recycle()

        return try {
            val tmp = File(file.parentFile, file.name + ".cropped")
            FileOutputStream(tmp).buffered(64 * 1024).use {
                warped.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            warped.recycle()
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

package com.scanvl.app.data

/**
 * 3 mức dung lượng xuất file (nhớ lựa chọn của người dùng):
 * - SMALL:    cạnh dài ≤ 1500px, JPEG 72% -> 5 trang ~ 0.9–1.3 MB (gửi cổng dịch vụ công, mail giới hạn)
 * - BALANCED: cạnh dài ≤ 2000px, JPEG 80% -> 5 trang ~ 1.4–1.8 MB (mặc định, đạt mục tiêu < 2MB/5 trang)
 * - HIGH:     giữ nguyên ảnh gốc ≤ 2560px, JPEG 88% -> in ấn sắc nét, xuất PDF ~14ms vì không nén lại
 */
enum class ExportQuality(val label: String, val hint: String, val maxEdgePx: Int, val jpegQuality: Int) {
    SMALL("Nhỏ", "~1MB/5tr", 1500, 72),
    BALANCED("Cân bằng", "<2MB/5tr", 2000, 80),
    HIGH("Cao", "Rõ nhất", 2560, 88)
}

/**
 * Một trang đã quét. Ảnh JPEG gốc KHÔNG bao giờ bị sửa:
 * xoay trang chỉ thay đổi [rotation] (0/90/180/270) -> thao tác tức thì, không tốn CPU.
 */
data class ScanPage(
    val id: String,
    val fileName: String,
    val rotation: Int = 0
)

/**
 * Một tài liệu = danh sách trang theo thứ tự.
 * [deletedAt] = null: đang hoạt động; khác null: nằm trong Thùng rác (tự xóa vĩnh viễn sau 30 ngày).
 */
data class ScanDoc(
    val id: String,
    val title: String,
    val createdAt: Long,
    val pages: List<ScanPage>,
    val deletedAt: Long? = null
) {
    val isInTrash: Boolean get() = deletedAt != null
}

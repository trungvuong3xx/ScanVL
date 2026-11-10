# ScanVL - Android ML Kit Document Scanner

Ứng dụng Android Native chuyên nghiệp dùng để quét tài liệu, hóa đơn, hợp đồng và tự động tối ưu hóa, xuất file PDF chất lượng cao.

## Công nghệ sử dụng
- **Google Play services ML Kit Document Scanner** (`com.google.android.gms:play-services-mlkit-document-scanner:16.0.0`)
- **Jetpack Compose & Material 3**: Giao diện người dùng hiện đại, trực quan, mượt mà.
- **Scoped Storage & FileProvider**: Quản lý file PDF an toàn, chia sẻ nhanh qua Zalo, Gmail, Drive...
- **Coil**: Tải ảnh thumbnail trang đầu tiên của tài liệu.

## Cấu trúc thư mục chính
- `app/src/main/java/com/scanvl/app/MainActivity.kt`: Toàn bộ logic khởi chạy Scanner, nhận dữ liệu quét, lưu trữ và UI Jetpack Compose.
- `app/src/main/AndroidManifest.xml`: Khai báo FileProvider và Activity.
- `app/src/main/res/xml/file_paths.xml`: Cấu hình đường dẫn chia sẻ FileProvider.
- `app/build.gradle.kts`: Các dependencies chính thức từ Google.

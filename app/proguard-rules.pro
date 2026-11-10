# ---- Gson: giữ nguyên tên field của model để JSON lưu trong SharedPreferences đọc lại được ----
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.scanvl.app.ScannedDocument { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ML Kit / Play Services / Compose / Coil đã kèm consumer rules riêng, không cần khai báo thêm.

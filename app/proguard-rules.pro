# Keep source file + line numbers for readable crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Room ──────────────────────────────────────────────────────────────────────
# Entities are accessed via reflection by Room's generated code
-keep class com.lumen.app.data.db.entity.** { *; }
-keep class com.lumen.app.data.db.dao.SearchResultRow { *; }

# ── Hilt ──────────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclasseswithmembernames class * {
    @dagger.hilt.* <fields>;
    @dagger.hilt.* <methods>;
}

# ── WorkManager ───────────────────────────────────────────────────────────────
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class com.lumen.app.worker.** { *; }

# ── PdfBox-Android ────────────────────────────────────────────────────────────
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# ── ML Kit ────────────────────────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-dontwarn com.google.mlkit.**

# ── Tesseract4Android ─────────────────────────────────────────────────────────
-keep class com.googlecode.tesseract.android.** { *; }
-dontwarn com.googlecode.tesseract.**

# ── AndroidPdfViewer (barteksc / PDFium) ──────────────────────────────────────
-keep class com.github.barteksc.pdfviewer.** { *; }
-dontwarn com.github.barteksc.**

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

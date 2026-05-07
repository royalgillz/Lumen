# Lumen — Offline PDF Search for Android

Search every PDF on your device — instantly, privately, without the cloud.

---

## What it does

Lumen indexes the PDFs stored on your device and lets you search their full text in milliseconds. Tap any result to open the PDF at the exact matched page with the keyword highlighted.

**Search is the app, not a feature.** Open Lumen, type, see results. That's it.

---

## Privacy guarantee

Lumen has **no INTERNET permission**. The Android OS physically prevents it from making network requests — this is enforced at the manifest level, not just policy.

- No accounts or sign-in
- No file uploads
- No analytics or crash reporting
- No ads or tracking SDKs
- No cloud of any kind

Your PDFs stay on your device. The search index stays on your device. Everything stays on your device.

Verify this yourself in **Settings → Privacy Audit** inside the app.

---

## Features

- Full-text search across your entire PDF library with keyword-highlighted snippets
- Opens PDFs at the exact matched page with the search term highlighted
- OCR support for scanned (image-based) PDFs via on-device ML Kit
- In-viewer search with match navigation
- Reading progress — resumes where you left off per document
- Night mode and brightness control for the PDF viewer
- Library management — add folders, track indexing status, see per-document errors
- Privacy Audit screen — inspect every permission and confirm zero network access
- Light and dark theme (Material 3)
- Works on Android 8.0+ (API 26)

---

## Tech stack

| Concern | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Repository |
| DI | Hilt |
| Database | Room + FTS4 (full-text search) |
| PDF text extraction | PdfBox-Android (Apache-2.0) |
| PDF rendering | Android PdfRenderer |
| OCR | ML Kit Text Recognition v2 (bundled, offline) |
| OCR fallback | Tesseract4Android |
| File access | Storage Access Framework (SAF) — no dangerous permissions |
| Background indexing | WorkManager |
| PDF viewer | AndroidPdfViewer (barteksc / PDFium) |
| Preferences | DataStore |

---

## Building

Requires Android Studio Ladybug (2024.2) or newer.

```bash
# Debug build
./gradlew assembleDebug

# Release bundle (for Play Store)
./gradlew bundleRelease

# Run unit tests
./gradlew test

# Run lint
./gradlew lint
```

Minimum SDK: API 26 (Android 8.0). Target SDK: API 35.

---

## Architecture

```
app/src/main/java/com/lumen/app/
├── data/
│   ├── db/           — Room database, entities, DAOs, FTS4 query sanitizer
│   ├── pdf/          — PdfTextExtractor (PdfBox), PdfPageRenderer (bitmap for OCR)
│   ├── ocr/          — OcrEngine interface, MlKitOcrEngine, TesseractOcrEngine
│   ├── fs/           — SafRepository (SAF folder management), PdfScanner
│   └── repository/   — LibraryRepository, SearchRepository
├── domain/
│   ├── model/        — Document, SearchResult, SearchFilters
│   └── usecase/      — IndexLibraryUseCase, SearchUseCase, AddFolderUseCase
├── worker/           — IndexWorker (WorkManager foreground service)
└── ui/
    ├── theme/        — Color, Type, Theme (LumenTheme)
    ├── navigation/   — LumenNavGraph
    ├── search/       — SearchScreen, SearchViewModel
    ├── library/      — LibraryScreen, LibraryViewModel
    ├── settings/     — SettingsScreen, PrivacyAuditScreen
    ├── viewer/       — PdfViewerScreen, PdfViewerViewModel
    └── onboarding/   — OnboardingScreen, OnboardingViewModel
```

### Data flow

1. User picks a folder via SAF → URI persisted in DataStore with `takePersistableUriPermission()`
2. `IndexWorker` (WorkManager) walks the tree, extracts text per page via PdfBox or OCR, writes to Room FTS4
3. Search queries hit the FTS4 virtual table, joined with pages and documents tables
4. Results flow as `StateFlow<List<SearchResult>>` debounced at 200 ms

---

## Permissions

```
READ_EXTERNAL_STORAGE    — API 26–32 only; auto-removed on API 33+
FOREGROUND_SERVICE       — background indexing notification
FOREGROUND_SERVICE_DATA_SYNC
RECEIVE_BOOT_COMPLETED   — reschedule indexing after reboot
WAKE_LOCK                — keep CPU awake during indexing

INTERNET                 — intentionally absent
MANAGE_EXTERNAL_STORAGE  — intentionally absent
```

---

## Contributing

Issues and pull requests are welcome. Please open an issue before starting significant work so we can discuss the approach.

- Follow the existing code style (Kotlin, Compose, MVVM)
- Do not add any SDK that requires network access
- Do not add the INTERNET permission under any circumstances
- Keep the APK size reasonable — avoid bundling large assets

---

## License

Apache-2.0 — see [LICENSE](LICENSE).

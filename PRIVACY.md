# Privacy Policy — Lumen

**Applies to: Lumen v1.0 · Last updated: May 2026**

Lumen collects no data. Here is exactly what happens on your device:

## What Lumen does

- Your PDF files are read in place. They are never copied, uploaded, or moved.
- A search index (SQLite database) is stored in the app's private storage on your device.
- No analytics events are recorded.
- No crash reports are sent anywhere.
- The app has no INTERNET permission in its Android Manifest. This means it physically cannot make network requests.

## Permissions used

| Permission | Why |
|---|---|
| `READ_EXTERNAL_STORAGE` (Android 8–12 only) | To read PDF files from the folders you select |
| `FOREGROUND_SERVICE` | To run indexing in the background with a visible notification |
| `RECEIVE_BOOT_COMPLETED` | To restart background sync after device reboot |
| `WAKE_LOCK` | To keep the processor awake during indexing |

No other permissions are requested or used.

## Data on your device

- **Search index (SQLite):** stored in the app's private storage. Contains extracted text lines from your PDFs. Deleted automatically when you uninstall the app.
- **Folder paths:** the SAF URIs of folders you add to Lumen, stored in app preferences.
- **Reading progress:** the last page you viewed per document, stored locally.

## Data sent off your device

Nothing. Ever.

## Third-party SDKs

- **ML Kit Text Recognition (Google):** runs 100% on-device. No data is sent to Google servers.
- No analytics SDKs, no ad SDKs, no crash reporting SDKs.

## Deleting your data

Delete the search index at any time from **Settings → Delete Index**.
Uninstalling the app deletes the index and all app data automatically.

## Contact

If you have questions about this policy, open an issue on the GitHub repository.

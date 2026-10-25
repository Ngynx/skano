# Skano

Skano is an offline barcode-scanning app for Android and iOS, built with Kotlin Multiplatform and Compose Multiplatform. Scans are detected on-device and stored locally — no network required.

## What it does

Skano currently ships a single scanning screen with two modes, toggled by the **Batch** button in the top-right corner.

### Single mode (default)

- One scan at a time: while the result chip is visible, new detections are locked until you dismiss it.
- A bottom-center chip shows the scanned value, its format, and a running count (`×N`) for re-scans of the same code.
- Tap the chip to copy the value to the clipboard; tap `✕` to dismiss and re-arm the scanner.

### Batch mode

- Continuous scanning: detections keep flowing while the result panel is open.
- Distinct codes stack in a bottom-left panel in first-scan order, each with a positional reference (`#1`, `#2`, …) and a per-entry scan count (`×N`).
- Tap a row to copy that code; tap the `+` slot on a row to attach a manual reference label (e.g. `A07`), which is persisted with the record.
- The panel header shows the total distinct count; `✕` clears the panel (view-only — stored records are not deleted).
- The stack accumulates in both modes, so enabling batch mid-session reveals codes scanned earlier.

### Detection feedback

A detection rectangle is drawn over the live camera preview around the detected barcode on both platforms, briefly holding then fading out. Duplicate detections of the same code within 1.2 s are debounced, and every accepted scan is persisted to the local database.

## Supported barcode formats

| Format  | Android (ML Kit) | iOS (AVFoundation) |
|---------|------------------|--------------------|
| EAN-13  | yes              | yes                |
| EAN-8   | yes              | yes                |
| UPC-A   | yes              | via EAN-13 fold¹   |
| UPC-E   | yes              | yes                |
| Code 128 | yes              | yes                |

¹ AVFoundation has no dedicated UPC-A type; it reports UPC-A symbols as EAN-13 with a leading `0`. `BarcodeNormalizer` folds these to a 12-digit UPC-A so both platforms store the same value.

## Tech stack and architecture

| Layer | Choice |
|-------|--------|
| Shared UI | Kotlin Multiplatform + Compose Multiplatform (Material 3), one `ScannerScreen` for both platforms |
| State | Pure-Kotlin `ScannerViewModel` exposing a `StateFlow<ScannerUiState>` |
| Android camera | CameraX + ML Kit barcode scanning (bundled offline model) |
| iOS camera | AVFoundation (`AVCaptureSession` + `AVCaptureMetadataOutput`) |
| Storage | SQLDelight (`SkanoDatabase`) — local SQLite, tables: `labels`, `scan_records`, `scan_queues`, `queue_entries` |
| DI | Manual `AppContainer` (no DI framework) |

Architecture highlights:

- **Platform seams** via `expect`/`actual`: `BarcodeScannerView`, `CameraPermission`, `Clipboard`, `DatabaseDriverFactory`, `Time`. The scanner screen is written once in common code; only the camera pipelines differ per platform.
- **Domain layer** (pure Kotlin, testable without a device): `CodeFormat`, `BarcodeNormalizer` (EAN-13→UPC-A fold), `ScanDebouncer` (single-slot, 1.2 s window), `QueueNameValidator`, `QueueTextExporter` (CSV).
- **Persistence**: `RecordRepository` deduplicates scans per `(raw_value, format)` and keeps a scan count; `LabelRepository` and `QueueRepository` exist in the data layer but do not have UI surfaces yet.

## Project structure

- [`iosApp`](./iosApp) — the iOS entry point (Xcode project). Even though the UI is shared with Compose Multiplatform, this shell hosts it; SwiftUI code lives in [`iosApp/iosApp`](./iosApp/iosApp).
- [`androidApp`](./androidApp) — the Android application module.
- [`shared`](./shared/src) — code shared across both apps, organized by source set:
  - [`commonMain`](./shared/src/commonMain/kotlin) — UI, domain, data, and platform seams common to all targets.
  - [`androidMain`](./shared/src/androidMain/kotlin) — Android actuals (CameraX + ML Kit scanner, Android drivers).
  - [`iosMain`](./shared/src/iosMain/kotlin) — iOS actuals (AVFoundation scanner, native SQLite driver).
  - `commonTest`, `androidHostTest`, `iosTest` — unit tests per target.

## Running the apps

- **Android**: `./gradlew :androidApp:assembleDebug`, or use the IDE run configuration.
- **iOS**: open [`iosApp`](./iosApp) in Xcode and run it from there (requires a Mac; use a simulator or a device).

## Running tests

- Android host tests: `./gradlew :shared:testAndroidHostTest`
- iOS simulator tests: `./gradlew :shared:iosSimulatorArm64Test`

Both cover the shared module: domain logic (`BarcodeNormalizer`, `ScanDebouncer`, `QueueNameValidator`, `QueueTextExporter`), `ScannerViewModel`, and the SQLDelight repositories.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html).

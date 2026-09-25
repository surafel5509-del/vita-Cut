# Vita Cut

**Create. Edit. Inspire.**

Vita Cut is a production-quality, dark-first video editor for Android (8.0 / API 26+), built with
Kotlin, Jetpack Compose, Media3/ExoPlayer, and Clean Architecture. Everything runs on-device:
no accounts, no analytics, no media ever leaves the phone.

---

## Feature overview

### Home & projects
- Project dashboard with search, thumbnails, duration metadata, rename / duplicate / delete.
- New-project dialog with aspect-ratio presets (16:9, 9:16, 1:1, 4:5, 3:4, 21:9).
- Crash-recovery dialog: autosaved snapshots are detected on launch and can be restored or discarded.

### Editor
- **Multi-track timeline**: video, image, text, sticker, audio, voiceover and overlay tracks;
  pinch-to-zoom (16–2400 px/s), magnetic snapping, thumbnail strips and audio waveforms,
  playhead scrubbing, drag-to-move and trim handles.
- **Clip tools**: split, trim, duplicate, delete, reverse (real transcode, with progress),
  freeze frame, detach audio, volume, fades.
- **Speed**: constant 0.1×–8× plus curve presets — Montage, Bullet, Hero, Jump Cut, Flash In,
  Flash Out — and a custom speed-curve model.
- **Transitions** across 11 categories (basic, blur, zoom, spin, slide, shake, glitch, flash,
  light, 3D, cinematic) rendered by per-clip edge shaders.
- **Effects** in Trending / Cinematic / Distortion / Retro categories, each with intensity and
  enable toggles.
- **Filters** (10 categories) implemented as named color-grading deltas, with keyframeable
  intensity.
- **Color grading**: 16 sliders (brightness … grain), RGB tone curves, HSL and LUT support.
- **Text**: styled text items with in/loop/out animations; stickers (emoji, built-in vector set,
  imported images); overlays with blend modes; masking, chroma key and basic motion tracking.
- **Captions**: assisted offline caption generation, karaoke highlight, style/position controls,
  per-cue editing, split/delete, SRT & VTT import/export and sharing.
- **Smart tools (AI)**: auto reframe (16:9 / 9:16 / 1:1 / 4:5), silence removal, beat sync,
  auto cut, background removal (ML Kit subject segmentation), object detection (ML Kit) —
  every feature degrades gracefully offline with a localized explanation.
- **Voiceover recording** with a live HUD and waveform-backed audio items.
- **Keyframe engine**: universal, per-property keyframes with linear/ease interpolation
  (position, scale, rotation, opacity, volume, filter intensity).
- **Canvas**: ratio switching plus black / white / blur-fill / gradient / image backgrounds.
- **Undo/redo**: snapshot-based history with labeled steps; autosave loop with configurable
  interval; save-or-discard dialog on back press.

### Export
- 480p–4K, 24–60 fps, H.264/H.265 with device-capability fallbacks, AAC 128–320 kbps,
  bitrate presets, size estimate, progress + ETA, cancel, background export via WorkManager
  foreground worker, gallery (MediaStore) or SAF-folder destination, share sheet, history.

### Settings
- Theme (dark-first / light / system), 8 UI languages + system default, performance modes
  (high / balanced / battery saver with low-end auto-detection), hardware acceleration,
  proxy media, autosave, reduced motion, notifications, haptics, cache management, privacy.

### Templates
- Bundled starter templates (Quick Reel, Travel Montage, Beat Drop), favorites, use-count,
  save-current-project-as-template.

---

## Architecture

Modular Clean Architecture + MVVM (19 Gradle modules):

```
app                    – Application, MainActivity, NavHost, DI bootstrap
feature/home           – project dashboard
feature/editor         – timeline editor (VM, PreviewController, TimelinePanel, tool sheets)
feature/export         – export UI over WorkManager progress
feature/settings       – preferences UI
feature/templates      – template gallery
domain                 – pure-Kotlin use cases + repository contracts
data                   – repository implementations (Room/DataStore/files), bundled templates
core/model             – the project document model (serializable, lenient JSON)
core/timeline          – TimelineEngine (all edit ops), snapping, speed math, history
core/database          – Room (metadata only — media is referenced by URI, never stored)
core/datastore         – typed settings DataStore
core/media             – metadata reader, thumbnails, waveforms, proxies, reverse transcode,
                         voice recorder, MediaStore/SAF scanning
core/rendering         – shared GL effects pipeline (preview AND export render identically)
core/export            – Transformer-based export worker, planner, storage guard
core/captions          – transcription registry, caption editor, SRT/VTT import-export
core/ai                – audio analyzers, object detection, subject segmentation,
                         motion tracking, auto-reframe, beat detection
core/designsystem      – Vita* components, dark-first theme, ALL localized strings (8 locales)
```

Key decisions:
- **One rendering pipeline**: `EffectsPipelineBuilder` produces the same Media3 `Effects`
  (GLSL shaders, LUTs, overlays, captions) for ExoPlayer preview and Transformer export.
- **Feature modules are navigation-free** — they expose callback-driven screens; the app module
  owns the NavHost.
- **Localization**: every user-visible string lives in Android resources
  (`en, am, ar, fr, es, pt, zh, hi`), resolved at runtime through `VitaStrings` for dynamic
  error/message keys.
- **Privacy by design**: scoped storage only (Photo Picker / SAF), no storage permissions on
  API 29+, media processed locally.

## Tech stack

AGP 8.7.3 · Gradle 8.9 · Kotlin 2.0.21 · Compose BOM 2024.12.01 · Media3 1.5.1 ·
Hilt 2.52 (+ Hilt Worker) · Room 2.6.1 · DataStore 1.1.1 · WorkManager 2.10.0 ·
kotlinx-serialization 1.7.3 · Coil 2.7.0 · ML Kit (object detection, subject segmentation) ·
Navigation-Compose 2.8.5 · minSdk 26 / targetSdk 35.

## Building

```bash
# Android Studio: open the project root and sync, or from the CLI:
./gradlew :app:assembleDebug
./gradlew test          # unit tests (model, timeline, export planner, captions, AI, domain)
```

Requires JDK 17 and the Android SDK (compileSdk 35). The Gradle wrapper is fully committed
(`gradlew`/`gradlew.bat` + `gradle/wrapper/gradle-wrapper.jar` + `gradle-wrapper.properties` for
Gradle 8.9) — just run `./gradlew` and the distribution is downloaded automatically.

## Testing

- **Unit tests** (pure JVM): project serialization, timeline engine ops, speed math, snapping &
  keyframes, export planner, caption editing / subtitle import, audio analyzers, AI edit use cases.
- **Instrumentation tests** (device/emulator via `./gradlew connectedAndroidTest`):
  - `core:database` — Room DAO contracts: autosave pending/commit flow, crash-recovery query,
    export record lifecycle.
  - `core:designsystem` — Compose UI tests for the shared component kit (buttons, chip rows,
    sliders, empty/error states) inside `VitaTheme`.
  - `app` — end-to-end localization check: sample strings resolve in all 8 locales, translations
    genuinely differ from English, and unknown message keys degrade safely via `VitaStrings`.

## Project status

All seven build phases are complete: scaffold → model/timeline → media/rendering/database →
export → captions → AI → design system, features, app wiring and localization.

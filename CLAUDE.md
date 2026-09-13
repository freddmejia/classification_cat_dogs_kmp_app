# CLAUDE.md

Guidance for Claude Code in this repository.

## Project status

**ScannerCatDogs** is a Kotlin Multiplatform app (Android + iOS) that classifies a photo as **cat** or **dog** on-device, using the TFLite model published by the separate ML project.

As of 2026-09-13:
- **Scaffolding only.** `shared/src/commonMain` is still the KMP wizard template (`App.kt` with a "Click me!" button, `Greeting`, `Platform` expect/actual). Tests are `assertEquals(3, 1 + 2)` placeholders.
- **Model assets are bundled** in `shared/src/androidMain/assets/` (release v1.0.0), but **no code reads them yet**.
- **Not started:** camera capture, preprocessing, inference, classifier UI.
- **Git:** branch `main`, no remote yet.

**Read first:**
- [docs/MODEL_CONTRACT.md](docs/MODEL_CONTRACT.md): the model's input/output interface. **The binding contract for this app.**
- [docs/RESEARCH.md](docs/RESEARCH.md): verified library options and the chosen architecture.

## Relationship to the ML project

The model is trained and published in a **separate project** at `C:\Users\Usuario\Documents\ai\classification\cat_dogs`.

- That project is **external and read-only** from here. Do not edit it as part of app work.
- `docs/MODEL_CONTRACT.md` is a **copy** of the contract, kept in sync by hand. Never edit the copy to make app code pass; if the contract is wrong, it is fixed in the ML project and republished.
- Model updates arrive as a new `release/vX.Y.Z/`. Version bumps mean:
  - **MAJOR** (input/output changed) → **app code must change**;
  - **MINOR** (runtime/architecture changed) → **re-verify on devices**;
  - **PATCH** (weights only) → swap files, re-run the reference test.

## Model interface (summary; the contract is authoritative)

- **Input:** tensor 0, `[1, 128, 128, 3]` NHWC, `float32`, **RGB**, **raw 0-255 values**.
- **Normalization: none in the app.** The model contains `Rescaling(1/127.5, offset=-1)` internally. **Never divide by 255** - doing so yields ~0.55 for everything.
- **Output:** tensor 0, `[1, 1]` `float32`, sigmoid probability of **dog**. `p > 0.5` means dog. Class order is `['cats', 'dogs']`.
- **Preprocessing:** apply sensor rotation, center-crop to square (live camera only), resize to 128x128 bilinear, drop alpha (Android RGBA_8888; iOS BGRA also swaps B and R), write float32 RGB interleaved.
- **Operators:** 8 built-in TFLite ops, no Flex/Select TF ops.

### Reference test (run this after any change to the model, the preprocessing, or the runtime)

Feed the bundled reference images through the app's own preprocessing **without center-crop**, using the default model:

| Image | Expected | Label |
|---|---|---|
| `dog.png` | 0.99254 | dog |
| `cat.jpg` | 0.00225 | cat |

Pass if the label matches and the value is within **±0.02**. Platform resizing differs slightly from `tf.image.resize`, so small deviations are expected.

## Assets

| File | Size | Use |
|---|---|---|
| `shared/src/androidMain/assets/cat_dog_mobilenetv3.tflite` | 3.58 MB | **Default.** Float32; use for GPU |
| `shared/src/androidMain/assets/cat_dog_mobilenetv3_optimized.tflite` | 1.08 MB | Dynamic-range quantized (int8 weights, float I/O). **CPU only** |
| `shared/src/androidMain/assets/dog.png`, `cat.jpg` | 84 KB, 332 KB | Reference test images |

- Assets live in `androidMain/assets/` so `AssetManager` (and LiteRT's `CompiledModel.create(context.assets, ...)`) can reach them. They are **not** in `commonMain/composeResources/`, because iOS is expected to ship a converted Core ML model rather than this `.tflite` (see RESEARCH.md).
- **Verify SHA-256 against the contract** after replacing any model file.
- Reference images currently ship in the APK. If that becomes unwanted, move them to a device-test source set rather than deleting them.

## Planned architecture

From [docs/RESEARCH.md](docs/RESEARCH.md):

- **UI is shared** with Compose Multiplatform.
- **Camera and inference are native per platform,** behind a common Kotlin `expect`/`actual` interface.
- **Android:** LiteRT `com.google.ai.edge.litert:litert:2.2.0` (`CompiledModel` API, GPU accelerator built in) + CameraX 1.6.2 `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST` and `OUTPUT_IMAGE_FORMAT_RGBA_8888`.
- **iOS:** try Core ML first (convert with `coremltools` in the ML project); fall back to the `TensorFlowLiteObjC` pod if conversion fails or accuracy differs.
- **KMP camera/inference wrappers were evaluated and rejected** (CameraK delivers JPEG frames; peekaboo and moko-tensorflow are stale; kflite is alpha). Do not reach for them without re-reading RESEARCH.md.

## Toolchain (verified 2026-09-13)

| Item | Version |
|---|---|
| AGP | 9.0.1 |
| Gradle wrapper | 9.1.0 |
| Kotlin | 2.4.10 |
| Compose Multiplatform | 1.11.1 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 24 |
| JVM target | 11 (Gradle daemon toolchain: Azul 21) |
| iOS targets | `iosArm64`, `iosSimulatorArm64`; static framework `Shared` |

Notes:
- AGP 9 requires `com.android.kotlin.multiplatform.library` in `shared` and a separate `androidApp` module - that is why the project is split this way. Do not apply `com.android.library` to `shared`.
- RESEARCH.md lists newer versions (AGP 9.4.0, Kotlin 2.4.20, CMP 1.12.0). Bumping AGP to 9.4.0 also requires the Gradle wrapper at 9.6.0+. Treat as a deliberate, separately verified change.
- Versions are centralized in `gradle/libs.versions.toml`. Add dependencies there, not inline.
- `org.gradle.configuration-cache=true` is on. Avoid build logic that reads state at execution time.

## Commands

| Command | Purpose |
|---|---|
| `./gradlew :androidApp:assembleDebug` | Build the Android app |
| `./gradlew :shared:testAndroidHostTest` | Shared host (JVM) tests |
| `./gradlew :shared:iosSimulatorArm64Test` | Shared iOS tests (macOS only) |
| `./gradlew :androidApp:installDebug` | Install on a connected device/emulator |

The iOS app is built from Xcode by opening `iosApp/`. macOS and Xcode >= 26.4 are required; neither is available on this machine, so **iOS changes cannot be compiled or tested here** - say so rather than claiming they work.

## Conventions

- Write all code in English, including identifiers and user-facing strings.
- Do not add comments in code.
- Reusable logic belongs in `shared/src/commonMain`; keep platform code to the thin `actual` layer.
- **Preprocessing at inference time must match training:** same resize, same 0-255 scaling, RGB order.
- Keep `build/`, `.gradle/`, `.kotlin/` and `local.properties` out of git (`.gitignore` covers them).
- `.gitattributes` normalizes line endings to LF and marks `.tflite`/`.png`/`.jpg`/`.jar` binary - this repo is also opened on macOS for the iOS side.

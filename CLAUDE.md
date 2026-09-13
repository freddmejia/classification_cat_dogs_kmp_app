# CLAUDE.md

Guidance for Claude Code in this repository.

## Project status

**ScannerCatDogs** is a Kotlin Multiplatform app (Android + iOS) that classifies a photo as **cat** or **dog** on-device, using the TFLite model published by the separate ML project.

As of 2026-09-13:
- **Android inference works and is verified against the contract.** `AndroidCatDogClassifier` (LiteRT `CompiledModel`) loads the model from assets and classifies a `Bitmap`; `ReferenceImageTest` proves both models match the reference outputs on a device.
- **UI is still the KMP wizard template** (`App.kt` with a "Click me!" button, `Greeting`, `Platform` expect/actual). The classifier is not wired to any screen.
- **Not started:** camera capture, the classifier UI, and the whole iOS inference path (`Platform.ios.kt` is still template code).
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

### Reference test (run after any change to the model, the preprocessing, or the runtime)

`./gradlew :shared:connectedAndroidDeviceTest` - **needs a running emulator or device.** The task is `connectedAndroidDeviceTest`, not `androidDeviceTest`.

`ReferenceImageTest` feeds the bundled images through the app's own preprocessing, without center-crop, and asserts the label plus a **±0.02** tolerance. Measured 2026-09-13 on a Pixel_8 AVD (API 36, x86_64, `Accelerator.CPU`):

| Model | Image | Measured | Reference | Delta |
|---|---|---|---|---|
| default | `dog.png` | 0.9924486 | 0.99254 | 9.1e-05 |
| default | `cat.jpg` | 0.0023325 | 0.00225 | 8.2e-05 |
| optimized | `dog.png` | 0.9930208 | 0.99545 | 2.4e-03 |
| optimized | `cat.jpg` | 0.0021034 | 0.00266 | 5.6e-04 |

The default model agreeing to ~1e-04 means the preprocessing chain is right. **If a change pushes these into the 1e-02 range, something in the chain broke** - suspect color order, scaling, or the resize - even though the assertion still passes.

The test logs every measured value to logcat under the tag `ReferenceImageTest`, so a failure shows how far it drifted.

## Assets

| File | Size | Use |
|---|---|---|
| `shared/src/androidMain/assets/cat_dog_mobilenetv3.tflite` | 3.58 MB | **Default.** Float32; use for GPU |
| `shared/src/androidMain/assets/cat_dog_mobilenetv3_optimized.tflite` | 1.08 MB | Dynamic-range quantized (int8 weights, float I/O). **CPU only** |
| `shared/src/androidMain/assets/dog.png`, `cat.jpg` | 84 KB, 332 KB | Reference test images |

**The debug APK is ~47 MB**, up from ~18 MB, because LiteRT ships `arm64-v8a`, `armeabi-v7a` and `x86_64` native libraries (~24 MB total). Before shipping, consider an ABI split or `abiFilters`; `arm64-v8a` alone covers essentially every modern phone.

- Assets live in `androidMain/assets/` so `AssetManager` (and LiteRT's `CompiledModel.create(context.assets, ...)`) can reach them. They are **not** in `commonMain/composeResources/`, because iOS is expected to ship a converted Core ML model rather than this `.tflite` (see RESEARCH.md).
- **Verify SHA-256 against the contract** after replacing any model file.
- Reference images currently ship in the APK. If that becomes unwanted, move them to a device-test source set rather than deleting them.
- **`dog.png` is RGBA with a uniformly opaque alpha channel.** `BitmapFactory` premultiplies RGB by alpha; the reference came from OpenCV, which drops alpha without premultiplying. They agree only because the image is fully opaque. A future reference image with real transparency would break this **silently** - decode with `inPremultiplied = false` then, and note `Bitmap.createScaledBitmap` rejects unpremultiplied bitmaps, so the resize would have to be done by hand.

## Planned architecture

From [docs/RESEARCH.md](docs/RESEARCH.md):

- **UI is shared** with Compose Multiplatform.
- **Camera and inference are native per platform,** behind a common Kotlin `expect`/`actual` interface.
- **Android: done for inference.** LiteRT `com.google.ai.edge.litert:litert:2.2.0`. `CompiledModel` actually lives in the transitive `litert-api`; depending on `litert` is enough. The AAR ships `arm64-v8a`, `armeabi-v7a` and `x86_64` - **no `x86`**, so a 32-bit x86 emulator will not work. Still to come: CameraX 1.6.2 `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST` and `OUTPUT_IMAGE_FORMAT_RGBA_8888`.
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
| `./gradlew :shared:connectedAndroidDeviceTest` | **The reference test.** Needs an emulator or device |
| `./gradlew :shared:iosSimulatorArm64Test` | Shared iOS tests (macOS only) |
| `./gradlew :androidApp:installDebug` | Install on a connected device/emulator |

The iOS app is built from Xcode by opening `iosApp/`. macOS and Xcode >= 26.4 are required; neither is available on this machine, so **iOS changes cannot be compiled or tested here** - say so rather than claiming they work.

## Conventions

- Write all code in English, including identifiers and user-facing strings.
- Do not add comments in code.
- Reusable logic belongs in `shared/src/commonMain`; keep platform code to the thin `actual` layer.
- **Preprocessing at inference time must match training:** same resize, same 0-255 scaling, RGB order.
- Keep `build/`, `.gradle/`, `.kotlin/` and `local.properties` out of git (`.gitignore` covers them).
- `java` is not on `PATH` here. Gradle needs `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` (Git Bash) before `./gradlew`.
- **`android.uniquePackageNames=false` in `gradle.properties` is load-bearing - do not remove it.** `litert` and `litert-api` both declare the namespace `com.google.ai.edge.litert`. Building the `shared` library only warns, but merging that into `androidApp` is a hard error and `:androidApp:processDebugMainManifest` fails. Both artifacts are required, so the check has to be downgraded. The remaining warning is expected.
- `.gitattributes` normalizes line endings to LF and marks `.tflite`/`.png`/`.jpg`/`.jar` binary - this repo is also opened on macOS for the iOS side.

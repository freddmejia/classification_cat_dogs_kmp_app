# CLAUDE.md

Guidance for Claude Code in this repository.

## Project status

**ScannerCatDogs** is a Kotlin Multiplatform app (Android + iOS) that classifies a photo as **cat** or **dog** on-device, using the TFLite model published by the separate ML project.

As of 2026-09-13:
- **Android inference works and is verified against the contract.** `AndroidCatDogClassifier` (LiteRT `CompiledModel`) loads the model from assets and classifies a `Bitmap`; `ReferenceImageTest` proves both models match the reference outputs on a device.
- **The Android app works end to end:** one screen, live CameraX preview, a SCAN/STOP toggle, and a **continuously updating** result on top. Verified running on an emulator.
- **Not started: the entire iOS path.** `Scanner.ios.kt` is a stub reporting `CameraStatus.Unavailable`, so the iOS app builds and shows "Camera unavailable". **That message is hardcoded, not a permission failure** - there is no camera code on iOS at all. It has never been compiled; there is no macOS here.
- `Info.plist` now carries `NSCameraUsageDescription`. iOS **kills the app** rather than denying it if that key is missing when the camera is touched, so it has to be in place before any AVFoundation work starts.
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

`ClassificationTest` (commonTest, no device needed) pins the output semantics: the single output is the **dog** score, `> 0.5` is dog, and `confidence` is `1 - score` for a cat. **0.002 means about 99.8% cat, not a weak cat call** - that inversion is the easy mistake, so it is asserted rather than assumed.

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
- **Neither reference image carries an EXIF orientation tag** (checked: both report `Orientation=None`). That is why `BitmapFactory`, which ignores EXIF, agrees with OpenCV, which applies it. A replacement reference image shot on a phone almost certainly *will* carry one, and would then be fed to the model rotated differently than the notebook fed it. Check EXIF before swapping a reference image.
- **`dog.png` is RGBA with a uniformly opaque alpha channel.** `BitmapFactory` premultiplies RGB by alpha; the reference came from OpenCV, which drops alpha without premultiplying. They agree only because the image is fully opaque. A future reference image with real transparency would break this **silently** - decode with `inPremultiplied = false` then, and note `Bitmap.createScaledBitmap` rejects unpremultiplied bitmaps, so the resize would have to be done by hand.

## App structure

One screen, `ScannerScreen`, over an `expect`/`actual` `Scanner`.

| File | Source set | Role |
|---|---|---|
| `Scanner.kt` | commonMain | `Scanner` interface, `ScanState`, `CameraStatus`, and the `rememberScanner` / `CameraPreview` expects |
| `ScannerScreen.kt` | commonMain | The whole UI. Pure Compose, no platform types |
| `Theme.kt` | commonMain | Dark palette; `ScannerColors.Dog` is amber, `.Cat` is violet |
| `Scanner.android.kt` | androidMain | CameraX + permission + LiteRT wiring |
| `Scanner.ios.kt` | iosMain | Stub, always `Unavailable` |
| `AndroidCatDogClassifier.kt` | androidMain | The model call. Knows nothing about the camera |

Things worth knowing before changing it:

- **Scanning is continuous and explicitly started.** `start()` attaches the `ImageAnalysis` analyser, `stop()` calls `clearAnalyzer()`. Stop is a real stop: with the analyser cleared CameraX delivers no frames at all, rather than delivering frames that get discarded. Measured on the emulator: **4-11% CPU stopped, 28-44% running**. Nothing is classified until the user taps scan.
- **Frames are throttled to `MIN_FRAME_INTERVAL_MS` (120 ms, about 8 fps).** Inference itself is only ~2 ms; the cost is `toBitmap()` plus the rotate/crop per frame. Unthrottled it ran at 60-92% CPU on the emulator. Do not remove the throttle to "make it smoother" - the label does not need 30 fps.
- **The displayed probability is smoothed** with an exponential moving average (`SMOOTHING`, 0.35). Raw per-frame output jitters badly, and at ~0.5 the label would flip every frame. The reference test calls `AndroidCatDogClassifier` directly, so it sees **raw, unsmoothed** values and is unaffected.
- **Below `CONFIDENCE_FLOOR` (0.65) the banner says NOT SURE** in neutral grey instead of guessing. This is app policy, not model behaviour - the model has no "neither" class and always returns a cat/dog probability. **The 0.65 is a guess; tune it against real photos.**
- There is a **6 s first-frame timeout**, so a dead camera surfaces as a failure instead of a spinner forever.
- Scanning stops automatically on `ON_PAUSE`, so backgrounding the app does not leave inference running.
- **The viewfinder square is indicative, not exact.** The classifier centre-crops a square from the *analysis* frame, while `PreviewView` uses `FILL_CENTER`, which crops differently for the screen's aspect ratio. The box is centred so it roughly matches; it is not pixel-accurate. Making it exact means mapping the analysis rect onto the preview.
- **The model is severely orientation-sensitive - this is the single biggest correctness risk.** Measured with `CameraPathDiagnosticTest` on the known-good `dog.png`:

| input | verdict | dog probability |
|---|---|---|
| upright | DOG | 0.992 |
| rotated 90 | **CAT** | **0.050** |
| rotated 180 | CAT | 0.218 |
| rotated 270 | DOG | 0.767 |

A sideways dog reads as a cat at 97% confidence. Any orientation mistake in the camera path shows up as "dogs are cats", not as low confidence. Verified on the emulator that the live path is correct: `rotationDegrees` is 90, `ImageProxy.toBitmap()` returns the frame **unrotated**, and the dumped tensor is upright. Do not add a second rotation anywhere.

- **Centre-cropping measurably shifts predictions.** Training resized the whole image without preserving aspect; the camera path crops a square first. On `cat.jpg` that moves the score from 0.0023 to 0.277 - same verdict, far less margin. If borderline real-world cases misbehave, test full-frame squash (`FramePreparation.upright`) against centre-crop before assuming the model is at fault.

- **`AndroidCatDogClassifier.lastInputAsBitmap()` returns the exact tensor last fed to the model**, rebuilt from the float array. Dump it as base64 to logcat to see precisely what the model saw - scoped storage blocks adb from reading app files, so logcat is the reliable channel. This is the fastest way to settle any "is it the app or the model" question.

- **The model always answers cat or dog.** There is no "neither" class, so pointing at a wall still returns ~0.5-0.6. Any "nothing detected" behaviour would need a confidence floor, and that is a product decision.
- Permission is re-checked on `ON_RESUME`, so granting it in Settings and coming back works.
- **Rear and front lenses are both supported.** `lens` is the user's choice (default rear); the flip button only appears when `availableCameraInfos` reports both facings. `bindUseCases` tries the chosen lens and **falls back to the other one if binding throws**, so a device with only a front camera still works. Only if both fail does it report `Unavailable`.
- **Never use `ProcessCameraProvider.hasCamera()` for this.** It physically opens the camera to probe it: on the emulator that opened the front camera, disconnected it, and left the rear preview black. `availableCameraInfos` + `CameraInfo.lensFacing` reads the same facts as metadata without opening anything.
- The front camera preview is mirrored by `PreviewView`, but `ImageAnalysis` frames are **not**. That is fine here - a horizontal flip does not change cat vs dog - but it matters for anything orientation-sensitive.

## Planned architecture

From [docs/RESEARCH.md](docs/RESEARCH.md):

- **UI is shared** with Compose Multiplatform.
- **Camera and inference are native per platform,** behind a common Kotlin `expect`/`actual` interface.
- **Android: done.** Camera and inference both work. LiteRT `com.google.ai.edge.litert:litert:2.2.0`. `CompiledModel` actually lives in the transitive `litert-api`; depending on `litert` is enough. The AAR ships `arm64-v8a`, `armeabi-v7a` and `x86_64` - **no `x86`**, so a 32-bit x86 emulator will not work. CameraX 1.6.2 `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST` and `OUTPUT_IMAGE_FORMAT_RGBA_8888` feeds it.
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
- **Do not add `androidx.core:core-ktx` at the catalog's 1.19.0.** It requires compileSdk 37 and AGP 9.1+, and fails `checkDebugAarMetadata` on this project's compileSdk 36 / AGP 9.0.1. The entry sits unused in `libs.versions.toml`; `ContextCompat` already arrives transitively through CameraX.
- **`androidApp/src/main/AndroidManifest.xml` strips five permissions with `tools:node="remove"`.** LiteRT's manifest contributes `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED` for its AiPack model-download feature, which this app does not use - it loads the model from assets. The merged manifest is now just `CAMERA`. If AiPack is ever adopted, drop those removals.
- **`android.uniquePackageNames=false` in `gradle.properties` is load-bearing - do not remove it.** `litert` and `litert-api` both declare the namespace `com.google.ai.edge.litert`. Building the `shared` library only warns, but merging that into `androidApp` is a hard error and `:androidApp:processDebugMainManifest` fails. Both artifacts are required, so the check has to be downgraded. The remaining warning is expected.
- `.gitattributes` normalizes line endings to LF and marks `.tflite`/`.png`/`.jpg`/`.jar` binary - this repo is also opened on macOS for the iOS side.

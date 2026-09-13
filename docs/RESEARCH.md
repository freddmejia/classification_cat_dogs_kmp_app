# Research: libraries for real-time cat/dog classification in KMP

Research done on 2026-09-13 from the ML project. Versions come from registries and official docs unless marked **unverified**.

## Design decision

- **UI is shared** with Compose Multiplatform.
- **Camera access and inference use native libraries on each platform,** behind a common Kotlin interface.
- **KMP wrappers (see below) are not used as the core,** because they either lag behind the native runtimes or depend on frozen iOS pods.

## Build toolchain

| Item | Verified value | Source |
|---|---|---|
| Kotlin latest stable | 2.4.20 (2026-09-07); 2.5.0 planned for Dec 2026 | Kotlin releases |
| Kotlin 2.4.20 fully supported Gradle | 7.6.3 – 9.7.0 | KGP compatibility table |
| Kotlin 2.4.20 fully supported AGP | 8.5.2 – 9.3.1 (newer versions work, but may show deprecation warnings) | KGP compatibility table |
| Kotlin 2.4 Apple requirements | Xcode ≥ 26.4; iOS/tvOS minimum raised to 15.0 | What's new in Kotlin 2.4 |
| Kotlin 2.4 Swift packages | `swiftPMDependencies { swiftPackage(...) }`, **experimental**; a CocoaPods→SwiftPM migration guide exists | What's new in Kotlin 2.4 |
| Swift export | Alpha | What's new in Kotlin 2.4 |
| Compose Multiplatform | 1.12.0 (Jetpack Compose 1.12.0); minimum Kotlin 2.1.0; Android API 21+, iOS 14+; JDK 11+ | Compose compatibility page |
| AGP latest stable | 9.4.0 (Sept 2026): minimum Gradle 9.6.0, JDK 17, build-tools 36.0.0, max API 37 | AGP release notes, Google Maven |
| AGP 9 + KMP | `org.jetbrains.kotlin.multiplatform` no longer works with `com.android.application`/`com.android.library`. Use `com.android.kotlin.multiplatform.library` in the shared module, and move the Android app to its own module | KMP AGP 9 migration guide |
| Gradle latest | 9.7.1. Java 25 needs Gradle ≥ 9.1.0 | Gradle compatibility matrix |

## Android runtime

| Library | Coordinates | Version | Notes |
|---|---|---|---|
| LiteRT (TensorFlow Lite's new name) | `com.google.ai.edge.litert:litert` | **2.2.0** (GitHub release 2026-08-13) | Its own `classes.jar` has only the `Interpreter` API; the `CompiledModel` API arrives via `litert-api` (see below). For Kotlin, the GPU accelerator is built in; no extra artifact. Min API 23. Android GPU uses OpenCL, falling back to OpenGL |
| LiteRT API (published alongside) | `com.google.ai.edge.litert:litert-api` | 2.2.0 | **Holds `CompiledModel`.** Pulled in transitively by `litert`; no need to declare it. Has no further dependencies |
| Older GPU delegate line | `litert-gpu`, `litert-gpu-api`, `litert-support`, `litert-metadata` | 1.4.2 | Only for the older Interpreter-based GPU delegate. Not needed with `CompiledModel` |
| Play services alternative | `com.google.android.gms:play-services-tflite-java` / `-gpu` | 16.5.0 | Interpreter API; runtime shipped by Play services |
| CameraX | `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` | **1.6.2** stable (1.7.0-alpha03 exists) | `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST` and `OUTPUT_IMAGE_FORMAT_RGBA_8888` |
| Permission | `android.permission.CAMERA` | – | Request at runtime |

**Answered (2026-09-13):** `CompiledModel` GPU does **not** need a hand-written `<uses-native-library android:name="libOpenCL.so">`. LiteRT's own AAR manifest already declares it, plus `libOpenCL-car.so`, `libOpenCL-pixel.so`, `libvndksupport.so` and the Qualcomm/Google Tensor/MTK NPU libraries.

`CompiledModel` usage from the official docs:

```kotlin
val model = CompiledModel.create(context.assets, "cat_dog_mobilenetv3.tflite", CompiledModel.Options(Accelerator.GPU))
val inputBuffers = model.createInputBuffers()
val outputBuffers = model.createOutputBuffers()
inputBuffers[0].writeFloat(pixels)
model.run(inputBuffers, outputBuffers)
val dogProbability = outputBuffers[0].readFloat()[0]
```

## iOS runtime

**Built into Kotlin/Native, no dependency needed:** `platform.AVFoundation` (camera), `platform.CoreVideo` (pixel buffers), `platform.Accelerate` (vImage resizing), `platform.CoreML` and `platform.Vision`.

Inference options:

| Option | Verified facts | Pros | Cons |
|---|---|---|---|
| **A. Core ML** (convert with `coremltools`, run with Vision/Core ML) | `coremltools` latest stable 9.0 (2025-11-10); 9.1.dev1 (2026-08-03) | Apple's own runtime, can use the Neural Engine, no third-party binary | **Unverified:** whether 9.0 converts a TF 2.21 / Keras 3 model. Needs a second model file |
| **B. TensorFlowLiteObjC pod** (+ `Metal` subspec) | Latest stable **2.17.0 (2024-07-29)**; nightlies until 2025-06-19. `TensorFlowLiteC` 2.17.0 is a 76.5 MB archive with `TensorFlowLiteC.xcframework`, plus `TensorFlowLiteCCoreML.xcframework` and `TensorFlowLiteCMetal.xcframework` subspecs; iOS ≥ 12.0 | Uses the same `.tflite` as Android. The model's 8 ops have long been supported | Frozen. CocoaPods trunk goes **read-only on 2026-12-02** (existing pods stay installable via the CDN). Kotlin 2.4 is moving to SwiftPM |
| **C. LiteRT 2.2.0 C API** + prebuilt `libLiteRtMetalAccelerator.dylib` | Used by MLCommons `mobile_app_open` PR #1175 (min iOS 14.0; the Metal accelerator is loaded at runtime with `dlopen`, from a pinned 2.2.0 URL) | Current runtime, same `.tflite` as Android | No official CocoaPods or SwiftPM package (LiteRT issue #125). You'd package an XCFramework and write cinterop yourself |

**Suggested order:** try A (convert in the ML project). If conversion fails or accuracy differs, use B. C only if a current runtime is required.

## Camera libraries for KMP (not recommended as the core)

| Library | Status (GitHub API, 2026-09-13) | Verdict |
|---|---|---|
| CameraK / Kamera, `io.github.kashif-mehmood-km:camerak:1.2` | 574 stars, last push 2026-09-03, release 1.2 (2026-08-07); Android API 21+, iOS 13+ | Active, but its analyzer delivers **JPEG-encoded** frames on Android and iOS, so every frame must be decoded. Too slow for real time |
| peekaboo (`onseok/peekaboo`) | Last push 2024-09-26, release v0.5.2 (2024-04-15) | Stale; aimed at picking images |
| compose-camera (`l2hyunwoo/compose-camera`) | GitHub API returned HTTP 451 | Unverified |

## KMP inference wrappers (not recommended as the core)

| Library | Status | Verdict |
|---|---|---|
| kflite (`io.github.shadadman:kflite-core`, README version 3.4.0-alpha; GitHub release tag 4.90.90 on 2026-07-28) | Active, 71 stars. Android wraps TFLite/LiteRT; iOS needs a hand-written Podfile with the `TensorFlowLiteObjC` (+Metal/CoreML) pods. API: `Kflite.init(model = Res.readBytes(...))`, `Kflite.run(inputs, outputs)` | Useful reference or prototype. Alpha, and inherits the frozen iOS pods |
| moko-tensorflow (`icerockdev/moko-tensorflow`) | Last push 2023-09-05, last release 0.2.1 (2021-07-13) | Abandoned |

## Checks done (2026-09-13, in this project)

1. **`litert-2.2.0.aar` inspected.** `jni/` contains `arm64-v8a`, `armeabi-v7a` and `x86_64` (`libLiteRt.so`, `libLiteRtClGlAccelerator.so`). **There is no `x86`**, so 32-bit x86 emulators will not work. The POM's only dependency is `litert-api:2.2.0`.
2. **`CompiledModel` lives in `litert-api`, not `litert`.** `litert`'s `classes.jar` holds only the old `org.tensorflow.lite.Interpreter` API; `com.google.ai.edge.litert.CompiledModel`, `TensorBuffer`, `Accelerator` and `Environment` are in `litert-api`, which `litert` pulls in transitively. Depending on `litert` alone is enough. Verified signatures: `CompiledModel.create(AssetManager, String, Options)`, `Options(vararg Accelerator)`, `createInputBuffers()`, `run(inputs, outputs)`, `TensorBuffer.writeFloat(FloatArray)` / `readFloat()`, and `CompiledModel : AutoCloseable`.
3. **The model runs on Android and matches the contract.** Measured on a Pixel_8 AVD (API 36, x86_64) with `Accelerator.CPU`, via `shared/src/androidDeviceTest/.../ReferenceImageTest.kt`:

| Model | Image | Measured | Reference | Delta |
|---|---|---|---|---|
| default | `dog.png` | 0.9924486 | 0.99254 | 9.1e-05 |
| default | `cat.jpg` | 0.0023325 | 0.00225 | 8.2e-05 |
| optimized | `dog.png` | 0.9930208 | 0.99545 | 2.4e-03 |
| optimized | `cat.jpg` | 0.0021034 | 0.00266 | 5.6e-04 |

All well inside the contract's +/-0.02. The default model agreeing to ~1e-04 confirms the whole chain: RGB order, raw 0-255 values, bilinear resize, and no alpha premultiplication.

4. **`dog.png` is PNG color type 6 (RGBA), but its alpha channel is uniformly 255.** `BitmapFactory` premultiplies RGB by alpha by default, while the reference was produced by OpenCV, which drops alpha without premultiplying. Because the image is fully opaque, the two agree. **If a future reference image has real transparency this breaks silently** - a premultiplied decode would shift the RGB values. Decode with `inPremultiplied = false` then, and note that `Bitmap.createScaledBitmap` rejects unpremultiplied bitmaps, so the resize would have to be done by hand.

5. **Consuming LiteRT from an application needs `android.uniquePackageNames=false`.** `litert` and `litert-api` share the namespace `com.google.ai.edge.litert`. The `shared` library build only warns; `:androidApp:processDebugMainManifest` fails outright with "Namespace ... is used in multiple modules and/or libraries". `android.experimental.enableDuplicatePackageCheck=false` does **not** work - the property is `android.uniquePackageNames`.
6. **APK size:** adding LiteRT took the debug APK from ~18 MB to ~47 MB, since all three ABIs are bundled (~24 MB of `.so`). An ABI split or `abiFilters` is worth doing before release.
7. **The `<uses-native-library libOpenCL.so>` question from the Android runtime table is answered:** LiteRT's own manifest already declares it, along with the OpenCL, Qualcomm, Google Tensor and MTK NPU libraries. Apps do not need to add it.

8. **CameraX 1.6.2 works with this setup.** `ImageAnalysis` with `OUTPUT_IMAGE_FORMAT_RGBA_8888` + `STRATEGY_KEEP_ONLY_LATEST`, `ImageProxy.toBitmap()`, centre-crop and rotate, then straight into the classifier. Runs on an emulator with no crash. **Correctness of the live path is not proven** - only that it runs and returns plausible values. The reference-image test still covers model correctness.
9. **`androidx.core:core-ktx:1.19.0` cannot be used here.** It demands compileSdk 37 and AGP 9.1+; this project is compileSdk 36 / AGP 9.0.1, so `checkDebugAarMetadata` fails. CameraX brings `androidx.core` transitively at a compatible version.
10. **LiteRT injects five permissions** (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`) for AiPack model downloads. They are stripped with `tools:node="remove"`; the app still runs and scans.

11. **`ProcessCameraProvider.hasCamera()` opens the camera to answer.** Calling it for both facings at startup made the emulator open camera 1 (front), disconnect it, then bind camera 10 (rear) - and left the preview black. `provider.availableCameraInfos` with `CameraInfo.lensFacing` answers the same question from metadata, with a single camera open. Verified by the `Camera N: Opened` lines in logcat: three opens before, one after.

12. **Live classification costs, measured on the Pixel_8 AVD.** Inference is ~2 ms per frame; the per-frame `ImageProxy.toBitmap()` + rotate/crop dominates. App CPU: **4-11% idle, 60-92% classifying every frame, 28-44% throttled to ~8 fps**. `ImageAnalysis.clearAnalyzer()` is an effective stop - CPU returns to idle and memory drops back.

## Checks not yet done

1. Measure inference latency on a **real device**, CPU vs GPU, default vs optimized model. Emulator numbers above are software-rendered and not representative.
2. Core ML conversion with `coremltools` 9.0 in the ML project's Docker image (keep raw 0–255 image input: `ImageType(scale=1.0, bias=[0,0,0])`).
3. Prove the CameraX `ImageAnalysis` RGBA → 128×128 float path gives the *same numbers* as the reference images. The path runs, but has only been eyeballed; an emulator virtual-scene frame is not a known-answer input.
4. Map the analysis crop rect onto `PreviewView` so the on-screen viewfinder marks exactly what is classified.

## Sources

- LiteRT for Android: https://developers.google.com/edge/litert/android
- LiteRT GPU acceleration (`CompiledModel`): https://developers.google.com/edge/litert/next/gpu
- LiteRT GPU with the Interpreter API: https://developers.google.com/edge/litert/android/gpu
- Migrate to LiteRT from TensorFlow Lite: https://ai.google.dev/edge/litert/migration
- LiteRT iOS quickstart: https://developers.google.com/edge/litert/ios/quickstart
- LiteRT Swift Package Manager request: https://github.com/google-ai-edge/LiteRT/issues/125
- MLCommons LiteRT 2.2.0 iOS PR: https://github.com/mlcommons/mobile_app_open/pull/1175
- CocoaPods trunk read-only plan: https://blog.cocoapods.org/CocoaPods-Specs-Repo/
- Kotlin releases: https://kotlinlang.org/docs/releases.html
- What's new in Kotlin 2.4: https://kotlinlang.org/docs/whatsnew24.html
- Kotlin Gradle plugin compatibility: https://kotlinlang.org/docs/gradle-configure-project.html
- Compose Multiplatform compatibility: https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- KMP with AGP 9: https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html
- AGP release notes: https://developer.android.com/build/releases/gradle-plugin
- Gradle compatibility matrix: https://docs.gradle.org/current/userguide/compatibility.html
- Google Maven (LiteRT, CameraX, AGP): https://dl.google.com/android/maven2/
- CocoaPods trunk API: https://trunk.cocoapods.org/api/v1/pods/TensorFlowLiteC
- kflite: https://github.com/shadmanadman/kflite
- moko-tensorflow: https://github.com/icerockdev/moko-tensorflow
- CameraK: https://github.com/kashif-e/CameraK
- coremltools on PyPI: https://pypi.org/project/coremltools/

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
| LiteRT (TensorFlow Lite's new name) | `com.google.ai.edge.litert:litert` | **2.2.0** (GitHub release 2026-08-13) | Includes the `CompiledModel` API (CPU/GPU/NPU) **and** the `Interpreter` API (CPU-only in 2.2.0). For Kotlin, the GPU accelerator is built in; no extra artifact. Min API 23. Android GPU uses OpenCL, falling back to OpenGL |
| LiteRT API (published alongside) | `com.google.ai.edge.litert:litert-api` | 2.2.0 | Transitive dependencies not checked yet |
| Older GPU delegate line | `litert-gpu`, `litert-gpu-api`, `litert-support`, `litert-metadata` | 1.4.2 | Only for the older Interpreter-based GPU delegate. Not needed with `CompiledModel` |
| Play services alternative | `com.google.android.gms:play-services-tflite-java` / `-gpu` | 16.5.0 | Interpreter API; runtime shipped by Play services |
| CameraX | `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` | **1.6.2** stable (1.7.0-alpha03 exists) | `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST` and `OUTPUT_IMAGE_FORMAT_RGBA_8888` |
| Permission | `android.permission.CAMERA` | – | Request at runtime |

**Unverified:** some developers report that `CompiledModel` GPU needs `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` in the manifest. Official docs don't mention it; test on a device.

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

## Checks not yet done

1. Download `litert-2.2.0.aar`: confirm `jni/arm64-v8a` and `jni/x86_64` native libraries, the `CompiledModel` classes, and the POM's transitive dependencies. (The first attempt failed on a script error parsing the POM.)
2. Run the default `.tflite` on an Android emulator/device with LiteRT 2.2.0 and compare with the reference outputs in MODEL_CONTRACT.md.
3. Measure inference latency on a real device, CPU vs GPU, default vs optimized model.
4. Core ML conversion with `coremltools` 9.0 in the ML project's Docker image (keep raw 0–255 image input: `ImageType(scale=1.0, bias=[0,0,0])`).
5. Prove the CameraX `ImageAnalysis` RGBA → 128×128 float path gives the same result as the reference images.

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

# Model protection & code obfuscation

Draft plan. **No code has been changed yet.** This documents what I found, the
options with example implementations, the real performance cost, and my
recommendation. Decisions still needed are in the last section.

Goal, in the user's words: obfuscate the app code, and stop a user from
unzipping the APK and walking away with the `.tflite` cat/dog models.

## Honest caveat first

No on-device model can be made truly unextractable. For an **offline** app the
decryption key has to ship inside the app, so a determined attacker on a rooted
device can still recover the key or dump the decrypted model from memory. There
is no way around this for a fully offline classifier.

What encryption-at-rest *does* defeat is exactly the threat named: someone
unzipping the APK/IPA and finding a ready-to-use `.tflite`. After this change the
package contains only ciphertext. That raises the bar from "trivial" to
"needs reverse engineering + a rooted device". That is the honest ceiling.

---

## Part 1 - Findings

### Current state

- **Release is not obfuscated.** `androidApp/build.gradle.kts` has
  `isMinifyEnabled = false` on the `release` build type. Nothing is renamed,
  shrunk or stripped.
- **The models ship as plaintext.** `shared/src/androidMain/assets/` contains 13
  `.tflite` files. They are copied verbatim into the APK (and, on iOS, into the
  app bundle by the "Copy Model Files" build phase). `unzip app.apk` → the models
  are right there under `assets/`.
- **The APK ships more models than it runs.** Only two are referenced at runtime
  (`ModelContract.DEFAULT_MODEL_ASSET` and `OPTIMIZED_MODEL_ASSET`, the
  `...ZoomOutV4` pair). The other 11 are dead weight in the package. Whatever we
  do, we should stop shipping the unused ones.

### What the runtime allows (this decides what is possible)

- **Android `CompiledModel` can only load from an asset name or a file path.**
  There is no `ByteBuffer`/in-memory loader on `CompiledModel` in litert 2.2.0.
  So if we keep `CompiledModel`, protecting the model means decrypting to a
  **file** first.
- **The bundled runtime also ships the classic `org.tensorflow.lite.Interpreter`,
  which *does* have `Interpreter(ByteBuffer, Options)`.** Verified in the AAR
  (`litert-2.2.0/jars/classes.jar`). This is the door to **in-memory** decryption
  on Android without ever writing plaintext to disk. It means swapping
  `AndroidCatDogClassifier` off `CompiledModel` and onto `Interpreter`.
- **iOS already uses the TFLite C API** and calls `TfLiteModelCreateFromFile`.
  The same C API has `TfLiteModelCreate(data, size)`, which loads from a memory
  buffer - so in-memory decryption is feasible on iOS too, by decrypting into a
  pinned byte array and calling `TfLiteModelCreate` instead of `...FromFile`.

### R8 is low-risk here

The app uses **no reflection on its own classes**. LiteRT, CameraX and Compose
all bundle their own consumer ProGuard rules (`proguard.txt` inside each AAR),
which AGP applies automatically. So enabling R8 needs almost no custom keep
rules. The only care points:

- Keep the JNI-facing TFLite classes (covered by LiteRT's own consumer rules;
  add an explicit belt-and-braces rule anyway).
- `Classification`, `Label`, `ScanState` etc. are plain Kotlin used directly (no
  serialization/reflection), so they can be freely renamed - no rule needed.

---

## Part 2 - Performance: what it actually costs

This is the concern that triggered this doc, so let me be precise.

**Encryption adds a one-time cost at model load, and *zero* per-frame cost.**

- The model is decrypted **once**, when the classifier is constructed (first
  scan / app start), not on every camera frame. The per-frame inference path
  (~2 ms today) is unchanged.
- AES-GCM decryption of the default model (3.58 MB) is on the order of **a few
  to a few tens of milliseconds** on a modern phone (AES-NI / ARMv8 crypto
  extensions). The 1.08 MB optimized model is proportionally less.
- So the realistic added latency is **a one-time bump at startup measured in
  milliseconds, not seconds.** The "loses a few seconds of speed" worry does not
  match the numbers - unless we pick the decrypt-to-file option *and* re-decrypt
  on every launch (avoidable; see below).

Where a real delay *could* creep in:

- **Decrypt-to-file, done on every launch:** rewriting a 3.5 MB file each start
  is wasteful. Mitigation: decrypt once to app-private storage and reuse it
  (but then a plaintext copy lives on disk - the whole weakness of that option).
- **GPU/accelerator delegate:** the current `CompiledModel` path can use a GPU
  delegate. The classic `Interpreter` in-memory path would run on CPU (the app
  already defaults to `Accelerator.CPU`, and inference is ~2 ms, so this is not a
  practical loss here).

---

## Part 3 - The two options, with code

Both options share the **same build-time encryption step** and differ only in
how the model is decrypted at runtime.

### Shared: build-time encryption (Gradle)

Encrypt the two shipped models at build time so only `.enc` files enter the
package. The plaintext `.tflite` stay in the repo/source tree (they are the
contract artifacts) but are **not** packaged.

`shared/build.gradle.kts` (sketch):

```kotlin
val modelKeyHex = providers.gradleProperty("modelKeyHex")
    .orElse(providers.environmentVariable("MODEL_KEY_HEX"))

val encryptModels = tasks.register("encryptModels") {
    val srcDir = layout.projectDirectory.dir("src/androidMain/assets")
    val outDir = layout.buildDirectory.dir("encryptedModels/assets")
    inputs.dir(srcDir)
    inputs.property("key", modelKeyHex)
    outputs.dir(outDir)
    doLast {
        val key = hexToBytes(modelKeyHex.get())
        listOf(
            "cat_dog_mobilenetv3DataAugmentationZoomOutV4.tflite",
            "cat_dog_mobilenetv3DataAugmentationZoomOutV4_optimized.tflite",
        ).forEach { name ->
            val plain = srcDir.file(name).asFile.readBytes()
            val (iv, cipher) = aesGcmEncrypt(key, plain)   // 12-byte IV + ciphertext+tag
            outDir.get().file("$name.enc").asFile.apply {
                parentFile.mkdirs(); writeBytes(iv + cipher)
            }
        }
    }
}
```

Then package the `build/encryptedModels/assets` directory as the Android asset
source (via `sourceSets`/`androidResources` wiring) instead of the raw
`.tflite`, and make `preBuild` depend on `encryptModels`. On iOS, the "Copy
Model Files" phase copies the `.enc` files instead of `.tflite`.

Key handling: the key is passed as a Gradle property / env var at build time
(kept out of git), and embedded in the app in obfuscated form (see "Key
handling" below). It is **not** committed in plaintext.

### Option A - in-memory decrypt (recommended)

Plaintext model never touches disk. Requires rewriting `AndroidCatDogClassifier`
from `CompiledModel` to `Interpreter(ByteBuffer)`.

```kotlin
class AndroidCatDogClassifier(
    assets: AssetManager,
    modelAsset: String = ModelContract.DEFAULT_MODEL_ASSET,
) : AutoCloseable {

    private val interpreter: Interpreter
    private val input = FloatArray(ModelContract.INPUT_FLOAT_COUNT)
    private val pixels = IntArray(ModelContract.INPUT_SIZE * ModelContract.INPUT_SIZE)
    private val output = Array(1) { FloatArray(1) }

    init {
        val blob = assets.open("$modelAsset.enc").use { it.readBytes() }
        val plain = ModelCrypto.decrypt(blob)               // byte[] in memory
        val direct = ByteBuffer.allocateDirect(plain.size)
            .order(ByteOrder.nativeOrder())
            .apply { put(plain); rewind() }
        plain.fill(0)                                        // wipe the heap copy
        interpreter = Interpreter(direct, Interpreter.Options().setNumThreads(2))
    }

    fun classify(bitmap: Bitmap): Classification {
        writeInput(bitmap)                                   // unchanged 0-255 RGB fill
        val inputBuffer = ByteBuffer.allocateDirect(input.size * 4)
            .order(ByteOrder.nativeOrder())
        inputBuffer.asFloatBuffer().put(input)
        interpreter.run(inputBuffer, output)
        return Classification(output[0][0])
    }

    override fun close() = interpreter.close()
}
```

- Preprocessing (`writeInput`, the 0-255 RGB fill, resize) is **unchanged** -
  this is the part the reference test guards, so it must stay byte-identical.
- `lastInputAsBitmap()` (debug helper) carries over unchanged.
- **Must re-run `./gradlew :shared:connectedAndroidDeviceTest`** on a device/
  emulator afterwards. The reference test asserts ±0.02 vs the published values;
  switching runtimes must not move them (see CLAUDE.md - a drift into 1e-2 means
  the chain broke).
- iOS mirror: decrypt to a pinned `ByteArray`, call `TfLiteModelCreate(ptr, size)`
  instead of `TfLiteModelCreateFromFile`. Re-run `iosSimulatorArm64Test` on macOS.

Trade-offs: bigger change (new inference path on both platforms, device
re-verification), loses the `CompiledModel` accelerator abstraction (irrelevant
here - CPU, ~2 ms). Strongest against the stated threat.

### Option B - decrypt to app-private file

Keep `CompiledModel`. At first launch, decrypt the `.enc` asset into
`context.filesDir` (app-private) and load `CompiledModel.create(path)`.

```kotlin
private fun ensureDecryptedModel(context: Context, asset: String): File {
    val out = File(context.filesDir, asset)                 // app-private
    if (!out.exists()) {
        val blob = context.assets.open("$asset.enc").use { it.readBytes() }
        out.writeBytes(ModelCrypto.decrypt(blob))
    }
    return out
}
// ... CompiledModel.create(out.absolutePath, CompiledModel.Options(Accelerator.CPU))
```

Trade-offs: **smaller change**, keeps the verified `CompiledModel` path, less
device re-verification risk. But a **plaintext copy lives on disk** in
`filesDir`. On a non-rooted device other apps cannot read it; on a rooted device
it is trivially readable - so this weakens the exact protection we are adding.
Decrypt-once-and-cache also means the plaintext persists between runs.

### Key handling (both options)

The AES key must be reconstructable inside the app. In rough order of strength:

1. **Split + assemble at runtime, held in native (NDK) code**, XORed with a
   value derived from the signing certificate. Hardest to pull out statically.
2. **Obfuscated constant in DEX**, reassembled from parts. R8 renaming helps a
   little; a determined RE still finds it.
3. Plain constant. Pointless - defeats the purpose.

For an offline app, (1) is the practical ceiling. Anything stronger needs the
key to come from a server at runtime, which this app's offline design rules out.

---

## Part 4 - R8 / ProGuard config (independent of the model work)

`androidApp/build.gradle.kts`:

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro",
        )
    }
}
```

`androidApp/proguard-rules.pro` (minimal - libraries bring their own rules):

```proguard
# TFLite / LiteRT JNI entry points (belt and braces; the AAR also ships rules)
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Optional: keep line numbers for readable crash reports, hide source file names
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
```

Notes:
- `android.enableR8.fullMode` is **not** disabled anywhere (good - full mode is
  on by default and should stay on).
- Build once with minify on and smoke-test the app + run the device reference
  test; R8 stripping something LiteRT needs would show up there.
- Also worth doing regardless: an **ABI split / `abiFilters`** and dropping the
  11 unused models - the APK is ~47 MB today (CLAUDE.md).

---

## Part 5 - Decisions I still need from you

1. **Protection strength:** Option A (in-memory, recommended - strongest, bigger
   change, needs device re-test) or Option B (decrypt-to-file - smaller change,
   plaintext copy on disk)?
2. **Scope now:** Android only (I can build & verify here on Windows) or Android
   + iOS (I can write the iOS code but cannot compile/test it here - you'd verify
   on macOS)?
3. **Housekeeping:** OK to stop packaging the 11 unused `.tflite` and add an ABI
   split while we are in the build files? (Independent win, shrinks the APK.)

My recommendation: **Option A, Android first**, plus the housekeeping. It matches
your threat model (nothing usable in the package), the runtime cost is a one-time
few-milliseconds at load rather than "seconds", and doing Android first lets me
verify the reference test here before we mirror it to iOS.

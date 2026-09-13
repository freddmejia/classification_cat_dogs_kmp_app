# Model contract: cat vs dog classifier

| | |
|---|---|
| **Contract version** | **1.0.0** |
| **Model release** | `release/v1.0.0/` |
| **Date** | 2026-09-13 |
| **Source of truth** | This file, in the ML project `C:\Users\Usuario\Documents\ai\classification\cat_dogs` |

This file defines the interface between the ML project and every app that uses the model, such as the KMP app. Apps keep a **copy** and never edit it; changes are made in the ML project and published through its release process, `docs/MODEL_RELEASE.md`.

## Versioning rules

| Bump | Release type | Meaning for apps |
|---|---|---|
| **MAJOR** (2.0.0) | **B: input/output change** | Input shape, color order, value range, normalization, output shape or meaning, classes or threshold changed. **App code must change** |
| **MINOR** (1.1.0) | **C: runtime change** | Input and output stay the same, but operators, architecture, size, quantization or accelerator support changed. **App must re-verify runtime and performance on devices** |
| **PATCH** (1.0.1) | **A: weights only** | Same architecture, input and output; retrained weights. **App only swaps files and runs its reference test** |

## Files

| File | Size | SHA-256 | Use |
|---|---|---|---|
| `cat_dog_mobilenetv3.tflite` | 3,754,192 bytes (3.58 MB) | `cc4ae83dd5c2466a9143e012f4d5b94f8502064ab3b31a0da21b2eb8c94f0c18` | **Default.** Float32 weights; use for GPU (Metal/OpenCL) |
| `cat_dog_mobilenetv3_optimized.tflite` | 1,131,360 bytes (1.08 MB) | `d11d65ea068c1b537ac936cc6ef67e0be9122dd8e266284dbd8f704c11550536` | Dynamic-range quantized (int8 weights, float input/output). CPU only |
| `dog.png` | 84,520 bytes | `a364a3e2ff3bddadde85897aeccb8b3051d9cd6822b79db96fb6d0a4f69b59a1` | Reference test image (555×358) |
| `cat.jpg` | 331,846 bytes | `9b46595fdf92b4a91d380e6b94721da5a68ec18ace40f4829a29db357d79bda5` | Reference test image (960×1282) |

## Model

- **Architecture:**
  - MobileNetV3Small (ImageNet weights, `include_top=False`), called with `training=False`;
  - then GlobalAveragePooling2D, Dropout(0.2), Dense(1, sigmoid).
- **Training** (`CatDogClassifier TransferLearning.ipynb`):
  1. Stage 1 trains the head on a frozen backbone.
  2. Stage 2 fine-tunes the last 20 backbone layers with BatchNormalization kept frozen.
- **Test set results (Keras model, 2,000 images):** accuracy **0.9435**, precision **0.9503**, recall **0.936** (dog is the positive class).
- **Not measured yet:** accuracy of the optimized model on the full test set.

## Input

| Property | Value |
|---|---|
| Tensor | index 0 |
| Shape | `[1, 128, 128, 3]` (NHWC) |
| Type | `float32` |
| Color order | **RGB** |
| Value range | **0.0 – 255.0 raw pixel values** |
| Normalization | **None in the app.** The model contains `Rescaling(1/127.5, offset=-1)` internally |

**Never divide by 255.** Tested: feeding 0–1 values gives about 0.55 for both reference images, which is meaningless.

## Output

| Property | Value |
|---|---|
| Tensor | index 0 |
| Shape | `[1, 1]` |
| Type | `float32` |
| Meaning | Sigmoid probability of **dog** |
| Decision | `p > 0.5` → dog, otherwise cat |
| Classes | index order `['cats', 'dogs']` |

## Preprocessing steps (app side)

1. Apply the sensor rotation, so the image is upright.
2. Center-crop to a square (recommended for a live camera; training images were resized without cropping).
3. Resize to 128×128 with bilinear filtering.
4. Convert to RGB: Android RGBA_8888 drops alpha; iOS BGRA swaps B and R and drops alpha.
5. Write float32 values in 0–255, row by row, RGB interleaved.

## Reference outputs

Verified 2026-09-13 with the TensorFlow 2.21 TFLite interpreter. Each image was loaded with OpenCV, converted BGR→RGB, resized to 128×128 with `tf.image.resize` (bilinear), and fed as float32 0–255. No cropping.

| Image | Keras | `cat_dog_mobilenetv3.tflite` | `cat_dog_mobilenetv3_optimized.tflite` | Expected label |
|---|---|---|---|---|
| `dog.png` | 0.99254 | **0.99254** | 0.99545 | dog |
| `cat.jpg` | 0.00225 | **0.00225** | 0.00266 | cat |

**Test rule for apps:** run each reference image through the app's own preprocessing (without center-crop), using the default model.
- Pass if the label matches and the value is within **±0.02** of the reference.
- Small differences are expected, because platform image resizing differs slightly from `tf.image.resize`.

## Runtime requirements (verified)

- **Operators (8, all built-in TFLite):** `ADD`, `CONV_2D`, `DEPTHWISE_CONV_2D`, `FULLY_CONNECTED`, `HARD_SWISH`, `LOGISTIC`, `MEAN`, `MUL`.
- **No Flex / Select TF ops.**
- **GPU delegate:** the TFLite analyzer reports the model compatible (no guarantee of good performance on every device).
- **Tensor types:** the default model uses float32 only. The optimized model has 53 int8 weight tensors, with float32 activations and input/output.

## Changelog

| Contract | Release | Type | Summary |
|---|---|---|---|
| 1.0.0 | v1.0.0 | Initial | First published model: MobileNetV3Small two-stage transfer learning, test accuracy 0.9435 |

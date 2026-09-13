# CLAUDE.md

Guidance for Claude Code in this repository.

## Project status

This project is a deep learning image classifier that labels a photo as **cat** or **dog**. It uses TensorFlow/Keras transfer learning on MobileNetV3Small.

As of 2026-09-13:
- **Notebook:** training, evaluation and export are in `CatDogClassifier TransferLearning.ipynb`.
- **Current model:** test accuracy 0.9435, precision 0.9503, recall 0.936.
- **Published release:** `release/v1.0.0/`, for use by apps (a Kotlin Multiplatform app in a separate project).
- **Git:** branch `main`, remote `origin` (private GitHub repository).

**Read first:**
- [CONTEXT.md](../../Documents/ai/classification/cat_dogs/CONTEXT.md): project history, all results, findings, open tasks.
- [docs/MODEL_CONTRACT.md](../../Documents/ai/classification/cat_dogs/docs/MODEL_CONTRACT.md): the model's input/output interface. **Source of truth** for apps.
- [docs/MODEL_RELEASE.md](../../Documents/ai/classification/cat_dogs/docs/MODEL_RELEASE.md): how to publish a new model version.

**Origin:** the notebook started as a port of the Colab notebook https://huggingface.co/Kavindutharaka/cat_dog_classifier/blob/main/cat_dog_classifier.ipynb.

**Scope rule:** work only inside this folder (`C:\Users\Usuario\Documents\ai\classification\cat_dogs`). Do not read or modify sibling or parent directories. Apps may **read** `release/`, but must never write to it.

## Host environment (verified)

- **OS:** Windows 11 with WSL2; shell PowerShell 5.1 (Git Bash also available).
- **GPU:** NVIDIA GeForce RTX 5060 Ti, 16 GB VRAM, driver 595.97 (Blackwell, compute capability 12.0).
- **Docker:** Docker Desktop 28.3.3, Compose v2.39.2, with the `nvidia` container runtime registered. Docker Desktop must be running.
- **Host Python:** 3.12.0 with no ML packages. **Run everything inside the container.** TensorFlow GPU does not run on native Windows.

## Environment files

| File | Purpose |
|---|---|
| `env.yml` | Conda/micromamba env: python 3.12, jupyterlab and ipywidgets from conda-forge; `tensorflow[and-cuda]==2.21.*`, numpy, pandas, matplotlib, opendatasets and opencv-python-headless from pip |
| `Dockerfile` | `mambaorg/micromamba:2.9.0-debian13`; installs `env.yml` into the base env; sets `LD_LIBRARY_PATH` to the pip `nvidia/*/lib` folders (required for the GPU, see GPU notes); fails the build if any notebook import breaks |
| `docker-compose.yaml` | Service `notebook`: GPU reservation, JupyterLab on `127.0.0.1:8888`, `env_file: .env`, project bind-mounted at `/workspace`, named volumes `keras-cache` (ImageNet weights) and `nv-cache` |
| `.env` / `.env.example` | Runtime secrets (`KAGGLE_API_TOKEN`). `.env` is git-ignored and docker-ignored, and Compose requires it to exist; copy `.env.example` to create it |

Add new dependencies to `env.yml`, then rebuild. Put anything that has to stay version-compatible with TensorFlow (such as numpy) in the `pip:` section, so one resolver handles it.

## Commands

| Command | Purpose |
|---|---|
| `docker compose build` | Rebuild after changing `env.yml` or the Dockerfile |
| `docker compose up -d` | Start JupyterLab at http://127.0.0.1:8888 (token `$JUPYTER_TOKEN`, default `catdogs`) |
| `docker compose ps` | Check whether the `cat_dogs` container is running |
| `docker compose logs -f notebook` | Follow JupyterLab logs |
| `docker compose run --rm -T notebook python path/to/script.py` | Run a script in a throwaway container |
| `docker compose down` | Stop and remove the container |

**The running `cat_dogs` container is the user's live JupyterLab workspace.**
- Never run `up`, `down`, `restart` or rebuild-and-recreate without checking `docker compose ps` and asking first.
- Run checks in throwaway containers instead: `docker compose run --rm`, or `docker run --rm` with the project mounted read-only.
- Before editing the notebook file on disk, back it up and ask the user to reload it from disk before saving.

## GPU notes (RTX 5060 Ti, Blackwell)

Verified on 2026-09-13 with TF 2.21.0, cuDNN 9.26 and driver 595.97:
- **Runtime compilation is expected.** The TF 2.21 wheels only include CUDA kernels up to compute capability 9.0, so on this 12.0 GPU TF logs `not built with CUDA kernel binaries compatible with compute capability 12.0a ... jit-compiled from PTX, which could take 30 minutes or longer`. It works: first GPU ops take about 7 s, and a MobileNetV3Small epoch then takes about 2 s.
- **Without the Dockerfile's `LD_LIBRARY_PATH`,** TF can't load `libcusolver.so.11`. It prints `Cannot dlopen some GPU libraries` and **silently runs on the CPU** (`list_physical_devices('GPU')` returns `[]`). If the GPU list is ever empty, check this first.
- **Known issue: compiled kernels aren't persisted yet.** `~/.nv` inside the `nv-cache` volume is owned by root, and the container user `mambauser` can't write to it. The fix is `mkdir -p /home/mambauser/.nv` plus `chown` in the Dockerfile, which needs a rebuild and a container restart; ask first.
- **`cuda_timer.cc ... Delay kernel timed out`** errors during the first epoch are harmless autotuning noise.
- **Version changes:** don't change the TensorFlow version, or the `nvidia-*` package versions it pulls in, without re-running the GPU check. The `LD_LIBRARY_PATH` folder list must match the installed packages.

## Data

- **Dataset:** Kaggle `dineshpiyasamara/cats-and-dogs-for-classification` (217 MB).
- **Location:** `content/cats-and-dogs-for-classification/cats_dogs/{train,test}/{cats,dogs}`, git-ignored.
- **Split:** train has 8,000 images (split 90/10 into train/val with `seed=42`); test has 2,000 (1,000 per class).
- **Download:** use `kaggle.api.dataset_download_files("dineshpiyasamara/cats-and-dogs-for-classification", path="content", unzip=True)`. The notebook's `od.download(...)` is commented out.
- **Kaggle credentials:** `KAGGLE_API_TOKEN` (`KGAT_...` format) lives in `.env` and reaches the container at runtime through `env_file`. **Never put it in the Dockerfile** (`ENV`/`ARG`), because it would stay in the image layers and in `docker history`.
  - The `kaggle` package (1.8+, 2.2.4 installed) logs in from `KAGGLE_API_TOKEN` on `import kaggle`. Reuse `kaggle.api`.
  - `opendatasets` ignores `KAGGLE_API_TOKEN`. It only reads `./kaggle.json` from the current working directory, and otherwise prompts for a username and key.
  - **Security:** `CLASSIFIER CAT DOG USING TRANSFER L.txt` contains the real token and is committed (see CONTEXT.md §8). Never add secrets to tracked files.
- **Classes:** `image_dataset_from_directory` sorts class folders alphabetically: `cats = 0`, `dogs = 1`. So sigmoid output > 0.5 means dog.

## Current model and pipeline

Details and all past runs are in [CONTEXT.md](../../Documents/ai/classification/cat_dogs/CONTEXT.md); the exported interface is in [docs/MODEL_CONTRACT.md](../../Documents/ai/classification/cat_dogs/docs/MODEL_CONTRACT.md).

1. **Input:** 128×128 RGB, batch 32, **raw 0–255 pixels**.
2. **Model (functional API):** `MobileNetV3Small(include_top=False, weights="imagenet")` called with `training=False`, then GAP, Dropout(0.2), Dense(1, sigmoid).
3. **Stage 1:** backbone frozen, Adam 1e-3, EarlyStopping(val_loss, patience 15, restore best), then `model.save("checkpoints/stage1.keras")`.
4. **Stage 2:** unfreeze the last 20 backbone layers, **set every BatchNormalization layer `trainable=False`**, Adam 1e-5, EarlyStopping patience 5.
5. **Export:** `.keras`, `.weights.h5`, `.tflite`, optimized `.tflite`.

**Lessons that must not be repeated:**
- **Don't divide by 255 for MobileNetV3.** It includes `Rescaling(1/127.5, offset=-1)` and expects 0–255 input; dividing by 255 drops accuracy to about 0.75.
- **In Keras 3.15.1, `base(inputs, training=False)` does not keep BatchNorm frozen after `base.trainable = True`.** Freeze every BN layer explicitly (`layer.trainable = False`) before recompiling, or fine-tuning degrades the model.
- **Check that execution counts increase top to bottom before trusting metrics.** Stale kernel state once produced metrics from a different model. Use Restart Kernel and Run All Cells.
- **Strong augmentation (`RandomRotation(0.2)` = ±72°) hurt the frozen head.** Prefer none, or flip plus rotation ≤ 0.1.
- **`google.colab` can't be installed outside Colab.** Use `ipywidgets.FileUpload` or an image path.
- **OpenCV loads BGR.** Convert with `cv2.cvtColor(img, cv2.COLOR_BGR2RGB)` before predicting.

## Model releases

- **When a notebook or architecture change should reach apps,** follow [docs/MODEL_RELEASE.md](../../Documents/ai/classification/cat_dogs/docs/MODEL_RELEASE.md). Every release has a type:
  - **A: weights only**, PATCH version;
  - **B: input/output change**, MAJOR version;
  - **C: runtime change**, MINOR version.
- **Each release is published in `release/vX.Y.Z/`,** with model files, reference images, a contract snapshot and `RELEASE_NOTES.md`. Never modify a published release; fixes go in a new version.
- **Update [docs/MODEL_CONTRACT.md](../../Documents/ai/classification/cat_dogs/docs/MODEL_CONTRACT.md) and the CONTEXT.md results history** with every release.
- **Claude memories are never moved between projects.** Anything the app side needs goes into the contract or release notes.

## Conventions

- Write all code in English, including identifiers and user-facing strings.
- Do not add comments in code.
- Reusable logic belongs in Python modules; notebooks are for exploration.
- Preprocessing at inference time must match training: same resize and same scaling.
- Keep data (`cats-and-dogs-for-classification/`, `data/`), `checkpoints/` and `outputs/` out of git. `.gitignore` already covers them.
- The environment sets `TF_FORCE_GPU_ALLOW_GROWTH=true`. Don't hardcode GPU memory limits in code.

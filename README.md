# MNN-LLM-android

Source reference for the custom Android MNN text-LLM downloader APK (`TextLLM-MNN-Family-Downloader-CustomURL.apk`).

The Java activity, JNI bridge, and MNN runtime are separate pieces. The three source files at the repository root are the app layer; the MNN runtime must be built from the upstream MNN source and packaged as `libMNN.so`.

## Runtime used by the APK

- ABI: `arm64-v8a`
- Android API: 29 minimum for the APK bridge; MNN was configured with Android API 28
- NDK: `27.2.12479018`
- MNN: shared library, CPU backend plus OpenCL backend, LLM engine enabled
- C++ standard: C++17
- Models: downloaded at runtime; no model weights belong in this repository

### MNN source version used for the reference APK

The exact local source used for the reference build is the `MNN-master` directory from the working archive. It is a full source tree, not a Git checkout: it has no `.git` directory, so there is no upstream commit ID to record. The source archive is `MNN.zip` (the extracted tree is `MNN-master/`) with SHA-256:

```text
A786519AEB196C2FB1539E0028696BC14532810885760BDC0A8D16693A8BDF7A
```

That archive contains the original MNN source files used by the build, not a pointer or shortcut. A later rebuild should use that archive, or record a specific upstream MNN commit and verify its files against this snapshot.

## Build MNN

Clone the upstream source into `MNN-master` (or set `MNN_SOURCE` below):

```powershell
git clone https://github.com/alibaba/MNN.git MNN-master
```

Configure an Android arm64 Release build with the same options used for the reference APK:

```powershell
cmake -S MNN-master -B mnn-android `
  -G Ninja `
  -DCMAKE_TOOLCHAIN_FILE="$env:ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" `
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 `
  -DCMAKE_BUILD_TYPE=Release -DMNN_BUILD_SHARED_LIBS=ON `
  -DMNN_BUILD_LLM=ON -DMNN_OPENCL=ON -DMNN_ARM82=ON `
  -DMNN_USE_THREAD_POOL=ON -DMNN_USE_LOGCAT=ON
cmake --build mnn-android --config Release --target MNN -j
```

The required output is `mnn-android/libMNN.so`. Keep the matching MNN headers and LLM engine sources beside the bridge when compiling `bridge.cpp`.

## Assemble the APK

The original build was intentionally a small command-line Android build: `aapt2` creates the resource container, `javac` compiles `TextLlmActivity.java`, `d8` creates `classes.dex`, and the native bridge is linked against `libMNN.so`. Package these files under `lib/arm64-v8a/`:

- `libMNN.so`
- `libmnn_bridge.so`
- `libc++_shared.so`

Then run `zipalign` and sign with `apksigner`. The bridge compile must include the MNN `include`, `3rd_party`, and `transformers/llm/engine` directories and link `-lMNN`, `-lc++_shared`, and `-llog`.

The reference PowerShell build used Android Build Tools 35.0.0, API 35 `android.jar`, and a min API of 29 for D8. Do not commit generated build directories, private keystores, APKs, or downloaded model files.

## Source files

- `AndroidManifest.xml`
- `TextLlmActivity.java` — model downloader, custom Hugging Face URL, progress, validation, and chat UI
- `bridge.cpp` — JNI bridge to the MNN LLM engine


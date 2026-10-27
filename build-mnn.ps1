param(
    [string]$MNN_SOURCE = "$PSScriptRoot\MNN-master",
    [string]$BUILD_DIR = "$PSScriptRoot\mnn-android"
)
$ErrorActionPreference = 'Stop'
if (-not $env:ANDROID_NDK_HOME) { throw 'Set ANDROID_NDK_HOME to an Android NDK installation.' }
cmake -S $MNN_SOURCE -B $BUILD_DIR -G Ninja `
  "-DCMAKE_TOOLCHAIN_FILE=$env:ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" `
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 `
  -DCMAKE_BUILD_TYPE=Release -DMNN_BUILD_SHARED_LIBS=ON `
  -DMNN_BUILD_LLM=ON -DMNN_OPENCL=ON -DMNN_ARM82=ON `
  -DMNN_USE_THREAD_POOL=ON -DMNN_USE_LOGCAT=ON
cmake --build $BUILD_DIR --config Release --target MNN -j
Write-Host "Built $BUILD_DIR/libMNN.so"

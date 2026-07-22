cmake_minimum_required(VERSION 3.22.1)
project(llama_jni VERSION 1.0)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Force optimization for the math-heavy ggml/llama kernels, even in Debug ────
# AGP compiles the `debug` variant's native code at -O0, which makes on-device
# inference 10-50x slower (multi-second-per-token "thinking"). These apply to all
# targets added below (including llama.cpp via FetchContent); -O3 wins over the
# toolchain's -O0 because it appears later on the command line.
# NOTE: do NOT add -ffast-math — ggml #errors out because it needs non-finite
# (NaN/Inf) math; -O3 alone provides essentially all of the speedup.
add_compile_options(-O3)
add_compile_definitions(NDEBUG)

# ── CPU-only build: disable GPU backends ──────────────────────────────────────
set(GGML_METAL     OFF CACHE BOOL "" FORCE)
set(GGML_CUDA      OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN    OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP    OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

# ── Strip llama.cpp of everything we don't need ───────────────────────────────
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

# ── Fetch llama.cpp ───────────────────────────────────────────────────────────
# Update GIT_TAG to the latest release tag from:
#   https://github.com/ggerganov/llama.cpp/releases
include(FetchContent)
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        master
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama_cpp)

# ── Our thin JNI bridge ───────────────────────────────────────────────────────
add_library(llama-jni SHARED llama_jni.cpp)

target_link_libraries(llama-jni PRIVATE llama android log)

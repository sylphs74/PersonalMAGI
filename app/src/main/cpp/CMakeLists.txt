cmake_minimum_required(VERSION 3.22)

project("pocketmagi" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is checked out next to this file (see scripts/fetch-llama.sh and the CI workflow)
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_SRC}. Run scripts/fetch-llama.sh first.")
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "" FORCE)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    set(GGML_OPENMP ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(pocketmagi SHARED pocketmagi_jni.cpp)

target_include_directories(pocketmagi PRIVATE
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/tools/mtmd)

if(ANDROID)
    set(PM_PLATFORM_LIBS android log)
else()
    # host compile check only
    set(PM_PLATFORM_LIBS "")
endif()

target_link_libraries(pocketmagi
        mtmd
        llama-common
        llama
        ${PM_PLATFORM_LIBS})

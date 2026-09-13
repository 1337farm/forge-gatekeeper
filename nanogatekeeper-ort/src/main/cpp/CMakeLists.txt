# llm_engine: bare-metal ONNX Runtime GenAI (C API) for arm64-v8a.
cmake_minimum_required(VERSION 3.22.1)
project(llm_engine)

# Native ORT GenAI + ORT shared libs staged by scripts/fetch-ort-android.sh
# into nanogatekeeper-ort/src/main/jniLibs/arm64-v8a (same files AGP packages).
set(ORT_JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../jniLibs/arm64-v8a")
set(ORT_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/include")

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION "${ORT_JNILIBS_DIR}/libonnxruntime.so")

add_library(onnxruntime-genai SHARED IMPORTED)
set_target_properties(onnxruntime-genai PROPERTIES
    IMPORTED_LOCATION "${ORT_JNILIBS_DIR}/libonnxruntime-genai.so")

add_library(llm_engine SHARED llm_engine.cpp)

target_include_directories(llm_engine PRIVATE "${ORT_INCLUDE_DIR}")

target_link_libraries(llm_engine
    onnxruntime-genai
    onnxruntime
    log)

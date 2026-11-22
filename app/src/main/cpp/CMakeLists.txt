cmake_minimum_required(VERSION 3.22.1)
project("frame_bridge")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(NATIVE_LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/libs")

set(OPENCV_ANDROID_SDK "${NATIVE_LIBS_DIR}/OpenCV-android-sdk")
set(OpenCV_DIR "${OPENCV_ANDROID_SDK}/sdk/native/jni")
find_package(OpenCV REQUIRED)

set(ORT_ANDROID_DIR "${NATIVE_LIBS_DIR}/onnxruntime-android-1.32.0")

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
        IMPORTED_LOCATION "${ORT_ANDROID_DIR}/jni/${ANDROID_ABI}/libonnxruntime.so"
)

add_library(
        frame_bridge
        SHARED
        bigbrother.cpp
)

target_include_directories(frame_bridge PRIVATE
        "${ORT_ANDROID_DIR}/headers"
)

target_compile_options(frame_bridge PRIVATE
        -O2
        -Wall
        -Wextra
)

target_link_libraries(
        frame_bridge
        ${OpenCV_LIBS}
        onnxruntime
        jnigraphics
        android
)
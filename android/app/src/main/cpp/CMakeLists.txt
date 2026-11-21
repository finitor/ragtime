cmake_minimum_required(VERSION 3.22)
project(ragtime_android LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
foreach(feature APP TESTS HAMLIB PIPEWIRE HEIF RNNOISE BUILD_SOTTO)
    set(RAGTIME_${feature} OFF CACHE BOOL "Not in the Android app" FORCE)
endforeach()
# Inline pictures: JPEG 2000 through OpenJPEG, fetched with git and built
# in, and Cool-chic, which needs no library. Android itself reads the
# pictures an operator picks (JPEG, PNG, WebP, HEIF...), so no libwebp and
# no libheif.
set(RAGTIME_PICTURES ON CACHE BOOL "Inline pictures" FORCE)
set(RAGTIME_FETCH_OPENJPEG ON CACHE BOOL "OpenJPEG alone, built in" FORCE)
get_filename_component(RAGTIME_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
add_subdirectory("${RAGTIME_ROOT}" ragtime)
# Demo mode's net is the bench's fake data2g-host (tests/FakeHost.*, POSIX
# sockets on loopback) with made-up stations on it: no modem and no radio.
add_library(ragtime_jni SHARED Jni.cpp "${RAGTIME_ROOT}/platform/android/AndroidClient.cpp" "${RAGTIME_ROOT}/platform/android/AndroidFiles.cpp"
    "${RAGTIME_ROOT}/platform/android/AndroidPictures.cpp"
    "${RAGTIME_ROOT}/platform/android/DemoAir.cpp" "${RAGTIME_ROOT}/tests/FakeHost.cpp")
target_include_directories(ragtime_jni PRIVATE "${RAGTIME_ROOT}/platform/android" "${RAGTIME_ROOT}/tests")
target_link_libraries(ragtime_jni PRIVATE ragtime_core ragtime_image)
ragtime_warnings(ragtime_jni)
target_link_options(ragtime_jni PRIVATE "-Wl,-z,max-page-size=16384")

# Fetches OpenJPEG with git and builds it into this build as a static
# library, defining ragtime::openjpeg: JPEG 2000 with nothing installed and
# no other codec. For the Android app, whose pictures are read from disk by
# Android itself (so no libwebp or libheif), and for a desktop build that
# only wants the picture tests (-DRAGTIME_FETCH_OPENJPEG=ON).
#
# The same version as cmake/BuildImageLibs.cmake builds for Windows.
# Licence: OpenJPEG is BSD-2-Clause; an app that ships it shows its notice
# (docs/ANDROID_LICENSES.md).
include(FetchContent)

set(RAGTIME_OPENJPEG_VERSION v2.5.3)
FetchContent_Declare(ragtime_openjpeg
    GIT_REPOSITORY https://github.com/uclouvain/openjpeg.git
    GIT_TAG ${RAGTIME_OPENJPEG_VERSION}
    GIT_SHALLOW TRUE)
# Populate, not MakeAvailable: the Android build's CMake (3.22) has no
# EXCLUDE_FROM_ALL for the latter.
if(POLICY CMP0169)
    cmake_policy(SET CMP0169 OLD)
endif()
FetchContent_GetProperties(ragtime_openjpeg)
if(NOT ragtime_openjpeg_POPULATED)
    FetchContent_Populate(ragtime_openjpeg)
    # Only the library: no tools, no docs, no tests, and static whatever the
    # build around it makes.
    set(BUILD_CODEC OFF CACHE BOOL "" FORCE)
    set(BUILD_DOC OFF CACHE BOOL "" FORCE)
    set(BUILD_JPIP OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
    set(ragtime_shared_before ${BUILD_SHARED_LIBS})
    set(BUILD_SHARED_LIBS OFF)
    add_subdirectory(${ragtime_openjpeg_SOURCE_DIR} ${ragtime_openjpeg_BINARY_DIR} EXCLUDE_FROM_ALL)
    set(BUILD_SHARED_LIBS ${ragtime_shared_before})
    # OpenJPEG caches these for everyone, and our own programs and the
    # Android app's library would land in its bin folder.
    unset(EXECUTABLE_OUTPUT_PATH CACHE)
    unset(LIBRARY_OUTPUT_PATH CACHE)
endif()

add_library(ragtime::openjpeg INTERFACE IMPORTED)
target_link_libraries(ragtime::openjpeg INTERFACE openjp2)
# openjpeg.h, and the opj_config.h its configure step writes.
target_include_directories(ragtime::openjpeg INTERFACE
    ${ragtime_openjpeg_SOURCE_DIR}/src/lib/openjp2
    ${ragtime_openjpeg_BINARY_DIR}/src/lib/openjp2)

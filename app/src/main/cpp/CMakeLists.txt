cmake_minimum_required(VERSION 3.22)
project(rdpsync-native C)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# The dependency scripts and this optional IDE/CMake entry point share paths.
get_filename_component(RDPSYNC_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(RDPSYNC_DEPS_DIR "${RDPSYNC_ROOT}/build/freerdp-android" CACHE PATH "Built FreeRDP dependencies")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RDPSYNC_ARCH arm64)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(RDPSYNC_ARCH x86_64)
else()
    message(FATAL_ERROR "Unsupported Android ABI: ${ANDROID_ABI}")
endif()
add_library(rdpsync SHARED freerdp_bridge.c)
target_include_directories(rdpsync PRIVATE
    "${RDPSYNC_DEPS_DIR}/freerdp-src/include"
    "${RDPSYNC_DEPS_DIR}/freerdp-src/winpr/include"
    "${RDPSYNC_DEPS_DIR}/build-${RDPSYNC_ARCH}/include"
    "${RDPSYNC_DEPS_DIR}/build-${RDPSYNC_ARCH}/winpr/include"
    "${RDPSYNC_DEPS_DIR}/openssl-${RDPSYNC_ARCH}/include")
target_link_directories(rdpsync PRIVATE "${RDPSYNC_ROOT}/app/src/main/jniLibs/${ANDROID_ABI}"
    "${RDPSYNC_DEPS_DIR}/openssl-${RDPSYNC_ARCH}/lib")
target_link_libraries(rdpsync freerdp-client3 freerdp3 winpr3 crypto jnigraphics log android z)
# OpenSSL is statically linked; no device-global provider or private credentials.
target_link_options(rdpsync PRIVATE "-Wl,-z,max-page-size=16384")

cmake_minimum_required(VERSION 3.22)
project(rdpsync-native)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

add_library(rdpsync SHARED
    freerdp_bridge.c
)

target_include_directories(rdpsync PRIVATE
    ${CMAKE_SOURCE_DIR}/../build/freerdp-android/freerdp-src/include
    ${CMAKE_SOURCE_DIR}/../build/freerdp-android/freerdp-src/winpr/include
    ${CMAKE_SOURCE_DIR}/../build/freerdp-android/openssl-arm64/include
    ${CMAKE_SOURCE_DIR}/../build/freerdp-android/build-arm64/include
)

target_link_directories(rdpsync PRIVATE
    ${CMAKE_SOURCE_DIR}/../build/freerdp-android/openssl-arm64/lib
)

target_link_libraries(rdpsync
    freerdp3
    winpr3
    jnigraphics
    log
    android
    ssl
    crypto
)

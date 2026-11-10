cmake_minimum_required(VERSION 3.20)
project(BandKeyCredentialProvider LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 登录界面加载 CP 时不应依赖 VC++ 运行库，静态链接 CRT。
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

add_library(BandKeyCredentialProvider SHARED
    src/dllmain.cpp
    src/BandKeyCredentialProvider.cpp
    src/CpLog.cpp
    src/PipeClient.cpp
    src/StoredCredential.cpp
    src/BandKeyCredentialProvider.def
)

target_include_directories(BandKeyCredentialProvider PRIVATE src)

target_link_libraries(BandKeyCredentialProvider PRIVATE
    shlwapi
    credui
    secur32
    advapi32
    user32
    kernel32
    ole32
    crypt32
)

target_compile_definitions(BandKeyCredentialProvider PRIVATE
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    _WIN32_WINNT=0x0601
    WINVER=0x0601
)

if(MSVC)
    target_compile_options(BandKeyCredentialProvider PRIVATE /utf-8)
endif()

set_target_properties(BandKeyCredentialProvider PROPERTIES
    OUTPUT_NAME "BandKeyCredentialProvider"
)

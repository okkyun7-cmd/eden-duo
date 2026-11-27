# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.31)

set(CMAKE_OSX_DEPLOYMENT_TARGET "15.0" CACHE STRING "macOS deployment target")
project(yuzu)

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/CMakeModules")
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/CMakeModules/find")
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/externals/cmake-modules")

# run compiler checks before everything else
include(CompilerChecks)

set(CPM_SOURCE_CACHE ${CMAKE_SOURCE_DIR}/.cache/cpm)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
set(CMAKE_CXX_SCAN_FOR_MODULES OFF)

# force cmdlines to use rsp files to avoid 8191 char limit on winblows
if (CMAKE_GENERATOR MATCHES "Ninja" AND WIN32)
    set(CMAKE_NINJA_FORCE_RESPONSE_FILE ON)
endif()

include(DetectPlatform)
include(DetectArchitecture)
include(DefaultConfig)
include(UseLTO)
include(FasterLinker)
include(UseCcache)
include(CMakeDependentOption)
include(CTest)
include(CPMUtil)

if (NOT DEFINED ARCHITECTURE)
    message(FATAL_ERROR "Architecture didn't make it out of scope, did you delete DetectArchitecture.cmake?")
endif()

# Needed for FFmpeg w/ VAAPI and DRM
if (OPENBSD)
    # OpenBSD 7.8 broke libcxx when upgrading, so we must define the PSTL backend manually
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -I${CMAKE_SYSROOT}/usr/X11R6/include -D_LIBCPP_PSTL_BACKEND_SERIAL=1")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -I${CMAKE_SYSROOT}/usr/X11R6/include -D_LIBCPP_PSTL_BACKEND_SERIAL=1")
    set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -L${CMAKE_SYSROOT}/usr/X11R6/lib")
elseif (NETBSD)
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -I${CMAKE_SYSROOT}/usr/X11R7/include -I${CMAKE_SYSROOT}/usr/pkg/include/c++/v1")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -I${CMAKE_SYSROOT}/usr/X11R7/include -I${CMAKE_SYSROOT}/usr/pkg/include/c++/v1")
    set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -L${CMAKE_SYSROOT}/usr/X11R7/lib")
endif()

# NetBSD: Fun for the whole family!
if (NETBSD)
    set(ENV{PKG_CONFIG_PATH} "${PKG_CONFIG_PATH}:${CMAKE_SYSROOT}/usr/pkg/lib/ffmpeg7/pkgconfig")
endif()

cmake_dependent_option(YUZU_STATIC_ROOM "Build a static room executable only (CI only)" OFF "LINUX" OFF)
if (YUZU_STATIC_ROOM)
    set(YUZU_ROOM ON)
    set(YUZU_ROOM_STANDALONE ON)

    # disable e v e r y t h i n g
    set(ENABLE_QT OFF)
    set(YUZU_CMD OFF)
    set(ENABLE_CUBEB OFF)
    set(ENABLE_UPDATE_CHECKER OFF)
    set(USE_DISCORD_PRESENCE OFF)
    set(BUILD_TESTING OFF)
    set(ENABLE_WEB_SERVICE OFF)
    set(ENABLE_LIBUSB OFF)

    # allow static libs for boost though
    set(Boost_USE_STATIC_LIBS ON)
    set(CMAKE_FIND_LIBRARY_SUFFIXES ".a")
    set(OPENSSL_USE_STATIC_LIBS ON)
    set(OpenSSL_FORCE_SYSTEM ON)

    set(zstd_FORCE_BUNDLED ON)
endif()

# qt stuff
option(ENABLE_QT "Enable the Qt frontend" ON)
option(ENABLE_QT_TRANSLATION "Enable translations for the Qt frontend" OFF)
option(ENABLE_UPDATE_CHECKER "Enable update checker (for Qt and Android)" OFF)
cmake_dependent_option(YUZU_USE_QT_MULTIMEDIA "Use QtMultimedia for Camera"  OFF "NOT YUZU_USE_BUNDLED_QT" OFF)
cmake_dependent_option(YUZU_USE_QT_WEB_ENGINE "Use QtWebEngine for web applet implementation" OFF "NOT YUZU_USE_BUNDLED_QT" OFF)
set(YUZU_QT_MIRROR "" CACHE STRING "What mirror to use for downloading the bundled Qt libraries")
cmake_dependent_option(YUZU_USE_BUNDLED_QT "Download bundled Qt binaries" "${MSVC}" "ENABLE_QT" OFF)

# Retroid Pocket Duo 릴리스 최적화: 개발/디버그 오버헤드 비활성화
option(EDEN_DSMOD_BUILD_DEV_TOOLS "Build the DSMod RE/heap-analysis developer toolkit" OFF)
option(ENABLE_DEBUG_TOOLS "Enable debugging tools (maxwell disassembler, SPIRV translator, etc)" OFF)
option(ENABLE_WERROR "Enable -Werror diagnostics" ON)

# Lossless Scaling frame generation. Only Android.
cmake_dependent_option(ENABLE_LSFG "Enable Lossless Scaling frame generation" ON "ANDROID" OFF)

option(ENABLE_RESHADE "Enable ReShade FX post-processing effects" ON)

# non-linux bundled qt are static
if (YUZU_USE_BUNDLED_QT AND (APPLE OR NOT UNIX))
    set(YUZU_STATIC_BUILD ON)
endif()

# TODO: does mingw need any of this anymore
# static stuff
option(YUZU_STATIC_BUILD "Use static libraries and executables if available" OFF)

# TODO: StaticBuild.cmake
if (YUZU_STATIC_BUILD)
    # lol
    set(Boost_USE_STATIC_LIBS ON)
    set(BUILD_SHARED_LIBS OFF)

    ## find .a libs first (static, usually)
    set(CMAKE_FIND_LIBRARY_SUFFIXES ".a")

    ## some libraries use CMAKE_IMPORT_LIBRARY_SUFFIX e.g. Harfbuzz ##
    set(CMAKE_IMPORT_LIBRARY_SUFFIX ".a")

    if (MINGW)
        # simple hook to reject dynamic libs
        function(find_library var)
            # also skip previously-found libraries cuz... yaknow
            # UNLESS they are dll.a :{
            if (${var} AND NOT ${var} MATCHES "dll\\.a$")
                return()
            endif()

            _find_library(${var}${ARGN})
            if (${var})
                get_filename_component(lib_name "${${var}}" NAME)
                if (lib_name MATCHES "dll\\.a$")
                    unset(${var} CACHE)
                    set(${var} "${var}-NOTFOUND" CACHE INTERNAL "" FORCE)
                endif()
            endif()
        endfunction()

        # msys2 quazip does not build a static lib
        set(QuaZip-Qt6_FORCE_BUNDLED ON)

        set(YUZU_USE_BUNDLED_FFMPEG ON)
        set(YUZU_USE_BUNDLED_SDL3 ON)
        set(YUZU_USE_BUNDLED_OPENSSL ON)
    elseif(APPLE)
        set(YUZU_USE_BUNDLED_FFMPEG ON)
        set(YUZU_USE_BUNDLED_SDL3 ON)
        set(YUZU_USE_BUNDLED_OPENSSL ON)

        # these libs do not properly provide static libs/let you do it with cmake
        set(SPIRV-Headers_FORCE_BUNDLED ON)
        set(zstd_FORCE_BUNDLED ON)

        # broken, idk why
        set(lz4_FORCE_BUNDLED ON)
    endif()
endif()

if (MSVC AND ARCHITECTURE_x86)
    message(FATAL_ERROR "Attempting to build with the x86 environment is not supported. \
        This can typically happen if you used the Developer Command Prompt from the start menu; \
        instead, run vcvars64.bat directly, located at C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Auxiliary/Build/vcvars64.bat")
endif()

if (CXX_CLANG_CL)
    # clang-cl prints literally 10000+ warnings without this
    add_compile_options(
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-unused-command-line-argument>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-unsafe-buffer-usage>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-unused-value>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-extra-semi-stmt>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-sign-conversion>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-reserved-identifier>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-deprecated-declarations>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-cast-function-type-mismatch>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-c99-extensions>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-c++17-compat>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-c++11-compat-reserved-user-defined-literal>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-c++11-compat-deprecated-writable-strings>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-c++11-compat-pedantic>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-c++11-compat>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-c++0x-compat>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-c++98-c++11-compat-binary-literal>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-c++98-compat-pedantic>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-c++98-compat>
        $<$<COMPILE_LANGUAGE:C,CXX>:-Wno-c99-compat>
        $<$<COMPILE_LANGUAGE:C,CXX>:/EHsc>)
    # REQUIRED CPU features IN Windows-amd64
    if (ARCHITECTURE_x86_64)
        add_compile_options(
            $<$<COMPILE_LANGUAGE:C,CXX>:-msse4.1>
            $<$<COMPILE_LANGUAGE:C,CXX>:-mcx16>
        )
    endif()
endif()

# Disable Warnings as Errors for MSVC
if (MSVC AND NOT CXX_CLANG)
    # This was dripping into spirv, being overriden, and causing cl flag override warning
    # set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} /W3 /WX-")
    set(CMAKE_CXX_FLAGS_INIT "${CMAKE_CXX_FLAGS_INIT} /W3 /WX-")
endif()

# Set runtime library to MD/MDd for all configurations
if(MSVC)
    if (YUZU_USE_BUNDLED_QT AND ARCHITECTURE_arm64)
        set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
        set(libflag MT)
    else()
        set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>DLL")
        set(libflag MD)
    endif()

    # Force all projects (including external dependencies) to use the same runtime
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} /${libflag}")
    set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} /${libflag}d")
    set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} /${libflag}")
    set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} /${libflag}d")

    # Add this to ensure Cubeb uses the same runtime
    add_compile_options(
        $<$<COMPILE_LANGUAGE:C,CXX>:$<$<CONFIG:Debug>:/${libflag}d>>$<$<COMPILE_LANGUAGE:C,CXX>:$<$<CONFIG:Release>:/${libflag}>>
        $<$<COMPILE_LANGUAGE:C,CXX>:$<$<CONFIG:RelWithDebInfo>:/${libflag}>>$<$<COMPILE_LANGUAGE:C,CXX>:$<$<CONFIG:MinSizeRel>:/${libflag}>>)
endif()

# TODO(crueter): Cleanup, each dep that has a bundled option should allow to choose between bundled, external, system
option(ENABLE_CUBEB "Enables the cubeb audio backend" ON)

set(EXT_DEFAULT OFF)
if (MSVC OR ANDROID)
    set(EXT_DEFAULT ON)
endif()

option(YUZU_USE_BUNDLED_SDL3 "Download bundled SDL3 build" ${EXT_DEFAULT})

# ffmpeg
option(YUZU_USE_BUNDLED_FFMPEG "Download bundled FFmpeg" ${EXT_DEFAULT})
cmake_dependent_option(YUZU_USE_EXTERNAL_FFMPEG "Build FFmpeg from external source" "${SOLARIS}" "NOT WIN32 AND NOT ANDROID" OFF)

# sirit
set(BUNDLED_SIRIT_DEFAULT OFF)
if (MSVC AND NOT (CMAKE_BUILD_TYPE MATCHES "Deb") OR ANDROID)
    set(BUNDLED_SIRIT_DEFAULT ON)
endif()

option(YUZU_USE_BUNDLED_SIRIT "Download bundled sirit" ${BUNDLED_SIRIT_DEFAULT})

# FreeBSD 15+ has libusb, versions below should disable it
cmake_dependent_option(ENABLE_LIBUSB "Enable the use of LibUSB" ON "WIN32 OR LINUX OR FREEBSD OR APPLE" OFF)

cmake_dependent_option(ENABLE_OPENGL "Enable OpenGL" ON "NOT (WIN32 AND ARCHITECTURE_arm64) AND NOT APPLE AND NOT ANDROID" OFF)
mark_as_advanced(FORCE ENABLE_OPENGL)

option(ENABLE_WEB_SERVICE "Enable web services (telemetry, etc.)" ON)
option(ENABLE_WIFI_SCAN "Enable WiFi scanning" OFF)

cmake_dependent_option(USE_DISCORD_PRESENCE "Enables Discord Rich Presence" OFF "ENABLE_QT" OFF)

option(YUZU_TESTS "Compile tests" "${BUILD_TESTING}")

# Install udev rules on Linux (mainly for gyros)
# Only acts on joysticks and nothing else.
cmake_dependent_option(YUZU_INSTALL_UDEV_RULES "Install udev rules for gyro access" OFF "LINUX" OFF)

option(YUZU_DOWNLOAD_ANDROID_VVL "Download validation layer binary for android" ON)

option(YUZU_LEGACY "Apply patches that improve compatibility with older GPUs (e.g. Snapdragon 865) at the cost of performance" OFF)

option(NIGHTLY_BUILD "Use Nightly qualifiers in the update checker and build metadata" OFF)

cmake_dependent_option(YUZU_ROOM "Enable dedicated room functionality" ON "NOT ANDROID" OFF)
cmake_dependent_option(YUZU_ROOM_STANDALONE "Enable standalone room executable" ON "YUZU_ROOM" OFF)

cmake_dependent_option(YUZU_CMD "Compile the eden-cli executable" ON "NOT ANDROID" OFF)

cmake_dependent_option(YUZU

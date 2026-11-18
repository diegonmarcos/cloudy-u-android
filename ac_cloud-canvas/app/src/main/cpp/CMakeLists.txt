# Cloud Canvas (#1809): libcanvasaudio.so, the Play page's SoundFont engine (FluidSynth) and Oboe output.
# FluidSynth and Oboe are NOT built here: app/build.gradle's fetchFluidSynth task unpacks the pinned official FluidSynth Android build
# (its 16 KB-aligned libfluidsynth.so, liboboe.so 1.9.0 and their dependencies) and the Oboe 1.9.0 headers into FLUID_DIR, and they are
# linked as prebuilt shared libraries (LGPL-2.1: FluidSynth stays a separate, replaceable .so).
cmake_minimum_required(VERSION 3.22.1)
project(canvasaudio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED FLUID_DIR)
    message(FATAL_ERROR "FLUID_DIR is not set (app/build.gradle passes the fetchFluidSynth output)")
endif()
set(FLUID_LIBS "${FLUID_DIR}/jniLibs/${ANDROID_ABI}")

add_library(fluidsynth SHARED IMPORTED)
set_target_properties(fluidsynth PROPERTIES IMPORTED_LOCATION "${FLUID_LIBS}/libfluidsynth.so")
add_library(oboe SHARED IMPORTED)
set_target_properties(oboe PROPERTIES IMPORTED_LOCATION "${FLUID_LIBS}/liboboe.so")

add_library(canvasaudio SHARED canvas_audio_jni.cpp sf_core.cpp)
target_include_directories(canvasaudio PRIVATE "${FLUID_DIR}/include" "${FLUID_DIR}/oboe-include")
target_compile_options(canvasaudio PRIVATE -Wall -O2)
# 16 KB pages (1_cicd/src/data/page-size-guard.json): the app's own library is linked with 16 KB LOAD segments too
target_link_options(canvasaudio PRIVATE "-Wl,-z,max-page-size=16384")
find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(canvasaudio fluidsynth oboe ${android-lib} ${log-lib})

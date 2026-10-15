cmake_minimum_required(VERSION 3.22)
project(noamp LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# Build target: MinGW-w64 (GCC) -> noamp.dll, loaded by the JVM through JNI. The same code also builds on Linux (host tests).
option(NOAMP_AVX2 "Use AVX2 + FMA (any PC from ~2013 on). Turn OFF for very old CPUs." ON)

include(FetchContent)
if(POLICY CMP0169)
    cmake_policy(SET CMP0169 OLD)
endif()

# ---- Neural Amp Modeler core (needs internet on the first configure). Pin GIT_TAG to a release for reproducible builds.
FetchContent_Declare(nam_core
    GIT_REPOSITORY https://github.com/sdatkinson/NeuralAmpModelerCore.git
    GIT_TAG main
    GIT_SHALLOW TRUE)
FetchContent_GetProperties(nam_core)
if(NOT nam_core_POPULATED)
    FetchContent_Populate(nam_core)
endif()
file(GLOB_RECURSE NAM_SRC ${nam_core_SOURCE_DIR}/NAM/*.cpp)
set(NAM_INC ${nam_core_SOURCE_DIR})
foreach(d Dependencies/eigen Dependencies/nlohmann Dependencies/nlohmann_json/single_include/nlohmann)
    if(EXISTS ${nam_core_SOURCE_DIR}/${d})
        list(APPEND NAM_INC ${nam_core_SOURCE_DIR}/${d})
    endif()
endforeach()

# ---- miniaudio (single header): WASAPI audio I/O + WAV/MP3/FLAC decoding
FetchContent_Declare(miniaudio
    GIT_REPOSITORY https://github.com/mackron/miniaudio.git
    GIT_TAG 0.11.21
    GIT_SHALLOW TRUE)
FetchContent_GetProperties(miniaudio)
if(NOT miniaudio_POPULATED)
    FetchContent_Populate(miniaudio)
endif()

find_package(JNI REQUIRED)

add_library(noamp SHARED jni.cpp engine.cpp ${NAM_SRC})
set_target_properties(noamp PROPERTIES PREFIX "")
target_include_directories(noamp PRIVATE ${NAM_INC} ${miniaudio_SOURCE_DIR} ${JNI_INCLUDE_DIRS})
target_compile_definitions(noamp PRIVATE NAM_SAMPLE_FLOAT NDEBUG EIGEN_NO_DEBUG _USE_MATH_DEFINES NOMINMAX WIN32_LEAN_AND_MEAN)
target_compile_options(noamp PRIVATE -O3 -ffast-math -fno-finite-math-only -fno-math-errno -funroll-loops -fomit-frame-pointer -Wno-deprecated-declarations)
if(NOAMP_AVX2 AND CMAKE_SYSTEM_PROCESSOR MATCHES "AMD64|x86_64|x64")
    target_compile_options(noamp PRIVATE -mavx2 -mfma)
endif()
if(WIN32)
    # fully static: no libstdc++-6.dll / libgcc / winpthread next to the app
    target_link_options(noamp PRIVATE -static -static-libgcc -static-libstdc++)
    target_link_libraries(noamp PRIVATE ole32 winmm)
else()
    target_link_libraries(noamp PRIVATE pthread dl m)
endif()

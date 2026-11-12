cmake_minimum_required(VERSION 3.22)
project(scouter_ssh C CXX)

set(THIRD_PARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/out CACHE PATH "native/third_party/build.sh's output")
set(ARMS glasses CACHE STRING "glasses: the arms over Android's Bluetooth; emulated: two emulated arms")
set(SCOUTER_KIT ${CMAKE_CURRENT_SOURCE_DIR}/../../..)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(ANDROID)
  set(platform ${THIRD_PARTY_DIR}/android-arm64)
  add_library(crypto STATIC IMPORTED)
  set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${platform}/lib/libcrypto.a
    INTERFACE_INCLUDE_DIRECTORIES ${platform}/include)
else()
  set(platform ${THIRD_PARTY_DIR}/host)
  find_package(OpenSSL REQUIRED)
  add_library(crypto INTERFACE)
  target_link_libraries(crypto INTERFACE OpenSSL::Crypto)
endif()
add_library(protobuf-lite STATIC IMPORTED)
set_target_properties(protobuf-lite PROPERTIES IMPORTED_LOCATION ${platform}/lib/libprotobuf-lite.a
  INTERFACE_INCLUDE_DIRECTORIES ${platform}/include)
set(MOSH_SRC ${THIRD_PARTY_DIR}/src/mosh-1.4.0)
set(PROTOC ${THIRD_PARTY_DIR}/host/bin/protoc)
include(third_party/mosh.cmake)

add_library(scouter_kit STATIC ${SCOUTER_KIT}/src/base/scouter_kit.cpp ${SCOUTER_KIT}/src/g1/scouter_kit_g1.cpp
  ${SCOUTER_KIT}/src/vm/vm.c ${SCOUTER_KIT}/src/vm/vm_gfx.c)
target_include_directories(scouter_kit PUBLIC ${SCOUTER_KIT}/src)
if(ANDROID AND ARMS STREQUAL glasses)
  target_compile_definitions(scouter_kit PUBLIC SCOUTER_G1_NO_BLUEZ)
else()
  target_compile_definitions(scouter_kit PUBLIC SCOUTER_G1_EMULATOR)
endif()
find_package(Threads REQUIRED)
target_link_libraries(scouter_kit PUBLIC Threads::Threads)
# The glasses' microphone (Arm::MicStart, voice commands): liblc3 decodes its frames (src/README.md).
file(GLOB LC3_SOURCES ${SCOUTER_KIT}/third_party/liblc3/src/*.c)
add_library(lc3 STATIC ${LC3_SOURCES})
set_target_properties(lc3 PROPERTIES C_STANDARD 11 POSITION_INDEPENDENT_CODE ON)
target_compile_definitions(lc3 PRIVATE LC3_PLUS=0 LC3_PLUS_HR=0)
target_include_directories(lc3 PUBLIC ${SCOUTER_KIT}/third_party/liblc3/include)
target_compile_definitions(scouter_kit PUBLIC SCOUTER_G1_MIC)
target_link_libraries(scouter_kit PUBLIC lc3 m)

# Object libraries link whole, so mosh's static registrations stay.
add_library(ssh_core OBJECT mosh_client.cpp glasses.cpp keys.cpp terminal.cpp screen.cpp fx.cpp tunes.cpp)
target_link_libraries(ssh_core PUBLIC mosh scouter_kit)

if(ANDROID)
  add_library(scouter_ssh SHARED jni.cpp android_transport.cpp)
  target_link_libraries(scouter_ssh PRIVATE ssh_core mosh log)
  target_link_options(scouter_ssh PRIVATE -Wl,--gc-sections)

  # The on-phone language and embedding models (Llm.kt): llama.cpp from native/third_party/fetch_llama.sh, CPU only,
  # built for ARMv8.2 with dot products and int8 matrix multiply (the S25's cores; Llm.kt checks the CPU first).
  # Its own library, loaded only when a model is used.
  set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama/llama.cpp CACHE PATH "fetch_llama.sh's output")
  option(VOX_LLM "build the language model library (vox_llm)" OFF)
  if(VOX_LLM AND EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    foreach(o LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER LLAMA_BUILD_APP
              LLAMA_OPENSSL LLAMA_SUBPROCESS LLAMA_BUILD_MTMD GGML_OPENMP GGML_NATIVE GGML_BACKEND_DL BUILD_SHARED_LIBS LLAMA_ALL_WARNINGS)
      set(${o} OFF CACHE BOOL "" FORCE)
    endforeach()
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
    # The GPU (Adreno, through OpenCL) when fetch_opencl.sh ran: llama.cpp's OpenCL backend with its Adreno kernels,
    # embedded; linked against fetch_opencl.sh's stub, the phone's own libOpenCL.so at run time.
    set(OPENCL_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/opencl CACHE PATH "fetch_opencl.sh's output")
    if(EXISTS ${OPENCL_DIR}/lib/libOpenCL.so)
      set(GGML_OPENCL ON CACHE BOOL "" FORCE)
      set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
      set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
      set(OpenCL_INCLUDE_DIR ${OPENCL_DIR}/include CACHE PATH "" FORCE)
      set(OpenCL_LIBRARY ${OPENCL_DIR}/lib/libOpenCL.so CACHE FILEPATH "" FORCE)
      message(STATUS "vox_llm: with the OpenCL (Adreno) backend")
    endif()
    add_subdirectory(${LLAMA_DIR} llama EXCLUDE_FROM_ALL)
    add_library(vox_llm SHARED llm_jni.cpp)
    target_link_libraries(vox_llm PRIVATE llama ggml log)
    target_link_options(vox_llm PRIVATE -Wl,--gc-sections)
  endif()
else()
  enable_testing()
  add_subdirectory(tests)
endif()

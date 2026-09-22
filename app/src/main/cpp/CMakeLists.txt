# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.18.0)

project(rime_jni VERSION 3.0.0)

# for reproducible build
add_link_options("LINKER:--hash-style=gnu,--build-id=none")

set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" ${CMAKE_MODULE_PATH})

# general options
option(BUILD_SHARED_LIBS "" OFF)
option(BUILD_TESTING "" OFF)

# boost
set(BOOST_VERSION 1.89.0)
set(BOOST_INCLUDE_LIBRARIES
    algorithm
    crc
    dll
    interprocess
    range
    regex
    scope_exit
    signals2
    utility
    vmd
    uuid)
add_subdirectory(deps/boost EXCLUDE_FROM_ALL)

option(WITH_GFLAGS "Use gflags" OFF)
option(WITH_GTEST "Use googletest" OFF)
option(WITH_UNWIND "Enable libunwind support" OFF)
add_subdirectory(deps/librime/deps/glog)
target_compile_options(
  glog_internal PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

option(YAML_CPP_BUILD_TOOLS "" OFF)
option(YAML_CPP_BUILD_TESTS "" OFF)
option(YAML_CPP_INSTALL "" OFF)
add_subdirectory(deps/librime/deps/yaml-cpp)

# enable compress for leveldb
option(SNAPPY_BUILD_TESTS "" OFF)
option(SNAPPY_BUILD_BENCHMARKS "" OFF)
option(SNAPPY_INSTALL "" OFF)
add_subdirectory(deps/snappy)

set(HAVE_SNAPPY TRUE)
option(LEVELDB_BUILD_TESTS "" OFF)
option(LEVELDB_BUILD_BENCHMARKS "" OFF)
option(LEVELDB_INSTALL "" OFF)
add_subdirectory(deps/librime/deps/leveldb)

add_subdirectory(deps/librime/deps/marisa-trie)
target_compile_options(
  marisa PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

set(LIBMARISA marisa)
option(USE_SYSTEM_MARISA "" ON)
add_subdirectory(deps/OpenCC)
file(GLOB LIBOPENCC_HEADERS deps/OpenCC/src/*.hpp
     "${CMAKE_BINARY_DIR}/deps/OpenCC/src/opencc_config.h")
file(COPY ${LIBOPENCC_HEADERS} DESTINATION "${CMAKE_BINARY_DIR}/include/opencc")

set(X11Keysym "${CMAKE_SOURCE_DIR}/deps/librime/include")
include_directories("${X11Keysym}")

# 如果你把插件目录也移到了 deps 下，这里需要更新为 deps/xxx
set(RIME_PLUGINS librime-lua librime-octagram librime-predict)

# symlink plugins
foreach(plugin ${RIME_PLUGINS})
  # 搜索源路径改为 deps/
  set(PLUGIN_SOURCE_DIR "${CMAKE_SOURCE_DIR}/deps/${plugin}")
  set(PLUGIN_TARGET_DIR "${CMAKE_SOURCE_DIR}/deps/librime/plugins/${plugin}")

  if(NOT EXISTS "${PLUGIN_TARGET_DIR}")
    file(CREATE_LINK "${PLUGIN_SOURCE_DIR}"
         "${PLUGIN_TARGET_DIR}" COPY_ON_ERROR SYMBOLIC)
  endif()
endforeach()

# librime-lua
# 同样将 deps 路径指向 deps/librime-lua-deps
set(LUA_DEPS_SOURCE "${CMAKE_SOURCE_DIR}/deps/librime-lua-deps")
set(LUA_DEPS_TARGET "${CMAKE_SOURCE_DIR}/deps/librime/plugins/librime-lua/thirdparty")

if(NOT EXISTS "${LUA_DEPS_TARGET}")
  file(CREATE_LINK "${LUA_DEPS_SOURCE}"
       "${LUA_DEPS_TARGET}"
       COPY_ON_ERROR SYMBOLIC)
endif()

option(BUILD_TEST "" OFF)
option(BUILD_STATIC "" ON)
add_subdirectory(deps/librime)
target_compile_options(
  rime-static PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=." "-Wno-error=deprecated-declarations")

target_compile_options(
  rime-lua-objs PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

target_compile_options(
  rime-octagram-objs PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

add_subdirectory(librime_jni)

add_subdirectory(libmarisa_jni)

# 手写识别（ONNX Runtime C API）的 JNI 桥。它**不引入 native 依赖**：
# 运行时 dlopen 复用 sherpa-onnx 的 AAR 已经打进 APK 的 libonnxruntime.so。
# 模型走 assets/handwriting/（ochwpro）。
# 早先曾在此 vendor zinnia 并自行编译，已因准确率过低整块移除。
add_subdirectory(handwriting_jni)

# 神经下一词预测（ONNX Runtime C API）的 JNI 桥。与手写同样的做法：
# 运行时 dlopen 复用 APK 里那份 libonnxruntime.so，不新增 native 依赖。
# 不同之处是它要维护 KV cache（整段上下文的前向结果），
# 缓存放在 native 侧、由 JNI 显式 prefill/truncate/reset。
add_subdirectory(neural_jni)

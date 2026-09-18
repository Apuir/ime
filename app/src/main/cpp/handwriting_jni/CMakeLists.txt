# SPDX-License-Identifier: GPL-3.0-or-later

# 手写识别（ONNX Runtime C API）的 JNI 桥。
#
# 这里**刻意不链接 ONNX Runtime**：运行时用 dlopen 取 sherpa-onnx 的 AAR
# 已经打进 APK 的那份 libonnxruntime.so。原因（以及为什么不能引入
# onnxruntime-android 的 AAR）写在 onnx_handwriting_jni.cc 的文件头里，
# 结论是：本目录只需要头文件，不需要任何 IMPORTED target，
# 也不要在这里加 add_library(... IMPORTED) 指向某个 .so。

cmake_minimum_required(VERSION 3.18.0)

add_library(handwriting_jni SHARED onnx_handwriting_jni.cc)

set_target_properties(handwriting_jni PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)

target_include_directories(
  handwriting_jni PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/onnxruntime")

# log：__android_log_print 用；dl：dlopen/dlsym 用。
target_link_libraries(handwriting_jni PRIVATE log dl)

# SPDX-License-Identifier: GPL-3.0-or-later

# 神经下一词预测（ONNX Runtime C API）的 JNI 桥。
#
# 与 handwriting_jni 同样**刻意不链接 ONNX Runtime**：运行时 dlopen 复用
# sherpa-onnx 的 AAR 已经打进 APK 的那份 libonnxruntime.so（1.27.1）。
# 因此这里只需要头文件，不需要任何 IMPORTED target，也不要加
# add_library(... IMPORTED) 指向某个 .so —— ORT 的版本是跟着 sherpa AAR 走的，
# 混进第二份 ORT 会让符号版本对不上（详见 handwriting_jni/README.md）。

cmake_minimum_required(VERSION 3.18.0)

add_library(neural_jni SHARED onnx_neural_jni.cc)

set_target_properties(neural_jni PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)

target_include_directories(
  neural_jni PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/onnxruntime")

# log：__android_log_print 用；dl：dlopen/dlsym 用。
target_link_libraries(neural_jni PRIVATE log dl)

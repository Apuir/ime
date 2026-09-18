# 手写识别的 JNI 桥（ONNX Runtime C API）

本目录是本地手写引擎（ochwpro 模型）的 native 层。它**不包含 ONNX Runtime 本体**，
也不链接它 —— 运行时用 `dlopen` 取一份已经存在于 APK 里的 `libonnxruntime.so`。

## 文件

| 文件 | 说明 |
|---|---|
| `onnx_handwriting_jni.cc` | JNI 入口。`dlopen` + `OrtGetApiBase` → 按 `ORT_API_VERSION` 取函数表，然后建会话、跑推理 |
| `onnxruntime/onnxruntime_c_api.h` | ONNX Runtime C API 头文件（vendored），`ORT_API_VERSION = 27` |
| `onnxruntime/onnxruntime_ep_c_api.h` | 上者内部 include 的补充头文件（vendored，不要单独 include） |
| `CMakeLists.txt` | 只编译上面那个 .cc；**刻意没有 `IMPORTED` target**，见下 |

`onnxruntime/` 下的两个头文件取自
`https://github.com/microsoft/onnxruntime` 的 **v1.27.1** tag
（`include/onnxruntime/core/session/`），
Copyright (c) Microsoft Corporation，**MIT License**。

## 关键：为什么不带自己的 ONNX Runtime

APK 里**已经**有一份 `libonnxruntime.so` —— 语音模块的
`sherpa-onnx-1.13.5-qnn.aar` 提供，版本 **1.27.1**，而且 sherpa 的三个 jni 库
（`libsherpa-onnx-{jni,c-api,cxx-api}.so`）都 `NEEDED libonnxruntime.so`。
也就是说它是语音功能**必需**的，既不能删也不能换。

而 `onnxruntime-android` 的 AAR 会再提供一份**同名** `.so`，Maven Central 上的版本
与 sherpa 的 1.27.1 对不上（只有 1.27.0 / 1.28.0 / … / 1.30.0）。两者在同一个 APK 里
无法共存，而且没有折中方案：

- 排除 sherpa 的、留下 AAR 的 → sherpa 的 jni 加载失败，**语音直接崩**；
- 反过来只加 AAR 的 Java 桥（`libonnxruntime4j_jni.so`）、核心仍用 sherpa 的 →
  Java 桥对核心的符号要求带**版本标签**。`readelf` 实测：
  1.27.0 的桥要求 `OrtGetApiBase@VERS_1.27.0`，而 sherpa 的核心只定义
  `VERS_1.27.1`，动态链接器直接报错，同样加载不起来。

所以本模块复用 sherpa 那份库。收益有两处：

1. **APK 体积零增长** —— 省掉 onnxruntime-android 那份约 33 MB（arm64 未压缩）的核心库；
2. 版本完全自洽，且**没有改动语音模块的依赖**（它的库一个字节都没动）。

代价是这一层必须用 C API 手写，以及下面这条耦合。

## 隐藏耦合（务必知悉）

**本地手写引擎依赖语音模块的 AAR 提供的 `libonnxruntime.so`。**

若将来移除 `sherpa-onnx` 依赖，本地手写引擎会随之不可用。届时表现为
`dlopen` 失败并给出可读错误（"无法加载 libonnxruntime.so（它由语音模块的
sherpa-onnx AAR 提供……）"），不会静默出错 —— 这条错误信息就是为这个场景写的。

届时的替代路径是引入 `onnxruntime-android` 的 AAR，并**同时**处理语音侧的 ORT 依赖；
那需要先确认 sherpa 与新版本的 ABI 兼容性（符号版本标签是硬约束），不能想当然。

## 校验过的事实（2026-09-18，均来自实测而非文档转述）

| 项 | 值 |
|---|---|
| sherpa 内置 ORT 版本 | **1.27.1**（`nm -D` 可见 `OrtGetApiBase@@VERS_1.27.1`） |
| 该库是否被裁剪算子 | **没有**：MatMul / Attention / LayerNormalization / Gelu / Softmax / Reshape / Transpose / Gather 均在 |
| 头文件版本匹配 | v1.27.1 的 `ORT_API_VERSION = 27`，与运行时一致；`GetApi(27)` 成功 |
| 1.27.0 的 Java 桥能否配 sherpa 的核心 | **不能**（`VERS_1.27.0` vs `VERS_1.27.1`，见上） |
| 1.27.1 的 Android AAR | Maven Central 与 GitHub release **都没有发布**（只有 1.27.0） |

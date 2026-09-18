// SPDX-License-Identifier: GPL-3.0-or-later
//
// 手写识别（ochwpro，ONNX Runtime）的 JNI 桥。
//
// ## 为什么这里用 dlopen 而不是链接一个自己的 ONNX Runtime
//
// 本项目**已经**在 APK 里带了 libonnxruntime.so —— 来自语音模块的
// `sherpa-onnx-1.13.5-qnn.aar`，版本 **1.27.1**，而且 sherpa 的三个 jni 库
// （libsherpa-onnx-{jni,c-api,cxx-api}.so）都 `NEEDED libonnxruntime.so`，
// 也就是说它是语音功能**必需**的，不能替换也不能移除。
//
// 而 onnxruntime-android 的 AAR 会再提供一份同名 .so，Maven Central 上的版本
// 与 sherpa 的 1.27.1 对不上（只有 1.27.0 / 1.28.0 / … / 1.30.0）。
// 这两者在同一个 APK 里**无法共存**，而且矛盾没有折中余地：
//
// - 留下 AAR 的核心库、排除 sherpa 的 → sherpa 的 jni 加载失败（语音直接崩）；
// - 反过来留下 sherpa 的、只加 AAR 的 Java 桥（`libonnxruntime4j_jni.so`）→
//   Java 桥对核心的符号要求带**版本标签**（`readelf` 可见：
//   1.27.0 的桥要求 `OrtGetApiBase@VERS_1.27.0`，而 sherpa 的核心只定义
//   `VERS_1.27.1`），动态链接器会直接报错，照样加载不起来。
//
// 所以这里直接复用 sherpa 那份库：用 `dlopen` 在运行时取 `OrtGetApiBase`，
// 再按 `ORT_API_VERSION`（头文件里的值，v1.27.1 对应 27）取函数表。
// 代价是这一层必须用 C API 手写（约 200 行），收益是：
// - **APK 体积零增长** —— 省掉了 onnxruntime-android 那份约 33 MB 的核心库；
// - 版本完全自洽（同一份 1.27.1 的库配同一版本的 C API 头文件），
//   也不存在「改动了语音模块依赖」的隐患。
//
// ## 隐藏耦合（务必知悉）
//
// **手写功能因此依赖语音模块的 AAR 提供的 libonnxruntime.so。**
// 若将来移除了 sherpa-onnx 依赖，本地手写引擎会随之不可用
// （表现为 `dlopen` 失败并给出明确错误，不会静默出错）。
// 届时的替代路径是引入 onnxruntime-android 的 AAR，并同时替换掉语音的 ORT 依赖 ——
// 那需要先确认 sherpa 与新版本 ABI 兼容，不能想当然。
//
// 头文件 vendored 在 `onnxruntime/` 下（MIT，取自 onnxruntime v1.27.1 的
// `include/onnxruntime/core/session/`）。

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <cstring>
#include <mutex>
#include <string>

#include "onnxruntime_c_api.h"

namespace {

constexpr char kTag[] = "HandwritingOnnx";

/**
 * ONNX Runtime 的核心库名。**不要改成别的名字**：
 * 这个名字就是 sherpa-onnx 的 AAR 提供的那份库（见文件头说明）。
 */
constexpr char kOrtLibraryName[] = "libonnxruntime.so";

/**
 * 模型的输入输出名。取自 ochwpro.onnx 本身（本机用 onnxruntime 校验过）。
 * [LoadLocked] 会与实际会话核对，不符则明确报错，不会带着错名字跑到识别阶段。
 */
constexpr char kInputName[] = "input";
constexpr char kMaskName[] = "mask";
constexpr char kOutputName[] = "logits";

/**
 * 张量形状常量。**与 Kotlin 侧 `OchwproPreprocess` 里的同名常量必须一致，改一处要改两处。**
 * 放在这里而不是从模型读：模型是我们自己打包的固定版本，
 * 而形状不符会让识别结果莫名其妙地错（而不是报错），显式校验更安全。
 */
constexpr int kFixedLen = 200;
constexpr int kFeatureDim = 5;

std::mutex g_mutex;
void *g_library = nullptr;
const OrtApi *g_api = nullptr;
OrtEnv *g_env = nullptr;
OrtSession *g_session = nullptr;
OrtMemoryInfo *g_memory_info = nullptr;
std::string g_last_error;
std::string g_runtime_version;

/** 记录错误。**调用前必须已持有 [g_mutex]。** */
void Fail(const std::string &message) {
  g_last_error = message;
  __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
}

/** 把 OrtStatus 转成可读错误并记录。**调用前必须已持有 [g_mutex]。** */
void FailOnStatus(const std::string &what, OrtStatus *status) {
  const char *detail = nullptr;
  if (status != nullptr && g_api != nullptr) {
    detail = g_api->GetErrorMessage(status);
  }
  Fail(what + ": " + (detail != nullptr ? detail : "未知错误"));
  if (status != nullptr && g_api != nullptr) {
    g_api->ReleaseStatus(status);
  }
}

std::string ErrnoMessage(const std::string &what) {
  const char *detail = dlerror();
  return what + ": " + (detail != nullptr ? detail : "未知错误");
}

/**
 * 打开 ONNX Runtime 并取指定 API 版本的函数表。
 *
 * 用 dlopen 而不是链接期依赖：sherpa 的 AAR 已经把库放进 APK 了，
 * 我们不需要（也不应该）再带一份。
 * **调用前必须已持有 [g_mutex]。**
 */
bool LoadOrtApi() {
  if (g_api != nullptr) return true;

  g_library = dlopen(kOrtLibraryName, RTLD_NOW | RTLD_LOCAL);
  if (g_library == nullptr) {
    Fail(ErrnoMessage(
        std::string("无法加载 ") + kOrtLibraryName +
        "（它由语音模块的 sherpa-onnx AAR 提供，若该依赖被移除，本地手写引擎会一并失效）"));
    return false;
  }

  // dlsym 返回的是函数指针，直接 reinterpret_cast 会在 -Wpedantic 下告警；
  // 这里用 memcpy 做无告警的转换。
  void *symbol = dlsym(g_library, "OrtGetApiBase");
  if (symbol == nullptr) {
    Fail(ErrnoMessage("libonnxruntime.so 里没有 OrtGetApiBase 符号"));
    return false;
  }

  const OrtApiBase *(*get_api_base)(void) = nullptr;
  static_assert(sizeof(get_api_base) == sizeof(symbol), "函数指针与 void* 尺寸不一致");
  std::memcpy(&get_api_base, &symbol, sizeof(symbol));

  const OrtApiBase *base = get_api_base();
  if (base == nullptr) {
    Fail("OrtGetApiBase() 返回空指针");
    return false;
  }

  const char *version = base->GetVersionString();
  g_runtime_version = version != nullptr ? version : "未知";

  const OrtApi *api = base->GetApi(ORT_API_VERSION);
  if (api == nullptr) {
    // 这是最关键的一处诊断：API 版本不匹配时 GetApi 返回空，
    // 而报错本身不会说明双方版本，缺了这句日志就只能靠猜。
    Fail(
        std::string("运行时 ONNX Runtime 版本为 ") + g_runtime_version +
        "，不支持本模块所需的 C API 版本 " + std::to_string(ORT_API_VERSION));
    return false;
  }

  g_api = api;
  __android_log_print(
      ANDROID_LOG_INFO, kTag, "ONNX Runtime 已加载：版本 %s，C API %d",
      g_runtime_version.c_str(), ORT_API_VERSION);
  return true;
}

/** 读取会话里的输入/输出名，确认期望的名字都在。**调用前必须已持有 [g_mutex]。** */
bool VerifyTensorNames() {
  OrtAllocator *allocator = nullptr;
  if (g_api->GetAllocatorWithDefaultOptions(&allocator) != nullptr) {
    Fail("取默认分配器失败");
    return false;
  }

  auto collect = [&](bool is_input, std::string *joined) {
    size_t count = 0;
    OrtStatus *status = is_input ? g_api->SessionGetInputCount(g_session, &count)
                                 : g_api->SessionGetOutputCount(g_session, &count);
    if (status != nullptr) {
      FailOnStatus("读取会话张量数量失败", status);
      return false;
    }
    bool found_input = false;
    bool found_mask = false;
    bool found_output = false;
    for (size_t i = 0; i < count; ++i) {
      char *name = nullptr;
      // 注意：allocator 本身已是 OrtAllocator*，这里不要写 &allocator
      status = is_input ? g_api->SessionGetInputName(g_session, i, allocator, &name)
                        : g_api->SessionGetOutputName(g_session, i, allocator, &name);
      if (status != nullptr) {
        FailOnStatus("读取会话张量名失败", status);
        return false;
      }
      if (name != nullptr) {
        if (!joined->empty()) joined->append(", ");
        joined->append(name);
        if (is_input && std::strcmp(name, kInputName) == 0) found_input = true;
        if (is_input && std::strcmp(name, kMaskName) == 0) found_mask = true;
        if (!is_input && std::strcmp(name, kOutputName) == 0) found_output = true;
        allocator->Free(allocator, name);
      }
    }
    if (is_input) return found_input && found_mask;
    return found_output;
  };

  std::string input_names;
  if (!collect(true, &input_names)) {
    Fail("模型输入名不符合预期（需要 " + std::string(kInputName) + "/" + kMaskName +
         "，实际为 " + input_names + "）");
    return false;
  }
  std::string output_names;
  if (!collect(false, &output_names)) {
    Fail("模型输出名不符合预期（需要 " + std::string(kOutputName) +
         "，实际为 " + output_names + "）");
    return false;
  }
  return true;
}

/** 释放会话与其相关资源（保留 env 与 ORT 句柄，便于重新加载）。**调用前必须已持有 [g_mutex]。** */
void ReleaseSessionLocked() {
  if (g_session != nullptr && g_api != nullptr) {
    g_api->ReleaseSession(g_session);
  }
  g_session = nullptr;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_input_handwriting_ochwpro_HandwritingOnnxNative_nativeLoad(
    JNIEnv *env, jobject /* thiz */, jstring model_path) {
  std::lock_guard<std::mutex> lock(g_mutex);
  g_last_error.clear();
  if (g_session != nullptr) return JNI_TRUE;  // 已加载，幂等

  if (model_path == nullptr) {
    Fail("模型路径为空");
    return JNI_FALSE;
  }
  const char *raw_path = env->GetStringUTFChars(model_path, nullptr);
  if (raw_path == nullptr) {
    Fail("读取模型路径失败");
    return JNI_FALSE;
  }
  const std::string path(raw_path);
  env->ReleaseStringUTFChars(model_path, raw_path);

  if (!LoadOrtApi()) return JNI_FALSE;

  if (g_env == nullptr) {
    OrtStatus *status = g_api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "handwriting", &g_env);
    if (status != nullptr) {
      FailOnStatus("创建 OrtEnv 失败", status);
      return JNI_FALSE;
    }
  }
  if (g_memory_info == nullptr) {
    OrtStatus *status =
        g_api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &g_memory_info);
    if (status != nullptr) {
      FailOnStatus("创建 CPU 内存信息失败", status);
      return JNI_FALSE;
    }
  }

  OrtSessionOptions *options = nullptr;
  OrtStatus *status = g_api->CreateSessionOptions(&options);
  if (status != nullptr) {
    FailOnStatus("创建会话选项失败", status);
    return JNI_FALSE;
  }

  // 识别本来就在 Kotlin 侧的单线程执行器里串行跑，这里再让 ORT 开线程池
  // 只会在输入法进程里多出调度与内存；模型只有 1.72M 参数，单线程足够。
  status = g_api->SetIntraOpNumThreads(options, 1);
  if (status == nullptr) {
    status = g_api->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL);
  }
  if (status == nullptr) {
    status = g_api->CreateSession(g_env, path.c_str(), options, &g_session);
  }
  g_api->ReleaseSessionOptions(options);
  if (status != nullptr) {
    FailOnStatus("建立 ONNX 会话失败（" + path + "）", status);
    ReleaseSessionLocked();
    return JNI_FALSE;
  }

  if (!VerifyTensorNames()) {
    ReleaseSessionLocked();
    return JNI_FALSE;
  }

  __android_log_print(ANDROID_LOG_INFO, kTag, "手写模型已加载：%s", path.c_str());
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_input_handwriting_ochwpro_HandwritingOnnxNative_nativeRun(
    JNIEnv *env, jobject /* thiz */, jfloatArray input, jbyteArray mask,
    jfloatArray out_logits) {
  std::lock_guard<std::mutex> lock(g_mutex);
  g_last_error.clear();

  if (g_session == nullptr || g_api == nullptr) {
    Fail("会话未建立");
    return JNI_FALSE;
  }
  if (input == nullptr || mask == nullptr || out_logits == nullptr) {
    Fail("入参为空");
    return JNI_FALSE;
  }

  const jsize input_size = env->GetArrayLength(input);
  const jsize mask_size = env->GetArrayLength(mask);
  const jsize output_size = env->GetArrayLength(out_logits);
  if (input_size != kFixedLen * kFeatureDim || mask_size != kFixedLen) {
    Fail(
        "输入张量尺寸不符：input=" + std::to_string(input_size) + "（应为 " +
        std::to_string(kFixedLen * kFeatureDim) + "），mask=" + std::to_string(mask_size) +
        "（应为 " + std::to_string(kFixedLen) + "）");
    return JNI_FALSE;
  }

  jfloat *input_data = env->GetFloatArrayElements(input, nullptr);
  jbyte *mask_data = env->GetByteArrayElements(mask, nullptr);
  if (input_data == nullptr || mask_data == nullptr) {
    if (input_data != nullptr) env->ReleaseFloatArrayElements(input, input_data, JNI_ABORT);
    if (mask_data != nullptr) env->ReleaseByteArrayElements(mask, mask_data, JNI_ABORT);
    Fail("取得输入数组失败");
    return JNI_FALSE;
  }

  const int64_t input_shape[3] = {1, kFixedLen, kFeatureDim};
  const int64_t mask_shape[2] = {1, kFixedLen};

  OrtValue *input_tensor = nullptr;
  OrtValue *mask_tensor = nullptr;
  OrtValue *output_tensor = nullptr;
  bool ok = false;

  do {
    if (g_api->CreateTensorWithDataAsOrtValue(
            g_memory_info, input_data, static_cast<size_t>(input_size) * sizeof(jfloat),
            input_shape, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &input_tensor) != nullptr) {
      Fail("创建输入张量失败");
      break;
    }
    if (g_api->CreateTensorWithDataAsOrtValue(
            g_memory_info, mask_data, static_cast<size_t>(mask_size) * sizeof(jbyte),
            mask_shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL, &mask_tensor) != nullptr) {
      Fail("创建掩码张量失败");
      break;
    }

    const char *input_names[2] = {kInputName, kMaskName};
    const OrtValue *inputs[2] = {input_tensor, mask_tensor};
    const char *output_names[1] = {kOutputName};
    OrtValue *outputs[1] = {nullptr};

    OrtStatus *status = g_api->Run(
        g_session, nullptr, input_names, inputs, 2, output_names, 1, outputs);
    if (status != nullptr) {
      FailOnStatus("推理失败", status);
      break;
    }
    output_tensor = outputs[0];
    if (output_tensor == nullptr) {
      Fail("推理返回空输出");
      break;
    }

    OrtTensorTypeAndShapeInfo *shape_info = nullptr;
    status = g_api->GetTensorTypeAndShape(output_tensor, &shape_info);
    if (status != nullptr) {
      FailOnStatus("读取输出形状失败", status);
      break;
    }
    size_t element_count = 0;
    status = g_api->GetTensorShapeElementCount(shape_info, &element_count);
    g_api->ReleaseTensorTypeAndShapeInfo(shape_info);
    if (status != nullptr) {
      FailOnStatus("读取输出元素数失败", status);
      break;
    }
    if (static_cast<jsize>(element_count) != output_size) {
      // 维数不符必须当错误：索引映射会整体错位，而表现只是「识别得不对」，
      // 不会抛任何异常，是最难查的一类问题。
      Fail(
          "模型输出维数 " + std::to_string(element_count) + " 与字符表长度 " +
          std::to_string(output_size) + " 不一致");
      break;
    }

    float *output_data = nullptr;
    status = g_api->GetTensorMutableData(
        output_tensor, reinterpret_cast<void **>(&output_data));
    if (status != nullptr) {
      FailOnStatus("读取输出数据失败", status);
      break;
    }
    env->SetFloatArrayRegion(out_logits, 0, output_size, output_data);
    ok = true;
  } while (false);

  if (output_tensor != nullptr) g_api->ReleaseValue(output_tensor);
  if (mask_tensor != nullptr) g_api->ReleaseValue(mask_tensor);
  if (input_tensor != nullptr) g_api->ReleaseValue(input_tensor);
  env->ReleaseByteArrayElements(mask, mask_data, JNI_ABORT);
  env->ReleaseFloatArrayElements(input, input_data, JNI_ABORT);
  return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_input_handwriting_ochwpro_HandwritingOnnxNative_nativeClose(
    JNIEnv * /* env */, jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  g_last_error.clear();
  ReleaseSessionLocked();
}

JNIEXPORT jstring JNICALL
Java_com_ninthsoft_ime_input_handwriting_ochwpro_HandwritingOnnxNative_nativeLastError(
    JNIEnv *env, jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  return env->NewStringUTF(g_last_error.c_str());
}

/**
 * 实际链到的 ONNX Runtime 版本。
 *
 * 存在的理由：本模块复用的是语音模块那份库（见文件头），
 * 「到底是哪个版本」是排查时的第一个问题，而它只能从运行时问出来。
 */
JNIEXPORT jstring JNICALL
Java_com_ninthsoft_ime_input_handwriting_ochwpro_HandwritingOnnxNative_nativeRuntimeVersion(
    JNIEnv *env, jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_runtime_version.empty()) {
    if (!LoadOrtApi()) return env->NewStringUTF("");
  }
  return env->NewStringUTF(g_runtime_version.c_str());
}

}  // extern "C"

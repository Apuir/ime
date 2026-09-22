// SPDX-License-Identifier: GPL-3.0-or-later
//
// 神经下一词预测（NWP）的 JNI 桥。
//
// ## 为什么是 dlopen，而不是链接自己的 ONNX Runtime
//
// 与 handwriting_jni 完全同一个理由（完整推导见该目录的 README 与 .cc 文件头）：
// APK 里**已经**有 sherpa-onnx AAR 提供的 libonnxruntime.so（1.27.1），
// 而 onnxruntime-android 的 AAR 会带进第二份同名库、且 `OrtGetApiBase` 的
// 符号版本标签对不上（v1.27.0 的桥要求 `@VERS_1.27.0`，sherpa 的核心只定义
// `VERS_1.27.1`）。所以这里同样只 dlsym 一个 `OrtGetApiBase`，其余全走函数表。
//
// ## 为什么 KV cache 放在 native 侧
//
// 档位 ③ 的全部意义在「增量前向 ≈16 ms，而全量重算 ≈350 ms」。缓存是
// `层数 × [1, heads, len, dim] × K/V` 的连续 float 缓冲，放在 C++ 侧可以：
//
// - **预分配一次、按位置写入**，避免每次 Run 都在 Java 堆上搬运几十 MB；
// - 用「逻辑长度」做 **O(1) 回滚**（nativeTruncate）—— 用户继续打字、
//   上一轮候选作废时，只需把长度退回已确认位置，不必清空重算。
//   这正是设计里「取消要能立刻回滚 KV cache」那一条的落点。
//
// 因此对 Kotlin 暴露的是 prefill / logits / truncate / reset 四个动作，
// 而不是「把一堆张量传进来」。
//
// ## 张量名与形状（与 scripts/nwp/model.py 的导出契约一一对应）
//
//   inputs : input_ids[1,S] int64, attention_mask[1,P+S] int64, position_ids[1,S] int64,
//            past_key_values.{i}.key / .value [1,H,P,D] float32
//   outputs: logits[1,V] float32（**只有最后一个位置**）, present.{i}.key / .value [1,H,P+S,D]
//
// `position_ids` 必须是显式输入：带 KV cache 时若不传位置，新 token 会被当成
// 从 0 开始编号，第 1 次增量起结果就全错（而且不会报错，只是预测变差）。
//
// 层数 / 头数 / 词表大小都在 nativeLoad 时**从会话自身读出来**，不信 manifest ——
// manifest 只用来决定「要不要下载、下对没有」，模型结构以模型本身为准。

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "onnxruntime_c_api.h"

namespace {

constexpr char kTag[] = "NeuralOnnx";

/** ONNX Runtime 的核心库名。由语音模块的 sherpa-onnx AAR 提供，不要改名。 */
constexpr char kOrtLibraryName[] = "libonnxruntime.so";

constexpr char kInputIdsName[] = "input_ids";
constexpr char kAttentionMaskName[] = "attention_mask";
constexpr char kPositionIdsName[] = "position_ids";
constexpr char kLogitsName[] = "logits";
constexpr char kPastPrefix[] = "past_key_values.";
constexpr char kPresentPrefix[] = "present.";
constexpr char kKeySuffix[] = ".key";
constexpr char kValueSuffix[] = ".value";

/**
 * 缓存容量的额外余量。预填之后用户每敲一个字都会增量追加，
 * 留一点余量免得「刚好越界就整段重算」这种最坏情况频繁发生。
 */
constexpr int kCacheSlack = 16;

/** 层数上限：纯粹是防御 manifest / 模型异常时把内存吃光。 */
constexpr int kMaxLayers = 128;

std::mutex g_mutex;
void *g_library = nullptr;
const OrtApi *g_api = nullptr;
OrtEnv *g_env = nullptr;
OrtSession *g_session = nullptr;
OrtMemoryInfo *g_memory_info = nullptr;
std::string g_last_error;
std::string g_runtime_version;

int g_layers = 0;
int g_heads = 0;
int g_head_dim = 0;
int g_vocab_size = 0;
int g_max_len = 0;
int g_cached_len = 0;

/** 每层一份 K/V，按位置紧凑存放，容量 [g_max_len]。 */
std::vector<std::vector<float>> g_past_key;
std::vector<std::vector<float>> g_past_value;
std::vector<float> g_logits;

/** 记录错误。**调用前必须已持有 [g_mutex]。** */
void Fail(const std::string &message) {
  g_last_error = message;
  __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
}

/** 把 OrtStatus 转成可读错误并记录（并释放它）。**调用前必须已持有 [g_mutex]。** */
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
 * **调用前必须已持有 [g_mutex]。**
 */
bool LoadOrtApi() {
  if (g_api != nullptr) return true;

  g_library = dlopen(kOrtLibraryName, RTLD_NOW | RTLD_LOCAL);
  if (g_library == nullptr) {
    Fail(ErrnoMessage(
        std::string("无法加载 ") + kOrtLibraryName +
        "（它由语音模块的 sherpa-onnx AAR 提供，若该依赖被移除，神经联想会一并失效）"));
    return false;
  }

  // dlsym 返回 void*，直接 reinterpret_cast 会在 -Wpedantic 下告警，用 memcpy 转换。
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
    // 版本不匹配时 GetApi 返回空，而错误本身不会说明双方版本，这句日志是唯一线索。
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

/** 读取会话第 index 个输入/输出的名字。**调用前必须已持有 [g_mutex]。** */
bool SessionTensorName(bool is_input, size_t index, std::string *out) {
  OrtAllocator *allocator = nullptr;
  if (g_api->GetAllocatorWithDefaultOptions(&allocator) != nullptr) {
    Fail("取默认分配器失败");
    return false;
  }
  char *name = nullptr;
  // allocator 已经是 OrtAllocator*，这里不要写 &allocator
  OrtStatus *status = is_input ? g_api->SessionGetInputName(g_session, index, allocator, &name)
                               : g_api->SessionGetOutputName(g_session, index, allocator, &name);
  if (status != nullptr) {
    FailOnStatus("读取会话张量名失败", status);
    return false;
  }
  *out = (name != nullptr) ? name : "";
  if (name != nullptr) allocator->Free(allocator, name);
  return true;
}

/**
 * 读取会话第 index 个输入/输出的形状。
 * 动态维在 ORT 里是 -1，所以 [1, H, P, D] 里的 P 会读到 -1 —— 这正是我们
 * 想区分的「固定维」与「动态维」。
 * **调用前必须已持有 [g_mutex]。**
 */
bool SessionTensorDims(bool is_input, size_t index, std::vector<int64_t> *dims) {
  OrtTypeInfo *type_info = nullptr;
  OrtStatus *status = is_input ? g_api->SessionGetInputTypeInfo(g_session, index, &type_info)
                               : g_api->SessionGetOutputTypeInfo(g_session, index, &type_info);
  if (status != nullptr) {
    FailOnStatus("读取张量类型失败", status);
    return false;
  }

  const OrtTensorTypeAndShapeInfo *shape_info = nullptr;
  status = g_api->CastTypeInfoToTensorInfo(type_info, &shape_info);
  if (status != nullptr || shape_info == nullptr) {
    if (status != nullptr) {
      FailOnStatus("张量类型不是普通张量", status);
    } else {
      Fail("张量类型不是普通张量");
    }
    g_api->ReleaseTypeInfo(type_info);
    return false;
  }

  size_t rank = 0;
  status = g_api->GetDimensionsCount(shape_info, &rank);
  if (status != nullptr) {
    FailOnStatus("读取张量维度数失败", status);
    g_api->ReleaseTypeInfo(type_info);
    return false;
  }
  dims->assign(rank, 0);
  if (rank > 0) {
    status = g_api->GetDimensions(shape_info, dims->data(), rank);
    if (status != nullptr) {
      FailOnStatus("读取张量维度失败", status);
      g_api->ReleaseTypeInfo(type_info);
      return false;
    }
  }
  // shape_info 由 type_info 拥有，只释放 type_info
  g_api->ReleaseTypeInfo(type_info);
  return true;
}

/**
 * 解析 `past_key_values.7.key` 这类名字里的层号。
 * 只接受纯数字，避免把意外的名字当成第 0 层。
 */
bool ParseLayerIndex(
    const std::string &name, const char *prefix, const char *suffix, int *index) {
  const size_t prefix_len = std::strlen(prefix);
  const size_t suffix_len = std::strlen(suffix);
  if (name.size() <= prefix_len + suffix_len) return false;
  if (name.compare(0, prefix_len, prefix) != 0) return false;
  if (name.compare(name.size() - suffix_len, suffix_len, suffix) != 0) return false;

  const std::string number = name.substr(prefix_len, name.size() - prefix_len - suffix_len);
  if (number.empty() || number.size() > 3) return false;
  int value = 0;
  for (const char c : number) {
    if (c < '0' || c > '9') return false;
    value = value * 10 + (c - '0');
  }
  if (value >= kMaxLayers) return false;
  *index = value;
  return true;
}

std::string PastName(int layer, bool is_key) {
  return std::string(kPastPrefix) + std::to_string(layer) + (is_key ? kKeySuffix : kValueSuffix);
}

std::string PresentName(int layer, bool is_key) {
  return std::string(kPresentPrefix) + std::to_string(layer) +
      (is_key ? kKeySuffix : kValueSuffix);
}

/** 释放会话与缓存（保留 env 与 ORT 句柄，便于重新加载）。**调用前必须已持有 [g_mutex]。** */
void ReleaseSessionLocked() {
  if (g_session != nullptr && g_api != nullptr) {
    g_api->ReleaseSession(g_session);
  }
  g_session = nullptr;

  g_layers = 0;
  g_heads = 0;
  g_head_dim = 0;
  g_vocab_size = 0;
  g_max_len = 0;
  g_cached_len = 0;
  // 连同容量一起释放：没有会话时这几十 MB 没有任何意义
  g_past_key.clear();
  g_past_value.clear();
  g_past_key.shrink_to_fit();
  g_past_value.shrink_to_fit();
  g_logits.clear();
  g_logits.shrink_to_fit();
}

/**
 * 核对模型结构与导出契约一致，并记录层数 / 头数 / 词表大小。
 *
 * 名字不符就明确报错，而不是带着错的假设跑到推理阶段 ——
 * 那种失败表现为「候选莫名其妙」，比报错难查得多。
 * **调用前必须已持有 [g_mutex]。**
 */
bool DiscoverModelLocked() {
  size_t input_count = 0;
  if (OrtStatus *status = g_api->SessionGetInputCount(g_session, &input_count)) {
    FailOnStatus("读取输入数量失败", status);
    return false;
  }
  size_t output_count = 0;
  if (OrtStatus *status = g_api->SessionGetOutputCount(g_session, &output_count)) {
    FailOnStatus("读取输出数量失败", status);
    return false;
  }

  bool has_ids = false;
  bool has_mask = false;
  bool has_positions = false;
  int layer_count = 0;
  std::vector<bool> key_seen(kMaxLayers, false);
  std::vector<bool> value_seen(kMaxLayers, false);
  size_t past_key_input_index = 0;
  bool has_past_key_input = false;
  std::string input_names;

  for (size_t i = 0; i < input_count; ++i) {
    std::string name;
    if (!SessionTensorName(true, i, &name)) return false;
    if (!input_names.empty()) input_names.append(", ");
    input_names.append(name);

    if (name == kInputIdsName) {
      has_ids = true;
    } else if (name == kAttentionMaskName) {
      has_mask = true;
    } else if (name == kPositionIdsName) {
      has_positions = true;
    } else {
      int layer = 0;
      if (ParseLayerIndex(name, kPastPrefix, kKeySuffix, &layer)) {
        key_seen[layer] = true;
        if (layer + 1 > layer_count) layer_count = layer + 1;
        if (!has_past_key_input || layer < static_cast<int>(past_key_input_index)) {
          past_key_input_index = i;
          has_past_key_input = true;
        }
      } else if (ParseLayerIndex(name, kPastPrefix, kValueSuffix, &layer)) {
        value_seen[layer] = true;
        if (layer + 1 > layer_count) layer_count = layer + 1;
      }
    }
  }

  if (!has_ids || !has_mask || !has_positions) {
    Fail(
        std::string("模型缺少必需输入（需要 ") + kInputIdsName + "/" + kAttentionMaskName + "/" +
        kPositionIdsName + "，实际为 " + input_names + "）");
    return false;
  }
  if (layer_count <= 0) {
    Fail(std::string("模型没有 ") + kPastPrefix + "N" + kKeySuffix + " 输入，无法使用 KV cache");
    return false;
  }
  for (int layer = 0; layer < layer_count; ++layer) {
    if (!key_seen[layer] || !value_seen[layer]) {
      Fail("KV cache 输入不成对，缺少第 " + std::to_string(layer) + " 层");
      return false;
    }
  }

  // present.* 输出必须与输入层数一致，否则缓存接不上
  bool has_logits = false;
  size_t logits_index = 0;
  std::string output_names;
  std::vector<bool> present_key_seen(kMaxLayers, false);
  std::vector<bool> present_value_seen(kMaxLayers, false);
  for (size_t i = 0; i < output_count; ++i) {
    std::string name;
    if (!SessionTensorName(false, i, &name)) return false;
    if (!output_names.empty()) output_names.append(", ");
    output_names.append(name);

    if (name == kLogitsName) {
      has_logits = true;
      logits_index = i;
    } else {
      int layer = 0;
      if (ParseLayerIndex(name, kPresentPrefix, kKeySuffix, &layer)) {
        present_key_seen[layer] = true;
      } else if (ParseLayerIndex(name, kPresentPrefix, kValueSuffix, &layer)) {
        present_value_seen[layer] = true;
      }
    }
  }
  if (!has_logits) {
    Fail(std::string("模型没有 ") + kLogitsName + " 输出（实际为 " + output_names + "）");
    return false;
  }
  for (int layer = 0; layer < layer_count; ++layer) {
    if (!present_key_seen[layer] || !present_value_seen[layer]) {
      Fail("模型缺少第 " + std::to_string(layer) + " 层的 present.* 输出，KV cache 无法维护");
      return false;
    }
  }

  // 头数与 head_dim 从 past_key_values.<首层>.key 的形状读：期望 [1, H, P, D]
  std::vector<int64_t> key_dims;
  if (!SessionTensorDims(true, past_key_input_index, &key_dims)) return false;
  if (key_dims.size() != 4) {
    Fail(
        "past_key_values.*.key 的维度数应为 4（[1,H,P,D]），实际为 " +
        std::to_string(key_dims.size()));
    return false;
  }
  if (key_dims[1] <= 0 || key_dims[3] <= 0) {
    Fail("无法从模型读出头数与 head_dim（首层 K 形状为动态维）");
    return false;
  }

  // 词表大小取 logits 输出最后一维；只支持 [1,V] 或 [1,S,V]
  std::vector<int64_t> logits_dims;
  if (!SessionTensorDims(false, logits_index, &logits_dims)) return false;
  if (logits_dims.size() < 2) {
    Fail("logits 输出至少要有 2 维，实际为 " + std::to_string(logits_dims.size()));
    return false;
  }
  const int64_t vocab = logits_dims[logits_dims.size() - 1];
  if (vocab <= 0) {
    Fail("无法从 logits 输出读出词表大小");
    return false;
  }

  g_layers = layer_count;
  g_heads = static_cast<int>(key_dims[1]);
  g_head_dim = static_cast<int>(key_dims[3]);
  g_vocab_size = static_cast<int>(vocab);
  return true;
}

/** 按 [g_max_len] 预分配缓存。**调用前必须已持有 [g_mutex]。** */
void AllocateCacheLocked() {
  const size_t per_layer =
      static_cast<size_t>(g_max_len) * static_cast<size_t>(g_heads) * g_head_dim;
  g_past_key.assign(static_cast<size_t>(g_layers), std::vector<float>(per_layer, 0.0f));
  g_past_value.assign(static_cast<size_t>(g_layers), std::vector<float>(per_layer, 0.0f));
  g_logits.assign(static_cast<size_t>(g_vocab_size), 0.0f);
  g_cached_len = 0;
}

size_t ElementCount(const int64_t *shape, size_t rank) {
  size_t count = 1;
  for (size_t i = 0; i < rank; ++i) {
    if (shape[i] <= 0) return 0;
    count *= static_cast<size_t>(shape[i]);
  }
  return count;
}

std::string DescribeLocked() {
  if (g_session == nullptr) return "未加载";
  return "layers=" + std::to_string(g_layers) + " heads=" + std::to_string(g_heads) +
      " head_dim=" + std::to_string(g_head_dim) + " vocab=" + std::to_string(g_vocab_size) +
      " max_len=" + std::to_string(g_max_len);
}

}  // namespace

extern "C" {

/**
 * 建立会话并预分配 KV cache。
 *
 * [max_context_tokens] 是**字符预算**（模型能接受的最大 input_ids 长度），
 * 取自 manifest 的 `context_tokens`；[intra_op_threads] 由 Kotlin 按 CPU 规模给，
 * 用来近似「只绑大核」—— ORT 没有核心亲和性接口，只能靠限制线程数 + 异步调用。
 */
JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_base_neural_NeuralOnnxNative_nativeLoad(
    JNIEnv *env, jobject /* thiz */, jstring model_path, jint max_context_tokens,
    jint intra_op_threads) {
  std::lock_guard<std::mutex> lock(g_mutex);
  g_last_error.clear();

  if (model_path == nullptr) {
    Fail("模型路径为空");
    return JNI_FALSE;
  }
  if (max_context_tokens <= 0) {
    Fail("上下文预算必须为正");
    return JNI_FALSE;
  }
  if (!LoadOrtApi()) return JNI_FALSE;

  ReleaseSessionLocked();

  if (g_env == nullptr) {
    if (OrtStatus *status = g_api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "neural", &g_env)) {
      FailOnStatus("创建 ONNX Runtime 环境失败", status);
      return JNI_FALSE;
    }
  }
  if (g_memory_info == nullptr) {
    if (OrtStatus *status = g_api->CreateCpuMemoryInfo(
            OrtArenaAllocator, OrtMemTypeDefault, &g_memory_info)) {
      FailOnStatus("创建 CPU 内存信息失败", status);
      return JNI_FALSE;
    }
  }

  OrtSessionOptions *options = nullptr;
  if (OrtStatus *status = g_api->CreateSessionOptions(&options)) {
    FailOnStatus("创建会话选项失败", status);
    return JNI_FALSE;
  }

  const int threads = intra_op_threads > 0 ? static_cast<int>(intra_op_threads) : 2;
  if (OrtStatus *status = g_api->SetIntraOpNumThreads(options, threads)) {
    FailOnStatus("设置算子内线程数失败", status);
    g_api->ReleaseSessionOptions(options);
    return JNI_FALSE;
  }
  // 单图推理，算子间并行没有意义，固定 1 免得额外线程争抢大核
  if (OrtStatus *status = g_api->SetInterOpNumThreads(options, 1)) {
    FailOnStatus("设置算子间线程数失败", status);
    g_api->ReleaseSessionOptions(options);
    return JNI_FALSE;
  }
  if (OrtStatus *status =
          g_api->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL)) {
    FailOnStatus("设置图优化级别失败", status);
    g_api->ReleaseSessionOptions(options);
    return JNI_FALSE;
  }
  // 键盘是「偶尔算一次」的负载，自旋等待只会白白耗电；失败不影响正确性，忽略即可
  if (OrtStatus *status = g_api->AddSessionConfigEntry(
          options, "session.intra_op.allow_spinning", "0")) {
    g_api->ReleaseStatus(status);
  }

  const char *path = env->GetStringUTFChars(model_path, nullptr);
  if (path == nullptr) {
    g_api->ReleaseSessionOptions(options);
    Fail("读取模型路径失败");
    return JNI_FALSE;
  }
  OrtStatus *create_status = g_api->CreateSession(g_env, path, options, &g_session);
  env->ReleaseStringUTFChars(model_path, path);
  g_api->ReleaseSessionOptions(options);

  if (create_status != nullptr) {
    FailOnStatus("建立 ONNX 会话失败", create_status);
    return JNI_FALSE;
  }

  if (!DiscoverModelLocked()) {
    ReleaseSessionLocked();
    return JNI_FALSE;
  }

  g_max_len = static_cast<int>(max_context_tokens) + kCacheSlack;
  AllocateCacheLocked();

  __android_log_print(
      ANDROID_LOG_INFO, kTag, "神经预测模型已加载：%s，线程 %d，缓存 %.1f MB",
      DescribeLocked().c_str(), threads,
      static_cast<double>(g_layers) * 2.0 * g_max_len * g_heads * g_head_dim * 4.0 /
          (1024.0 * 1024.0));
  return JNI_TRUE;
}

/**
 * 把 [ids] 追加到已缓存的上下文之后，跑一次前向。
 *
 * 首次调用（缓存为空）就是「整段预填」；之后每次只传新增的几个字，
 * 复用缓存 —— 这是 350 ms → 16 ms 的来源。
 */
JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_base_neural_NeuralOnnxNative_nativePrefill(
    JNIEnv *env, jobject /* thiz */, jintArray ids) {
  std::lock_guard<std::mutex> lock(g_mutex);
  g_last_error.clear();

  if (g_session == nullptr || g_api == nullptr) {
    Fail("模型尚未加载");
    return JNI_FALSE;
  }
  if (ids == nullptr) {
    Fail("input_ids 为空");
    return JNI_FALSE;
  }

  const jsize seq = env->GetArrayLength(ids);
  if (seq <= 0) {
    Fail("input_ids 长度必须为正");
    return JNI_FALSE;
  }
  if (g_cached_len + seq > g_max_len) {
    Fail(
        "上下文超出缓存容量（" + std::to_string(g_cached_len + seq) + " > " +
        std::to_string(g_max_len) + "），调用方应先 truncate 或 reset");
    return JNI_FALSE;
  }

  std::vector<jint> tokens(static_cast<size_t>(seq));
  env->GetIntArrayRegion(ids, 0, seq, tokens.data());

  const int past_len = g_cached_len;
  const int total_len = past_len + seq;

  std::vector<int64_t> input_ids(static_cast<size_t>(seq));
  std::vector<int64_t> position_ids(static_cast<size_t>(seq));
  // 缓存里全是我们自己写进去的真实 token，没有 padding，所以掩码恒为 1。
  // 回滚靠的是缩短「长度」，不是靠掩码 —— 掩码长度本身就已经只覆盖有效区间。
  std::vector<int64_t> attention_mask(static_cast<size_t>(total_len), 1);
  for (int i = 0; i < seq; ++i) {
    input_ids[i] = tokens[static_cast<size_t>(i)];
    position_ids[i] = past_len + i;
  }

  // 先取名字：SessionTensorName 依赖 g_session，且生成的名字必须活到 Run 结束。
  // 用 vector<string> 一次性装满再取 c_str()，避免中途扩容让指针失效。
  std::vector<std::string> owned_names;
  owned_names.reserve(3 + static_cast<size_t>(g_layers) * 2);
  std::vector<const char *> input_names;
  input_names.reserve(3 + static_cast<size_t>(g_layers) * 2);
  std::vector<OrtValue *> values;
  values.reserve(3 + static_cast<size_t>(g_layers) * 2);

  auto release_all = [&]() {
    for (OrtValue *value : values) {
      if (value != nullptr) g_api->ReleaseValue(value);
    }
    values.clear();
  };

  const int64_t ids_shape[2] = {1, seq};
  const int64_t mask_shape[2] = {1, total_len};
  struct Int64Tensor {
    const char *name;
    int64_t *data;
    const int64_t *shape;
    size_t rank;
  };
  const Int64Tensor int64_tensors[3] = {
      {kInputIdsName, input_ids.data(), ids_shape, 2},
      {kPositionIdsName, position_ids.data(), ids_shape, 2},
      {kAttentionMaskName, attention_mask.data(), mask_shape, 2},
  };
  for (const Int64Tensor &tensor : int64_tensors) {
    OrtValue *value = nullptr;
    if (OrtStatus *status = g_api->CreateTensorWithDataAsOrtValue(
            g_memory_info, tensor.data,
            sizeof(int64_t) * ElementCount(tensor.shape, tensor.rank), tensor.shape, tensor.rank,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &value)) {
      FailOnStatus(std::string("创建张量 ") + tensor.name + " 失败", status);
      release_all();
      return JNI_FALSE;
    }
    input_names.push_back(tensor.name);
    values.push_back(value);
  }

  const int64_t cache_shape[4] = {1, g_heads, past_len, g_head_dim};
  const size_t cache_elements =
      static_cast<size_t>(past_len) * static_cast<size_t>(g_heads) * g_head_dim;
  for (int layer = 0; layer < g_layers; ++layer) {
    for (int kv = 0; kv < 2; ++kv) {
      const bool is_key = kv == 0;
      // 用输入里真实存在的名字，而不是自己拼一个 —— 拼错了只会在 Run 时报「找不到输入」
      owned_names.push_back(PastName(layer, is_key));
      float *data = is_key ? g_past_key[static_cast<size_t>(layer)].data()
                           : g_past_value[static_cast<size_t>(layer)].data();
      OrtValue *value = nullptr;
      if (OrtStatus *status = g_api->CreateTensorWithDataAsOrtValue(
              g_memory_info, data, sizeof(float) * cache_elements, cache_shape, 4,
              ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &value)) {
        FailOnStatus("创建 KV cache 输入张量失败（第 " + std::to_string(layer) + " 层）", status);
        release_all();
        return JNI_FALSE;
      }
      input_names.push_back(owned_names.back().c_str());
      values.push_back(value);
    }
  }

  std::vector<std::string> owned_output_names;
  owned_output_names.reserve(1 + static_cast<size_t>(g_layers) * 2);
  std::vector<const char *> output_names;
  output_names.reserve(1 + static_cast<size_t>(g_layers) * 2);
  output_names.push_back(kLogitsName);
  for (int layer = 0; layer < g_layers; ++layer) {
    for (int kv = 0; kv < 2; ++kv) {
      owned_output_names.push_back(PresentName(layer, kv == 0));
      output_names.push_back(owned_output_names.back().c_str());
    }
  }

  std::vector<OrtValue *> outputs(output_names.size(), nullptr);

  // input_names 与 values 始终成对 push，长度必然一致
  OrtStatus *status = g_api->Run(
      g_session, nullptr, input_names.data(), values.data(), input_names.size(),
      output_names.data(), output_names.size(), outputs.data());
  if (status != nullptr) {
    FailOnStatus("推理失败", status);
    release_all();
    return JNI_FALSE;
  }

  bool ok = true;

  // logits：只取最后一个位置，元素数应正好等于词表大小
  {
    OrtTensorTypeAndShapeInfo *shape_info = nullptr;
    if (OrtStatus *s = g_api->GetTensorTypeAndShape(outputs[0], &shape_info)) {
      FailOnStatus("读取 logits 形状失败", s);
      ok = false;
    } else {
      size_t elements = 0;
      const bool sized = g_api->GetTensorShapeElementCount(shape_info, &elements) == nullptr;
      g_api->ReleaseTensorTypeAndShapeInfo(shape_info);
      float *data = nullptr;
      if (!sized) {
        Fail("无法读取 logits 元素数");
        ok = false;
      } else if (OrtStatus *s =
                     g_api->GetTensorMutableData(outputs[0], reinterpret_cast<void **>(&data))) {
        FailOnStatus("读取 logits 数据失败", s);
        ok = false;
      } else if (data == nullptr || elements != static_cast<size_t>(g_vocab_size)) {
        Fail(
            "logits 元素数应为 " + std::to_string(g_vocab_size) + "（仅最后一个位置），实际为 " +
            std::to_string(elements));
        ok = false;
      } else {
        std::memcpy(g_logits.data(), data, sizeof(float) * static_cast<size_t>(g_vocab_size));
      }
    }
  }

  // present.* 拷回缓存。ORT 会自己分配输出，所以这里必然有一次拷贝；
  // 预分配容量让这次拷贝不需要重新分配内存。
  if (ok) {
    const size_t expected = static_cast<size_t>(total_len) * g_heads * g_head_dim;
    for (int layer = 0; layer < g_layers && ok; ++layer) {
      for (int kv = 0; kv < 2; ++kv) {
        OrtValue *value = outputs[1 + static_cast<size_t>(layer) * 2 + kv];
        OrtTensorTypeAndShapeInfo *shape_info = nullptr;
        if (OrtStatus *s = g_api->GetTensorTypeAndShape(value, &shape_info)) {
          FailOnStatus("读取 present 形状失败", s);
          ok = false;
          break;
        }
        size_t elements = 0;
        const bool sized = g_api->GetTensorShapeElementCount(shape_info, &elements) == nullptr;
        g_api->ReleaseTensorTypeAndShapeInfo(shape_info);

        float *data = nullptr;
        if (!sized) {
          Fail("无法读取 present 元素数");
          ok = false;
          break;
        }
        if (OrtStatus *s = g_api->GetTensorMutableData(value, reinterpret_cast<void **>(&data))) {
          FailOnStatus("读取 present 数据失败", s);
          ok = false;
          break;
        }
        if (data == nullptr || elements != expected) {
          Fail(
              "present 元素数应为 " + std::to_string(expected) + "，实际为 " +
              std::to_string(elements));
          ok = false;
          break;
        }
        float *destination = kv == 0 ? g_past_key[static_cast<size_t>(layer)].data()
                                     : g_past_value[static_cast<size_t>(layer)].data();
        std::memcpy(destination, data, sizeof(float) * expected);
      }
    }
  }

  for (OrtValue *value : outputs) {
    if (value != nullptr) g_api->ReleaseValue(value);
  }
  release_all();

  if (!ok) return JNI_FALSE;

  g_cached_len = total_len;
  return JNI_TRUE;
}

/** 取最后一次前向最后一个位置的 logits（长度等于词表大小）。 */
JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_base_neural_NeuralOnnxNative_nativeLogits(
    JNIEnv *env, jobject /* thiz */, jfloatArray out) {
  std::lock_guard<std::mutex> lock(g_mutex);
  g_last_error.clear();

  if (g_session == nullptr) {
    Fail("模型尚未加载");
    return JNI_FALSE;
  }
  if (out == nullptr) {
    Fail("输出数组为空");
    return JNI_FALSE;
  }
  const jsize size = env->GetArrayLength(out);
  if (size != g_vocab_size) {
    Fail("输出数组长度应为 " + std::to_string(g_vocab_size) + "，实际为 " + std::to_string(size));
    return JNI_FALSE;
  }
  env->SetFloatArrayRegion(out, 0, size, g_logits.data());
  return JNI_TRUE;
}

/** 当前有效缓存长度（字符数）。 */
JNIEXPORT jint JNICALL
Java_com_ninthsoft_ime_base_neural_NeuralOnnxNative_nativeCachedLen(
    JNIEnv * /* env */, jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  return g_cached_len;
}

/**
 * 把缓存回滚到 [len] 个字符。
 *
 * O(1)：缓存是按位置紧凑存放的，注意力只会读 `[0, len)`，
 * 所以只要把长度计数器退回去，后面的残留值永远不会被读到。
 * 用户继续打字丢弃上一轮候选时，这一步就是「回滚 KV cache」的全部代价。
 */
JNIEXPORT jboolean JNICALL
Java_com_ninthsoft_ime_base_neural_NeuralOnnxNative_nativeTruncate(
    JNIEnv * /* env */, jobject /* thiz */, jint len) {
  std::lock_guard<std::mutex> lock(g_mutex);
  g_last_error.clear();

  if (g_session == nullptr) {
    Fail("模型尚未加载");
    return JNI_FALSE;
  }
  if (len < 0 || len > g_cached_len) {
    Fail(
        "回滚目标越界（" + std::to_string(len) + " 不在 [0, " + std::to_string(g_cached_len) +
        "] 内）");
    return JNI_FALSE;
  }
  g_cached_len = len;
  return JNI_TRUE;
}

/** 清空缓存（下一个字重新整段预填）。 */
JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_base_neural_NeuralOnnxNative_nativeReset(
    JNIEnv * /* env */, jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  g_cached_len = 0;
}

/** 只释放会话，保留 env 与 dlopen 句柄，便于重新加载。 */
JNIEXPORT void JNICALL
Java_com_ninthsoft_ime_base_neural_NeuralOnnxNative_nativeClose(
    JNIEnv * /* env */, jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  ReleaseSessionLocked();
}

JNIEXPORT jstring JNICALL
Java_com_ninthsoft_ime_base_neural_NeuralOnnxNative_nativeLastError(
    JNIEnv *env, jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_ninthsoft_ime_base_neural_NeuralOnnxNative_nativeRuntimeVersion(
    JNIEnv *env, jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_runtime_version.empty() && LoadOrtApi()) {
    // 仅为取版本号，失败信息保留在 g_last_error 里
  }
  return env->NewStringUTF(g_runtime_version.c_str());
}

/** 模型结构摘要，用于设置页显示与排障。 */
JNIEXPORT jstring JNICALL
Java_com_ninthsoft_ime_base_neural_NeuralOnnxNative_nativeDescribe(
    JNIEnv *env, jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  return env->NewStringUTF(DescribeLocked().c_str());
}

}  // extern "C"

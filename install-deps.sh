#!/bin/bash

# 严格模式：遇到错误立即停止
set -e

# --- 配置区域 ---
DEPS_DIR="app/src/main/cpp/deps"
BOOST_VERSION="1.89.0"
BOOST_DIR="$DEPS_DIR/boost" # 解压到根目录下的 boost 文件夹
BOOST_URL="https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz"
BOOST_HASH="67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74"

# --- librime：固定 commit，不再跟着别人的 main 走 ---
# 默认仍指向上游 danjian/librime。把它 fork 到自己的账号后，改这一行（或设同名环境变量），
# 构建就不再依赖上游仓库是否还在。版本固定到具体 commit，上游推新代码也不会突然把构建搞坏。
LIBRIME_REPO="${LIBRIME_REPO:-https://github.com/danjian/librime.git}"
LIBRIME_COMMIT="${LIBRIME_COMMIT:-95d3e11b335d484baa2e80346dea517565b463bb}"
LIBRIME_DIR="$DEPS_DIR/librime"

# Git 依赖定义
DEPS=(
    "$DEPS_DIR/OpenCC|https://github.com/BYVoid/OpenCC.git|master"
    "$DEPS_DIR/snappy|https://github.com/google/snappy.git|main"
    "$DEPS_DIR/librime-lua|https://github.com/hchunhui/librime-lua.git|master"
    "$DEPS_DIR/librime-lua-deps|https://github.com/hchunhui/librime-lua.git|thirdparty"
    "$DEPS_DIR/librime-octagram|https://github.com/lotem/librime-octagram.git|master"
    "$DEPS_DIR/librime-predict|https://github.com/rime/librime-predict.git|master"
    "$DEPS_DIR/llama.cpp|https://github.com/ggml-org/llama.cpp.git|master"
)

echo ">>> 同步 librime（固定 commit: $LIBRIME_COMMIT）..."
if [ ! -d "$LIBRIME_DIR/.git" ]; then
    if [ -d "$LIBRIME_DIR" ]; then
        echo ">>> 清理非 git 残留目录: $LIBRIME_DIR"
        rm -rf "$LIBRIME_DIR"
    fi
    echo ">>> 克隆: $LIBRIME_DIR"
    mkdir -p "$DEPS_DIR"
    # 需要按 commit 检出，所以不能用 --depth 1 -b <branch>
    git clone "$LIBRIME_REPO" "$LIBRIME_DIR"
else
    echo ">>> 更新: $LIBRIME_DIR"
    git -C "$LIBRIME_DIR" remote set-url origin "$LIBRIME_REPO"
    git -C "$LIBRIME_DIR" fetch origin --tags
fi
git -C "$LIBRIME_DIR" checkout --detach "$LIBRIME_COMMIT"

echo ">>> 开始同步 Git 依赖..."
for item in "${DEPS[@]}"; do
    IFS="|" read -r path url branch <<< "$item"

    if [ ! -d "$path/.git" ]; then
        # AGP 配置 CMake 时会在 deps/librime/plugins 下建空目录，导致 git clone 报
        # “目标路径已存在且不是空目录”。这里把非 git 残留清掉，保证脚本真正幂等。
        if [ -d "$path" ]; then
            echo ">>> 清理非 git 残留目录: $path"
            rm -rf "$path"
        fi
        echo ">>> 克隆: $path"
        mkdir -p "$(dirname "$path")"
        git clone --depth 1 -b "$branch" "$url" "$path"
    else
        echo ">>> 更新: $path (分支: $branch)"
        cd "$path"
        git fetch origin "$branch"
        git reset --hard "origin/$branch"
        cd - > /dev/null
    fi
done

echo ">>> 开始同步 Boost 依赖..."
if [ ! -d "$BOOST_DIR" ]; then
    echo ">>> 下载 Boost ${BOOST_VERSION}..."
    # 使用 curl 下载
    curl -L "$BOOST_URL" -o "boost.tar.xz"

    # 校验哈希
    if command -v sha256sum &> /dev/null; then
      echo "$BOOST_HASH  boost.tar.xz" | sha256sum -c -
    elif command -v shasum &> /dev/null; then
      echo "$BOOST_HASH  boost.tar.xz" | shasum -a 256 -c -
    else
      echo "Warning: no sha256sum or shasum found, skipping hash check"
    fi

    echo ">>> 解压 Boost..."
    mkdir -p "$BOOST_DIR"
    tar -xf "boost.tar.xz" -C "$BOOST_DIR" --strip-components=1
    rm "boost.tar.xz"
else
    echo ">>> Boost 已存在，跳过下载。"
fi

# Git 依赖定义
RIME_DEPS=(
    "$DEPS_DIR/librime/deps/glog|https://github.com/google/glog.git|master"
    "$DEPS_DIR/librime/deps/yaml-cpp|https://github.com/jbeder/yaml-cpp.git|master"
    "$DEPS_DIR/librime/deps/leveldb|https://github.com/google/leveldb.git|main"
    "$DEPS_DIR/librime/deps/marisa-trie|https://github.com/s-yata/marisa-trie.git|master"
)

echo ">>> 开始同步librime Git 依赖..."

for item in "${RIME_DEPS[@]}"; do
    IFS="|" read -r path url branch <<< "$item"

    if [ ! -d "$path/.git" ]; then
        # AGP 配置 CMake 时会在 deps/librime/plugins 下建空目录，导致 git clone 报
        # “目标路径已存在且不是空目录”。这里把非 git 残留清掉，保证脚本真正幂等。
        if [ -d "$path" ]; then
            echo ">>> 清理非 git 残留目录: $path"
            rm -rf "$path"
        fi
        echo ">>> 克隆: $path"
        mkdir -p "$(dirname "$path")"
        git clone --depth 1 -b "$branch" "$url" "$path"
    else
        echo ">>> 更新: $path (分支: $branch)"
        cd "$path"
        git fetch origin "$branch"
        git reset --hard "origin/$branch"
        cd - > /dev/null
    fi
done

# --- 给 librime 补上 C API 的 kind 字段 ---
# 固定的那份 librime（见文件开头的 LIBRIME_COMMIT）只加了内部 Schema::kind_，忘了暴露到
# RimeSchemaListItem，而 librime_jni/rime_data.h 直接读 item.kind，
# 不补会导致 native 编译报 “no member named 'kind'”。
# 如果换成已经自带该字段的版本，这里会自动跳过。
LIBRIME_SRC="$DEPS_DIR/librime/src"
if [ -f "$LIBRIME_SRC/rime_api.h" ] && ! grep -qE "char\s*\*\s*kind;" "$LIBRIME_SRC/rime_api.h"; then
    echo ">>> 给 librime 打补丁：把 schema kind 暴露到 C API"
    git -C "$DEPS_DIR/librime" apply --whitespace=nowarn - <<'LIBRIME_KIND_PATCH'
diff --git a/src/rime/lever/levers_api_impl.h b/src/rime/lever/levers_api_impl.h
index 60263a7c..7acf2040 100644
--- a/src/rime/lever/levers_api_impl.h
+++ b/src/rime/lever/levers_api_impl.h
@@ -110,6 +110,7 @@ static Bool rime_levers_get_available_schema_list(
     item.name = const_cast<char*>(info.name.c_str());
     item.layout = const_cast<char*>(info.layout.c_str());
     item.punctuation = const_cast<char*>(info.punctuation.c_str());
+    item.kind = const_cast<char*>(info.kind.c_str());
     item.reserved = const_cast<SchemaInfo*>(&info);
     ++list->size;
   }
@@ -131,6 +132,7 @@ static Bool rime_levers_get_selected_schema_list(RimeSwitcherSettings* settings,
     item.name = NULL;
     item.layout = const_cast<char*>("");
     item.punctuation = const_cast<char*>("");
+    item.kind = const_cast<char*>("");
     item.reserved = NULL;
     ++list->size;
   }
diff --git a/src/rime/lever/switcher_settings.cc b/src/rime/lever/switcher_settings.cc
index 83ff53a2..bf2c6dec 100644
--- a/src/rime/lever/switcher_settings.cc
+++ b/src/rime/lever/switcher_settings.cc
@@ -76,6 +76,7 @@ void SwitcherSettings::GetAvailableSchemasFromDirectory(const path& dir) {
         config.GetString("schema/version", &info.version);
         config.GetString("schema/layout", &info.layout);
         config.GetString("schema/punctuation", &info.punctuation);
+        config.GetString("schema/kind", &info.kind);
         if (auto authors = config.GetList("schema/author")) {
           for (size_t i = 0; i < authors->size(); ++i) {
             auto author = authors->GetValueAt(i);
diff --git a/src/rime/lever/switcher_settings.h b/src/rime/lever/switcher_settings.h
index f677a7e7..1f4a189d 100644
--- a/src/rime/lever/switcher_settings.h
+++ b/src/rime/lever/switcher_settings.h
@@ -17,6 +17,7 @@ struct SchemaInfo {
   string name;
   string layout;
   string punctuation;
+  string kind;
   string version;
   string author;
   string description;
diff --git a/src/rime_api.h b/src/rime_api.h
index 450cfc09..b281f0f0 100644
--- a/src/rime_api.h
+++ b/src/rime_api.h
@@ -223,6 +223,7 @@ typedef struct rime_schema_list_item_t {
   char* name;
   char* layout;
   char* punctuation;
+  char* kind;
   void* reserved;
 } RimeSchemaListItem;
 
diff --git a/src/rime_api_impl.h b/src/rime_api_impl.h
index 9eb192d2..5329bb99 100644
--- a/src/rime_api_impl.h
+++ b/src/rime_api_impl.h
@@ -627,6 +627,8 @@ RIME_DEPRECATED Bool RimeGetSchemaList(RimeSchemaList* output) {
     strcpy(x.layout, schema.layout().c_str());
     x.punctuation = new char[schema.punctuation().length() + 1];
     strcpy(x.punctuation, schema.punctuation().c_str());
+    x.kind = new char[schema.kind().length() + 1];
+    strcpy(x.kind, schema.kind().c_str());
     x.reserved = NULL;
     ++output->size;
   }
@@ -647,6 +649,7 @@ RIME_DEPRECATED void RimeFreeSchemaList(RimeSchemaList* schema_list) {
       delete[] schema_list->list[i].name;
       delete[] schema_list->list[i].layout;
       delete[] schema_list->list[i].punctuation;
+      delete[] schema_list->list[i].kind;
     }
     delete[] schema_list->list;
   }
LIBRIME_KIND_PATCH
else
    echo ">>> librime 已包含 C API kind 字段，跳过补丁"
fi

# --- 修正 librime 的「简拼限流」补丁（这是「手机上候选不通」的根因）---
# 固定的那份 librime（danjian/librime fork）带了一个私人补丁
#   feat: kAbbreviation rate limit when table search
# 它把 kMaxAbbreviationExpand 写成**整个 BFS 共用一个计数器、上限 2**。
# BFS 是广度优先，于是只有最先展开的两三条简拼路径能活下来 —— 全简拼输入
# （每个音节只打一个字母）的候选因此变成「随便剩下几个词」：
#   真机 zj → 传记 / zjh → 传记和 / zjhjszydcld → 传记和健身转悠电池了的
#   上游 librime 同一份数据：zj → 自己 / zjh → 这句话 / zjhjszydcld → 这句话就是这样多出来的
# 这里改成**逐路径**计数（上限 32），并把总迭代护栏 5120 提到 65536。
# 上游哪天自己修好了（不再有 kMaxAbbreviationExpand），这里会自动跳过。
if [ -f "$LIBRIME_SRC/rime/dict/table.cc" ] && grep -q "kMaxAbbreviationExpand" "$LIBRIME_SRC/rime/dict/table.cc"; then
    echo ">>> 给 librime 打补丁：把简拼展开限制从「全局」改成「逐路径」"
    git -C "$DEPS_DIR/librime" apply --whitespace=nowarn - <<'LIBRIME_ABBREV_PATCH'
diff --git a/src/rime/dict/table.cc b/src/rime/dict/table.cc
index 866f458a..ad61ebcf 100644
--- a/src/rime/dict/table.cc
+++ b/src/rime/dict/table.cc
@@ -567,10 +567,27 @@ TableAccessor Table::QueryPhrases(const Code& code) {
 
 // log(0.05) ≈ -3.0
 const double kPenaltyForAmbiguousSyllable = -2.995732274;
-// 限制 kAbbreviation 类型边在单次 Query 中的总迭代次数
-const size_t kAbbreviationIterationLimit = 5120;
-// 限制 kAbbreviation 类型边在单次 Query 中的扩展搜索数
-const size_t kMaxAbbreviationExpand = 2;
+// 限制 kAbbreviation 类型边在单次 Query 中的总迭代次数（纯粹的性能护栏）
+const size_t kAbbreviationIterationLimit = 65536;
+// 限制**单条路径**上 kAbbreviation 类型边的个数。
+//
+// ⚠️ 这里原来是「单次 Query 的全局计数、上限 2」（见上游 danjian/librime 的
+// `feat: kAbbreviation rate limit when table search`），那是个 bug：BFS 是广度优先、
+// 计数跨所有路径共享，于是**只有最先被展开的两三条简拼路径能活下来**，其余全被丢掉。
+// 表现就是「全简拼输入（如 zjhjszydcld）在本机给出的是随便几个词，而同一个词库在
+// 上游 librime 上给出的是正确整句」，而且候选看起来完全不通（实测：手机上 zj →
+// 传记，上游 librime zj → 自己 / zjh → 这句话）。
+//
+// 限制本身要保留（11 个字母的简拼会展开出极多音节组合），但必须按路径计：
+// 上限取 32，足以覆盖任何正常长度的整句简拼，同时仍然挡住病态的深展开。
+const size_t kMaxAbbreviationPerPath = 32;
+
+// BFS 状态：除 TableQuery 外还要记「这条路径上已经用了几次简拼边」。
+struct TableQueryState {
+  size_t pos;
+  TableQuery query;
+  size_t abbreviation_count;
+};
 
 bool Table::Query(const SyllableGraph& syll_graph,
                   size_t start_pos,
@@ -578,16 +595,16 @@ bool Table::Query(const SyllableGraph& syll_graph,
   if (!result || !index_ || start_pos >= syll_graph.interpreted_length)
     return false;
   result->clear();
-  std::queue<pair<size_t, TableQuery>> q;
+  std::queue<TableQueryState> q;
   TableQuery initial_state(index_);
-  q.push({start_pos, initial_state});
+  q.push({start_pos, initial_state, 0});
 
   size_t abbreviation_iteration_count = 0;
-  size_t abbreviation_expand_count = 0;
 
   while (!q.empty()) {
-    size_t current_pos = q.front().first;
-    TableQuery query(q.front().second);
+    size_t current_pos = q.front().pos;
+    TableQuery query(q.front().query);
+    size_t abbreviation_count = q.front().abbreviation_count;
     q.pop();
     auto index = syll_graph.indices.find(current_pos);
     if (index == syll_graph.indices.end()) {
@@ -633,13 +650,15 @@ bool Table::Query(const SyllableGraph& syll_graph,
         if (end_pos < syll_graph.interpreted_length &&
             query.Advance(syll_id, next_credibility, delta_quality_len,
                           current_pos)) {
-          // 限制kAbbreviation的展开
-          if (props->type == kAbbreviation &&
-              abbreviation_expand_count++ > kMaxAbbreviationExpand) {
+          // 简拼边的**逐路径**上限：走上一条简拼边就给这条路径记一笔，
+          // 只有这条路自己走太远才剪掉，不影响别的路径（原来错在全局计数）。
+          size_t next_abbreviation_count =
+              abbreviation_count + (props->type == kAbbreviation ? 1 : 0);
+          if (next_abbreviation_count > kMaxAbbreviationPerPath) {
             query.Backdate();
             continue;
           }
-          q.push({end_pos, query});
+          q.push({end_pos, query, next_abbreviation_count});
           query.Backdate();
         }
       }
LIBRIME_ABBREV_PATCH
else
    echo ">>> librime 简拼限制已是逐路径（或上游已修），跳过补丁"
fi

# --- 简拼总开关 + 九键的简拼生效范围 ---
# 1) 简拼总开关：app 的「简拼」设置通过运行时选项 abbrev_disabled 传进来，关闭时
#    不把 kAbbreviation 拼写放进音节图 —— 26 键与九键共用这一条闸门。
# 2) 九键发的是数字串，整串常常刚好是一个完整拼音（64=ni、94=yi/zhi），librime
#    默认会把简拼边当「更差的拼写」剪掉，于是 64 出不来「你好」；方案里声明
#    speller/abbrev_max_length 后，短输入下把剪枝下限抬掉，同时简拼只对短输入
#    生效（长串的简拼读法不像本意，简拼边还会让候选图暴涨：8 位全拼输入
#    实测 3.9ms → 235ms）。
# 上游哪天自己支持了，这里会自动跳过。
if [ -f "$LIBRIME_SRC/rime/gear/script_translator.cc" ] && ! grep -q "abbrev_disabled" "$LIBRIME_SRC/rime/gear/script_translator.cc"; then
    echo ">>> 给 librime 打补丁：简拼总开关 + 九键简拼生效范围"
    git -C "$DEPS_DIR/librime" apply --whitespace=nowarn - <<'LIBRIME_JIANPIN_PATCH'
diff --git a/src/rime/algo/syllabifier.cc b/src/rime/algo/syllabifier.cc
index 11d0efc5..6d311a39 100644
--- a/src/rime/algo/syllabifier.cc
+++ b/src/rime/algo/syllabifier.cc
@@ -34,6 +34,29 @@ int Syllabifier::BuildSyllableGraph(const string& input,
   if (input.empty())
     return 0;
 
+  // 输入码里除分隔符以外的长度：九键用户可能打 "7'9'9'8"，分隔符不该算进上限。
+  size_t code_length = 0;
+  for (char ch : input) {
+    if (delimiters_.find(ch) == string::npos)
+      ++code_length;
+  }
+  // 九键（声明了 abbrev_max_length 的方案）的简拼只对短输入生效：
+  // 数字串越长，简拼读法越不像用户本意；而且简拼边会让候选图暴涨
+  // （实测同一份数据、8 位全拼输入：3.9ms → 235ms）。超过上限就整条不放简拼边，
+  // 退回全拼，输入框里的手感与原行为一致。
+  const bool schema_bounds_abbreviation = abbrev_max_length_ > 0;
+  const bool allow_abbreviation =
+      !suppress_abbreviation_ &&
+      (!schema_bounds_abbreviation || code_length <= abbrev_max_length_);
+  // 短输入下简拼边要保留：整串数字常常刚好是一个完整拼音（64=ni、94=yi/zhi），
+  // librime 默认会把简拼边当「更差的拼写」剪掉，剪掉了 64 就出不来「你好」。
+  const bool keep_abbreviation_on_full_parse =
+      schema_bounds_abbreviation && allow_abbreviation;
+  if (keep_abbreviation_on_full_parse) {
+    // 规模已经由输入长度限住，迭代上限反而会截断三音节以上的简拼结果。
+    graph->unlimited_abbreviation_search = true;
+  }
+
   size_t farthest = 0;
   VertexQueue queue;
   queue.push(Vertex{0, kNormalSpelling});  // start
@@ -110,8 +133,10 @@ int Syllabifier::BuildSyllableGraph(const string& input,
         while (!accessor.exhausted()) {
           SyllableId syllable_id = accessor.syllable_id();
           EdgeProperties props(accessor.properties());
-          if (strict_spelling_ && matches_input &&
-              props.type != kNormalSpelling) {
+          if (!allow_abbreviation && props.type == kAbbreviation) {
+            // 简拼总开关关闭 / 超出简拼生效长度：简拼拼写整条不入图
+          } else if (strict_spelling_ && matches_input &&
+                     props.type != kNormalSpelling) {
             // disqualify fuzzy spelling or abbreviation as single word
           } else {
             props.end_pos = end_pos;
@@ -158,8 +183,11 @@ int Syllabifier::BuildSyllableGraph(const string& input,
   set<int> good;
   good.insert(farthest);
   // fuzzy spellings are immune to invalidation by normal spellings
-  SpellingType last_type =
-      (std::max)(graph->vertices[farthest], kFuzzySpelling);
+  // 简拼边默认只比模糊音好一点，整串能全拼时会被这里剪掉；九键需要在短输入下
+  // 保留它们（64=ni 时也要能出「你好」），所以那种方案把下限抬到 kAbbreviation。
+  SpellingType last_type = (std::max)(
+      graph->vertices[farthest],
+      keep_abbreviation_on_full_parse ? kAbbreviation : kFuzzySpelling);
   for (int i = farthest - 1; i >= 0; --i) {
     if (graph->vertices.find(i) == graph->vertices.end())
       continue;
diff --git a/src/rime/algo/syllabifier.h b/src/rime/algo/syllabifier.h
index 1167e296..8219b1fb 100644
--- a/src/rime/algo/syllabifier.h
+++ b/src/rime/algo/syllabifier.h
@@ -39,6 +39,8 @@ using SpellingIndices = map<size_t, SpellingIndex>;
 struct SyllableGraph {
   size_t input_length = 0;
   size_t interpreted_length = 0;
+  // 简拼边不做迭代上限（方案声明了 abbrev_max_length 时由 [Syllabifier] 置位）。
+  bool unlimited_abbreviation_search = false;
   VertexMap vertices;
   EdgeMap edges;
   SpellingIndices indices;
@@ -58,6 +60,15 @@ class Syllabifier {
                                   Prism& prism,
                                   SyllableGraph* graph);
   RIME_DLL void EnableCorrection(Corrector* corrector);
+  // 简拼总开关：关闭时不把简拼（kAbbreviation）拼写放进音节图。
+  void SuppressAbbreviation(bool suppress) {
+    suppress_abbreviation_ = suppress;
+  }
+  // 简拼边只在**非分隔符**输入码长度不超过 max_length 时参与；0 表示不限
+  // （librime 默认行为）。九键发数字串，见 syllabifier.cc 里的说明。
+  void RestrictAbbreviationToInputLength(size_t max_length) {
+    abbrev_max_length_ = max_length;
+  }
 
  protected:
   void CheckOverlappedSpellings(SyllableGraph* graph, size_t start, size_t end);
@@ -66,6 +77,8 @@ class Syllabifier {
   string delimiters_;
   bool enable_completion_ = false;
   bool strict_spelling_ = false;
+  bool suppress_abbreviation_ = false;
+  size_t abbrev_max_length_ = 0;
   Corrector* corrector_ = nullptr;
 };
 
diff --git a/src/rime/gear/script_translator.cc b/src/rime/gear/script_translator.cc
index aef62178..40301fb6 100644
--- a/src/rime/gear/script_translator.cc
+++ b/src/rime/gear/script_translator.cc
@@ -87,6 +87,11 @@ class ScriptSyllabifier : public PhraseSyllabifier {
         syllabifier_(translator->delimiters(),
                      translator->enable_completion(),
                      translator->strict_spelling()) {
+    // 这两个开关都作用于音节图：前者是简拼总开关（每次查询现读运行时选项），
+    // 后者把简拼限定在短输入上（九键的数字输入需要，见 syllabifier.cc）。
+    syllabifier_.SuppressAbbreviation(translator->abbreviation_disabled());
+    syllabifier_.RestrictAbbreviationToInputLength(
+        translator->abbrev_max_length());
     if (corrector) {
       syllabifier_.EnableCorrection(corrector);
     }
@@ -195,6 +200,11 @@ ScriptTranslator::ScriptTranslator(const Ticket& ticket)
       enable_word_completion_ = enable_completion_;
     }
     config->GetInt(name_space_ + "/max_homophones", &max_homophones_);
+    int abbrev_max_length = 0;
+    if (config->GetInt("speller/abbrev_max_length", &abbrev_max_length) &&
+        abbrev_max_length > 0) {
+      abbrev_max_length_ = static_cast<size_t>(abbrev_max_length);
+    }
     poet_.reset(new Poet(language(), config));
   }
   if (enable_correction_) {
@@ -204,6 +214,12 @@ ScriptTranslator::ScriptTranslator(const Ticket& ticket)
   }
 }
 
+bool ScriptTranslator::abbreviation_disabled() const {
+  // 不存在这个选项的方案（例如英文方案）保持原行为：简拼照旧由方案数据决定。
+  return engine_ && engine_->context() &&
+         engine_->context()->get_option("abbrev_disabled");
+}
+
 an<Translation> ScriptTranslator::Query(const string& input,
                                         const Segment& segment) {
   if (!dict_ || !dict_->loaded())
diff --git a/src/rime/gear/script_translator.h b/src/rime/gear/script_translator.h
index e5b60a7a..5ee2dc3a 100644
--- a/src/rime/gear/script_translator.h
+++ b/src/rime/gear/script_translator.h
@@ -52,6 +52,10 @@ class ScriptTranslator : public Translator,
   bool enable_word_completion() const { return enable_word_completion_; }
   int max_word_length() const { return max_word_length_; }
   int core_word_length() const;
+  // 简拼总开关（运行时选项），每次查询现读。
+  bool abbreviation_disabled() const;
+  // 简拼生效的输入码长度上限（0 = 不限）。
+  size_t abbrev_max_length() const { return abbrev_max_length_; }
 
  protected:
   int max_homophones_ = 1;
@@ -61,6 +65,7 @@ class ScriptTranslator : public Translator,
   bool always_show_comments_ = false;
   bool enable_correction_ = false;
   bool enable_word_completion_ = false;
+  size_t abbrev_max_length_ = 0;
   the<Corrector> corrector_;
   the<Poet> poet_;
   vector<an<Phrase>> queue_;
LIBRIME_JIANPIN_PATCH
    # 这条依赖上面的「简拼限流改成逐路径」补丁留下的常量：逐路径补丁没打上
    # （上游自己修好了）就跳过，宁可少一层优化也不要让构建挂掉。
    if grep -q "kMaxAbbreviationPerPath" "$LIBRIME_SRC/rime/dict/table.cc"; then
        git -C "$DEPS_DIR/librime" apply --whitespace=nowarn - <<'LIBRIME_JIANPIN_TABLE_PATCH'
diff --git a/src/rime/dict/table.cc b/src/rime/dict/table.cc
index ad61ebcf..d1e452e6 100644
--- a/src/rime/dict/table.cc
+++ b/src/rime/dict/table.cc
@@ -621,7 +621,11 @@ bool Table::Query(const SyllableGraph& syll_graph,
       SyllableId syll_id = spellings.first;
       for (auto props : spellings.second) {
         if (props->type == kAbbreviation) {
-          if (++abbreviation_iteration_count > kAbbreviationIterationLimit) {
+          // 方案声明「简拼只对短输入生效」时（九键），音节图会带上这个标记：
+          // 那条迭代闸门会把三音节以上的简拼结果截掉，短输入又已经由输入长度
+          // 限定住了规模，所以这里不设上限。
+          if (!syll_graph.unlimited_abbreviation_search &&
+              ++abbreviation_iteration_count > kAbbreviationIterationLimit) {
             continue;
           }
         }
LIBRIME_JIANPIN_TABLE_PATCH
    else
        echo ">>> table.cc 没有逐路径限流（上游已改），跳过简拼迭代上限那一段"
    fi
else
    echo ">>> librime 简拼总开关已是新版（或上游已支持），跳过补丁"
fi

echo ">>> 所有依赖已同步完成。"
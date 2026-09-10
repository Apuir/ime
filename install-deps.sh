#!/bin/bash

# 严格模式：遇到错误立即停止
set -e

# --- 配置区域 ---
DEPS_DIR="app/src/main/cpp/deps"
BOOST_VERSION="1.89.0"
BOOST_DIR="$DEPS_DIR/boost" # 解压到根目录下的 boost 文件夹
BOOST_URL="https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz"
BOOST_HASH="67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74"

# Git 依赖定义
DEPS=(
    "$DEPS_DIR/OpenCC|https://github.com/BYVoid/OpenCC.git|master"
    "$DEPS_DIR/snappy|https://github.com/google/snappy.git|main"
    "$DEPS_DIR/librime|https://github.com/danjian/librime.git|main"
    "$DEPS_DIR/librime-lua|https://github.com/hchunhui/librime-lua.git|master"
    "$DEPS_DIR/librime-lua-deps|https://github.com/hchunhui/librime-lua.git|thirdparty"
    "$DEPS_DIR/librime-octagram|https://github.com/lotem/librime-octagram.git|master"
    "$DEPS_DIR/librime-predict|https://github.com/rime/librime-predict.git|master"
    "$DEPS_DIR/llama.cpp|https://github.com/ggml-org/llama.cpp.git|master"
)

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
# 上游 danjian/librime 的 95d3e11 只加了内部 Schema::kind_，忘了暴露到
# RimeSchemaListItem，而 librime_jni/rime_data.h 直接读 item.kind，
# 不补会导致 native 编译报 “no member named 'kind'”。
# 若上游将来自己加上，这里会自动跳过。
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

echo ">>> 所有依赖已同步完成。"
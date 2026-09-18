#!/usr/bin/env bash
#
# 手写兜底引擎（ochwpro）取模脚本。
#
# 为什么要有这个脚本：模型是 7 MB 的二进制，和 resource.zip 一样**不入库**
# （见 .gitignore），但构建又必须要它。所以把「从哪下、下来之后必须是什么」
# 固定在这里，任何机器上跑一次就能得到同一份文件。
#
# 用法：
#   scripts/fetch-handwriting-model.sh
#
# 依赖：curl、sha256sum。已存在且校验通过时直接跳过（幂等）。

set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$APP_DIR/app/src/main/assets/handwriting"
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/handwriting"

BASE_URL="https://www.modelscope.cn/models/bikeand/ochwpro/resolve/master"

# 这两个值必须与 HandwritingModelStore.kt 里的常量、以及 assets/handwriting/NOTICE.txt 一致。
ONNX_NAME="ochwpro.onnx"
ONNX_SHA256="eb04d62a314c7d7bac4e34d6ce0c24137474d30ff94b929115a621385420ce13"
ONNX_SIZE=7001270

INDEX_NAME="char_index.json"
INDEX_SHA256="171cf2ac23731428ac9dd28f64be82dece5f66a0ca3d674ffb3b567b51532176"
INDEX_SIZE=51317

mkdir -p "$DEST_DIR"

verify() {
  local file="$1" want_sha="$2" want_size="$3" label="$4"
  local got_sha got_size
  got_size="$(stat -c %s "$file")"
  if [ "$got_size" != "$want_size" ]; then
    echo "✗ $label 大小不对：期望 $want_size，实际 $got_size（$file）" >&2
    return 1
  fi
  got_sha="$(sha256sum "$file" | cut -d' ' -f1)"
  if [ "$got_sha" != "$want_sha" ]; then
    echo "✗ $label sha256 不对：期望 $want_sha，实际 $got_sha（$file）" >&2
    return 1
  fi
  echo "✓ $label 校验通过（$got_size 字节）"
}

install_one() {
  local name="$1" sha="$2" size="$3"
  local dest="$DEST_DIR/$name"

  # 目标已就绪：直接过
  if [ -f "$dest" ] && verify "$dest" "$sha" "$size" "$name（已在 assets）" 2>/dev/null; then
    return 0
  fi

  # 本机缓存：优先复用，避免重复下载
  local cached="$CACHE_DIR/$name"
  if [ -f "$cached" ] && verify "$cached" "$sha" "$size" "$name（本机缓存）"; then
    cp -f "$cached" "$dest"
    echo "  已从缓存复制到 $dest"
    return 0
  fi

  echo "→ 下载 $name（$size 字节）"
  local tmp="$dest.tmp"
  curl -fsSL --retry 3 --retry-delay 2 -o "$tmp" "$BASE_URL/$name"
  verify "$tmp" "$sha" "$size" "$name（下载后）"
  mv -f "$tmp" "$dest"
  echo "  已写入 $dest"
}

echo "手写模型目标目录：$DEST_DIR"
install_one "$ONNX_NAME" "$ONNX_SHA256" "$ONNX_SIZE"
install_one "$INDEX_NAME" "$INDEX_SHA256" "$INDEX_SIZE"
echo "完成。模型与 char_index 均已就位，可以构建了。"

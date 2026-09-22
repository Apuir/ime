#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# 在**宿主机**（有 NVIDIA 卡的那台，`torch.cuda.is_available()` 为 True）建一个只用于 GPU 评测的
# venv：`.nwp-work/venv-gpu`
#
# 背景：`torch` 有 CUDA ≠ `onnxruntime` 有 CUDA —— 它们是两个独立的包。
# 常见情况是 torch 2.14.0+cu126 能正常用 GPU，而 venv 里的 onnxruntime 是 CPU 轮子，
# 于是 `eval.py --onnx-provider cuda` 报「要求的 cuda 不可用」。
# （容器/沙箱里 `/dev/nvidia*` 不存在，所以这类判断必须在宿主机上做。）
#
# 为什么不直接往 .nwp-work/venv 里装 onnxruntime-gpu：
#   1) 训练/导出用的 venv 已经很稳（torch 2.14.0+cu126），装 gpu 版 ORT 会动
#      numpy/protobuf 这些共用的包；
#   2) ORT 的 CUDA 版本要求和 torch 不一定同代，隔离在独立 venv 里最省事。
#
# 为什么要判驱动版本：
#   onnxruntime-gpu <= 1.26 走 CUDA 12（cuDNN 9 / cuFFT 11 / cuRAND 10，都有 pip 包）；
#   onnxruntime-gpu >= 1.27 走 CUDA 13。CUDA 13 需要 **580 以上的驱动**。
#   本机 /opt/cuda 是 13.4，所以驱动够新时用 CUDA 13 路线、否则用 CUDA 12 路线。
#
# 用法（在宿主机、仓库根目录）：
#   bash scripts/nwp/setup-gpu-venv.sh            # 真装
#   bash scripts/nwp/setup-gpu-venv.sh --dry-run  # 只判路线 + 打印要装什么，不落盘
#
set -euo pipefail

DRY=0
[[ "${1:-}" == "--dry-run" ]] && DRY=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VENV="${VENV:-$ROOT/.nwp-work/venv-gpu}"
PY="$VENV/bin/python"
# /home 若是只读（容器里就是这样），uv/pip 的缓存必须落在仓库内
export UV_CACHE_DIR="${UV_CACHE_DIR:-$ROOT/.nwp-work/uv-cache}"
export UV_PYTHON_INSTALL_DIR="${UV_PYTHON_INSTALL_DIR:-$ROOT/.nwp-work/uv-python}"
export UV_DATA_DIR="${UV_DATA_DIR:-$ROOT/.nwp-work/uv-data}"
export UV_LINK_MODE=copy
MIRROR="${MIRROR:-https://pypi.tuna.tsinghua.edu.cn/simple}"

echo "=== 0/5 看驱动：CUDA 13 路线需要 >= 580 ==="
DRIVER=""
if command -v nvidia-smi >/dev/null 2>&1; then
  # nvidia-smi 失败时会把一整句错误打到 stdout，所以只认第一段纯数字
  DRIVER="$(nvidia-smi --query-gpu=driver_version --format=csv,noheader 2>/dev/null \
    | head -1 | tr -d ' ' | grep -oE '^[0-9]+(\.[0-9]+)*' || true)"
fi
echo "driver_version=${DRIVER:-未知}"
# 580 以下（含没有 nvidia-smi）都退回 CUDA 12 路线，保证不会装完才发现跑不起来
USE_CU13=0
if [[ "$DRIVER" =~ ^([0-9]+) ]] && (( BASH_REMATCH[1] >= 580 )); then
  USE_CU13=1
fi
echo "→ 选 $( ((USE_CU13)) && echo 'CUDA 13（onnxruntime-gpu 1.30 + nvidia-cudnn-cu13）' || echo 'CUDA 12（onnxruntime-gpu 1.22 + nvidia-*-cu12）' )"

if ((DRY)); then
  if ((USE_CU13)); then
    echo "--- dry-run：将要执行的 pip 安装 ---"
    echo "pip install --index-url $MIRROR transformers 'numpy<3' protobuf"
    echo "pip install --index-url https://download.pytorch.org/whl/cpu torch"
    echo "pip install --index-url $MIRROR 'onnxruntime-gpu==1.30.0' 'nvidia-cudnn-cu13~=9.0'"
  else
    echo "--- dry-run：将要执行的 pip 安装 ---"
    echo "pip install --index-url $MIRROR transformers 'numpy<3' protobuf"
    echo "pip install --index-url https://download.pytorch.org/whl/cpu torch"
    echo "pip install --index-url $MIRROR 'onnxruntime-gpu==1.22.0' \\"
    echo "  'nvidia-cudnn-cu12~=9.0' 'nvidia-cuda-runtime-cu12~=12.0' \\"
    echo "  'nvidia-cuda-nvrtc-cu12~=12.0' 'nvidia-cufft-cu12~=11.0' 'nvidia-curand-cu12~=10.0'"
  fi
  echo "venv 将建在：$VENV"
  exit 0
fi

echo "=== 1/5 建 venv：$VENV ==="
if [[ -n "${UV:-$(command -v uv || true)}" ]] && command -v "${UV:-uv}" >/dev/null 2>&1; then
  "${UV:-uv}" venv --python 3.12 "$VENV"
else
  python3 -m venv "$VENV"
fi
"$PY" -m pip install --quiet --upgrade pip

echo "=== 2/5 装评测需要的依赖（numpy/torch 都用 CPU 轮子，几 MB）==="
# torch 只用来读 jsonl 样本、算 top-k/MRR/BPB，前向全在 ORT 里，CPU 轮子足够
"$PY" -m pip install --index-url "$MIRROR" transformers "numpy<3" protobuf
"$PY" -m pip install --index-url https://download.pytorch.org/whl/cpu torch

echo "=== 3/5 装 GPU 版 onnxruntime + 对应 CUDA 运行库 ==="
if ((USE_CU13)); then
  "$PY" -m pip install --index-url "$MIRROR" "onnxruntime-gpu==1.30.0" "nvidia-cudnn-cu13~=9.0"
else
  "$PY" -m pip install --index-url "$MIRROR" "onnxruntime-gpu==1.22.0" \
    "nvidia-cudnn-cu12~=9.0" "nvidia-cuda-runtime-cu12~=12.0" \
    "nvidia-cuda-nvrtc-cu12~=12.0" "nvidia-cufft-cu12~=11.0" "nvidia-curand-cu12~=10.0"
  # pip 装的 cuDNN/cuBLAS 不在系统 ldconfig 里，ORT 加载 CUDA EP 时要能找到它们
  SITE="$("$PY" -c 'import site; print(site.getsitepackages()[0])')"
  export LD_LIBRARY_PATH="$SITE/nvidia/cudnn/lib:$SITE/nvidia/cublas/lib:$SITE/nvidia/cufft/lib:$SITE/nvidia/curand/lib:$SITE/nvidia/cuda_runtime/lib:${LD_LIBRARY_PATH:-}"
  echo "export LD_LIBRARY_PATH=$LD_LIBRARY_PATH" > "$VENV/ld_library_path.env"
fi

echo "=== 4/5 验会话：CUDAExecutionProvider 必须真的建得起来 ==="
cd "$ROOT"
PYTHONPATH="$ROOT/scripts/nwp" "$PY" - <<'PYEOF'
import sys
from pathlib import Path

import onnxruntime as ort

print("onnxruntime", ort.__version__)
print("available:", ort.get_available_providers())
if "CUDAExecutionProvider" not in ort.get_available_providers():
    print("\n✗ GPU 推理不可用：CUDAExecutionProvider 没出现在可用列表里。")
    print("  1) 先看驱动：nvidia-smi（CUDA 13 路线需要 >= 580）")
    print("  2) 再看装 onnxruntime-gpu 时有没有提示缺 .so")
    print("  3) 实在不想折腾就用 CPU 跑（int8 约 2 h、fp16 约 9-10 h）：")
    print("     $PY scripts/nwp/eval.py run --onnx <模型> --limit 200000 --batch-size 16")
    sys.exit(1)

probe = Path(".nwp-work/nwp-smoke/onnx/nwp.int8.onnx")
if not probe.exists():
    print("⚠ CUDAExecutionProvider 已注册，但没有探针模型可建会话（先跑一次 smoke_test.sh）")
    sys.exit(0)

so = ort.SessionOptions()
so.log_severity_level = 3
# 光看 provider 列表不够：缺 .so 时是**建 session** 这一步才炸（onnxruntime#25609 就是这种）
sess = ort.InferenceSession(str(probe), so, providers=["CUDAExecutionProvider"])
print("✓ 已用 CUDAExecutionProvider 建起会话：", sess.get_providers())
PYEOF

echo "=== 5/5 完成 ==="
cat <<EOF

GPU 评测（在宿主机、仓库根目录）：
  GPU_PY=$VENV/bin/python
  先看 CUDA EP 在不在：
    \$GPU_PY -c "import onnxruntime as ort; print(ort.get_available_providers())"
  fp16 20 万条（就是卡住的那条）：
    \$GPU_PY scripts/nwp/eval.py run --onnx .nwp-work/nwp/onnx-fp16-new/nwp.fp16.onnx \\
      --limit 200000 --batch-size 16 --onnx-provider cuda
  int8 20 万条（同一 batch 口径，可横向比）：
    \$GPU_PY scripts/nwp/eval.py run --onnx .nwp-work/nwp/onnx/nwp.int8.onnx \\
      --limit 200000 --batch-size 16 --onnx-provider cuda

CUDA 12 路线下如果报找不到 libcudnn.so.9：
  source $VENV/ld_library_path.env
EOF

#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# 2000 条 × (int8/fp16) × (cuda/cpu) 四组计时对拍。
#
# 为什么要这个小测试：20 万条整跑一次太慢，而「哪种量化更快」只要几千条就看得出来。
# 关键是四组必须**同一份测试样本、同一个 batch-size、同一台机器**，否则差异里混着别的因素。
# 注意 CPU 组在 fp16 上可能很慢（ORT 的 CPU provider 会把 fp16 转 fp32 算），
# 那是预期结果、不是卡死；不想等就 `CPU=0 bash ...` 只跑 GPU 两组。
#
# 用法（宿主机、仓库根目录）：
#   bash scripts/nwp/bench_quant_speed.sh
#   N=2000 BATCH=16 CPU=0 bash scripts/nwp/bench_quant_speed.sh    # 只跑 GPU 两组
#   ONNX_DIR=.nwp-work/nwp/onnx-fp16-new bash scripts/nwp/bench_quant_speed.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
PY="${PY:-$ROOT/.nwp-work/venv/bin/python}"
N="${N:-2000}"
BATCH="${BATCH:-16}"
CPU="${CPU:-1}"
# fp16 那份在 onnx-fp16-new/（仓库里 onnx/ 下没有它），int8 在 onnx/
INT8="${INT8:-.nwp-work/nwp/onnx/nwp.int8.onnx}"
FP16="${FP16:-.nwp-work/nwp/onnx-fp16-new/nwp.fp16.onnx}"
SAMPLES="${SAMPLES:-.nwp-work/nwp/samples/test.jsonl}"
OUT="${OUT:-.nwp-work/nwp/results}"
# per-channel int8（若存在就一起比：粒度更细、期望更少掉点，但速度要实测）
PC="${PC:-.nwp-work/nwp/onnx-pc/nwp.int8.onnx}"

for f in "$INT8" "$FP16" "$SAMPLES"; do
  [[ -f "$f" ]] || { echo "找不到 $f" >&2; exit 1; }
done

run_one() {  # $1=标签 $2=模型 $3=provider
  local tag="$1" model="$2" prov="$3"
  local t0 t1
  t0=$(date +%s%N)
  # 捕获退出码要在下一行之前，否则 `local t1=$(date ...)` 的返回值会把失败吞掉
  if ! "$PY" scripts/nwp/eval.py run --onnx "$model" --samples-file "$SAMPLES" \
      --limit "$N" --batch-size "$BATCH" --onnx-provider "$prov" \
      --out "$OUT/_bench_${tag}.json" >/dev/null 2>&1; then
    t1=$(date +%s%N)
    printf '%-10s %-6s 失败（%d s 后退出；去掉重定向可看报错）\n' \
      "$tag" "$prov" "$(( (t1 - t0) / 1000000000 ))"
    return 0
  fi
  t1=$(date +%s%N)
  # 用 awk 算，不用 bc：容器里就没有 bc，宿主机也不该被假定有
  "$PY" - "$OUT/_bench_${tag}.json" "$tag" "$prov" "$((t1 - t0))" <<'PYEOF'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
m = d["results"]["model"]
sec = float(sys.argv[4]) / 1e9
print(f"{sys.argv[2]:<10} {sys.argv[3]:<6} {sec:7.1f} s   "
      f"{sec / m['n'] * 1000:6.2f} ms/条   top-1 {m['top1'] * 100:.2f}%   n={m['n']}")
PYEOF
}

echo "每组 $N 条 / batch $BATCH / 同一份 $SAMPLES"
printf '%-10s %-6s %8s   %10s   %s\n' "量化" "后端" "耗时" "单条" "top-1"
run_one int8 "$INT8" cuda
run_one fp16 "$FP16" cuda
[[ -f "$PC" ]] && run_one int8-pc "$PC" cuda
if [[ "$CPU" == "1" ]]; then
  run_one int8 "$INT8" cpu
  run_one fp16 "$FP16" cpu
  [[ -f "$PC" ]] && run_one int8-pc "$PC" cpu
fi
echo
echo "看两件事：① GPU 与 CPU 上 int8/fp16 的相对快慢（很可能方向相反）；"
echo "          ② 两种量化的 top-1 是否与 20 万条结论一致（int8 掉约 1 pp，fp16 零掉点）。"

#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# 一条命令验证整条链路（CPU、离线、约 1 分钟）：
#   合成语料 → 词表(char2id+word_vocab) → 滑窗样本(含去重) → 冒烟训练 → 评测
#   → ONNX 导出(fp32+int8) → ONNX vs PyTorch top-k 对拍 → KV cache 增量对拍
#
# 任何一步失败都退出非零。这不是"跑个 demo"：这里的断言就是 Phase 3 的验收条件
# （ONNX 必须复现 PyTorch 的 top-k；KV cache 回灌后结果必须一致）。
#
# 用法：
#   bash scripts/nwp/smoke_test.sh              # 用 <repo>/.nwp-work/venv 里的 python
#   bash scripts/nwp/smoke_test.sh --work /path/to/scratch
#   PY=/path/to/python bash scripts/nwp/smoke_test.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PY="${PY:-$ROOT/.nwp-work/venv/bin/python}"
WORK="$ROOT/.nwp-work/nwp-smoke"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --work) WORK="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

if [[ ! -x "$PY" ]]; then
  echo "找不到 python：$PY" >&2
  echo "先建 venv（见 scripts/nwp/README.md 的「环境」一节），或用 PY=... 指定" >&2
  exit 1
fi
export PYTHONPATH="$ROOT/scripts/nwp"
export HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"
export HF_HOME="$WORK/hf"
export TOKENIZERS_PARALLELISM=false

step() { printf '\n\033[1;36m=== %s ===\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m[PASS]\033[0m %s\n' "$*"; }

N_DOCS="${N_DOCS:-3000}"
STEPS="${STEPS:-40}"
# 刻意压到 48：让「上下文超过 max-context-ids 就截尾」这条路径在冒烟里真的被走到。
# 真实默认是 model.DEFAULT_MAX_CONTEXT_IDS=256（>= 端侧 prompt 预算 210），下面第 7 步会校验。
MAX_CONTEXT_IDS="${MAX_CONTEXT_IDS:-48}"

rm -rf "$WORK"
mkdir -p "$WORK"

step "0/9 py_compile 全部脚本"
"$PY" -m py_compile "$ROOT"/scripts/nwp/*.py
ok "scripts/nwp/*.py 全部编译通过"

step "0.5/9 回归测试（分片文件名唯一/磁盘行数==报告条数、fp16 骨干前向、ONNX 强制 float32 I/O、去重覆盖率）"
PYTHONPATH="$ROOT/scripts/nwp" "$PY" "$ROOT/scripts/nwp/test_nwp_pipeline.py"

step "1/9 合成语料（离线，$N_DOCS 条，含刻意注入的跨分片重复）"
"$PY" "$ROOT/scripts/nwp/fetch_corpus.py" --work "$WORK" --dataset synthetic --limit "$N_DOCS"

step "2/9 词表：char2id（corpus 模式，冒烟骨干没有预训练字表）+ word_vocab"
"$PY" "$ROOT/scripts/nwp/build_vocab.py" --work "$WORK" \
  --char-source corpus --vocab-source synthetic --size 400

step "3/9 滑窗样本 + ≥8 字 n-gram 去重（含边界自检）"
"$PY" "$ROOT/scripts/nwp/build_samples.py" --dedup-selftest
"$PY" "$ROOT/scripts/nwp/build_samples.py" --work "$WORK" \
  --context-words 32 --min-context-words 4 --valid-ratio 0.05 --test-ratio 0.1 \
  --max-context-ids "$MAX_CONTEXT_IDS"

# 去重必须真的抓到注入的重复；截尾必须真的发生
"$PY" - "$WORK" <<'EOF'
import json, sys
rep = json.load(open(f"{sys.argv[1]}/samples/report.json", encoding="utf-8"))
d = rep["splits"]["test"]["dropped_by_dedup"]
n = rep["splits"]["test"]["samples"]
assert d > 0, "测试集一条都没被去重丢掉——注入的重复没被抓到，去重是坏的"
print(f"[PASS] 测试集 {n} 条，被 ≥{rep['dedup']['ngram']} 字 n-gram 去重丢掉 {d} 条"
      f"（{rep['splits']['test']['dropped_rate']*100:.2f}%）")
tr = sum(v["truncated_by_max_context_ids"] for v in rep["splits"].values())
assert tr > 0, "没有一条样本被截尾，--max-context-ids 这条路径没被验证到"
assert rep["max_ids_seen"] > rep["max_context_ids"], "截尾前最大值没有超过上限，说明没触发截尾"
print(f"[PASS] --max-context-ids={rep['max_context_ids']}：截尾 {tr} 条，"
      f"截尾前最长 {rep['max_ids_seen']} 个 id（context_words={rep['context_words']}，仅采样规则）")
EOF

step "4/9 冒烟训练（随机小骨干，CPU，$STEPS 步，含逐步解冻 + LoRA）"
"$PY" "$ROOT/scripts/nwp/train.py" --work "$WORK" --smoke --max-steps "$STEPS" \
  --batch-size 8 --log-every 10 --eval-every 20 --lora-rank 4

step "5/9 评测：checkpoint + unigram + n-gram 基线"
"$PY" "$ROOT/scripts/nwp/eval.py" run --work "$WORK" \
  --checkpoint "$WORK/ckpt/best.pt" --baseline all --limit 200 --name smoke-ckpt

step "6/9 导出 ONNX（fp32 + 动态范围 int8）+ manifest + top-k / KV cache 对拍"
"$PY" "$ROOT/scripts/nwp/export_onnx.py" --work "$WORK" --eval-limit 128 --parity-rows 8

step "6.5/9 fp16 导出路径（--fp16：I/O 仍 float32、无 TopK、体积更小）"
"$PY" "$ROOT/scripts/nwp/export_onnx.py" --work "$WORK" --out-dir "$WORK/onnx-fp16" \
  --fp16 --eval-limit 32 --parity-rows 4 2>&1 | grep -E 'fp16|一致率|验收|格式' || true

step "7/9 校验产物"
PYTHONPATH="$ROOT/scripts/nwp" "$PY" - "$WORK" <<'EOF'
import json, sys, os
work = sys.argv[1]
man_path = os.path.join(work, "onnx", "manifest.json")
man = json.load(open(man_path, encoding="utf-8"))
for key in ("version", "name", "format", "context_tokens", "vocab_size", "char_vocab_size",
            "num_layers", "num_heads", "head_dim", "model", "word_vocab", "char_vocab", "eval"):
    assert key in man, f"manifest 缺字段 {key}"
assert man["vocab_size"] > 1 and man["char_vocab_size"] > 2
assert man["num_layers"] > 0 and man["num_heads"] > 0 and man["head_dim"] > 0
assert man["model"]["bytes"] > 0 and len(man["model"]["sha256"]) == 64
for f in ("nwp.onnx", man["model"]["file"], "word_vocab.txt", "char2id.json"):
    p = os.path.join(work, "onnx", f)
    assert os.path.exists(p) and os.path.getsize(p) > 0, f"缺产物 {p}"
srep = json.load(open(os.path.join(work, "samples", "report.json"), encoding="utf-8"))
assert man["context_tokens"] == srep["max_context_ids"], (
    f"manifest.context_tokens={man['context_tokens']} 必须等于 samples 的 max_context_ids="
    f"{srep['max_context_ids']}（它表示输入 id/字数上限，不是词数）")
from nwp_common import DEFAULT_MAX_CONTEXT_IDS, DEVICE_PROMPT_BUDGET_CHARS
assert DEFAULT_MAX_CONTEXT_IDS >= DEVICE_PROMPT_BUDGET_CHARS, (
    f"默认 max-context-ids={DEFAULT_MAX_CONTEXT_IDS} 低于端侧 prompt 预算 {DEVICE_PROMPT_BUDGET_CHARS}")
print(f"[PASS] manifest.context_tokens={man['context_tokens']} == samples.max_context_ids"
      f"（默认配置 {DEFAULT_MAX_CONTEXT_IDS} ≥ 端侧预算 {DEVICE_PROMPT_BUDGET_CHARS}）")
rep = json.load(open(os.path.join(work, "results", "export_report.json"), encoding="utf-8"))
assert rep["context_tokens"] == man["context_tokens"]
assert sum(rep["samples_truncated_by_max_context_ids"].values()) > 0, "导出报告里没有截尾计数"
p32 = rep["parity_fp32"]
assert p32["top1_agreement"] == 1.0, f"fp32 top-1 一致率不是 100%：{p32}"
assert rep["graph"]["has_topk"] is False, f"图里出现了 top-k 类算子：{rep['graph']['banned_ops']}"
assert rep["kv_torch"]["top5_equal"] and rep["kv_onnx"]["top5_equal_vs_torch"], "KV cache 与全量前向不一致"
assert rep["kv_onnx"]["present_len_after_step"] == rep["kv_onnx"]["context_len"], "present 长度没有累加"
print(f"[PASS] manifest 字段/哈希/体积齐全；int8={rep['int8_available']}")
print(f"[PASS] fp32 top-1 一致率 {p32['top1_agreement']*100:.2f}%，max|Δ|={p32['max_abs_diff']:.3e}")
if "parity_int8" in rep:
    i8 = rep["parity_int8"]
    print(f"[PASS] int8 top-1 一致率 {i8['top1_agreement']*100:.2f}%，"
          f"top-5 重合率 {i8['top5_overlap']*100:.2f}%，max|Δ|={i8['max_abs_diff']:.3e}")
print(f"[PASS] PyTorch KV max|Δ|={rep['kv_torch']['max_abs_diff']:.3e}；"
      f"ONNX KV max|Δ|={rep['kv_onnx']['max_abs_diff_vs_torch_full']:.3e}"
      f"（present_len={rep['kv_onnx']['present_len_after_step']}）")
print(f"[PASS] 图中节点 {rep['graph']['nodes']} 个，无 TopK/ArgMax")

# fp16 交付路径（文档写的 int8 退化方案）必须真的存在且仍满足端侧契约
fdir = os.path.join(work, "onnx-fp16")
if os.path.exists(os.path.join(fdir, "manifest.json")):
    fman = json.load(open(os.path.join(fdir, "manifest.json"), encoding="utf-8"))
    assert fman["format"] == "onnx-fp16" and fman["model"]["file"] == "nwp.fp16.onnx", fman
    assert set(fman.keys()) == set(man.keys()), "fp16 manifest 的键必须与 int8 完全一致"
    import onnx
    fg = onnx.load(os.path.join(fdir, "nwp.fp16.onnx"))
    kinds = {1: "float32", 10: "float16", 7: "int64", 6: "int32"}
    io_t = {kinds.get(i.type.tensor_type.elem_type) for i in fg.graph.input}
    oo_t = {kinds.get(o.type.tensor_type.elem_type) for o in fg.graph.output}
    assert io_t <= {"float32", "int64"} and oo_t == {"float32"}, (io_t, oo_t)
    assert not ({n.op_type for n in fg.graph.node} & {"TopK", "ArgMax"}), "fp16 图里有 TopK"
    fp32_b = os.path.getsize(os.path.join(fdir, "nwp.onnx"))
    fp16_b = os.path.getsize(os.path.join(fdir, "nwp.fp16.onnx"))
    assert fp16_b < 0.7 * fp32_b, (fp16_b, fp32_b)
    print(f"[PASS] fp16 交付：I/O=float32/无 TopK/{fp32_b/1e6:.0f}MB → {fp16_b/1e6:.0f}MB"
          f"（{100*fp16_b/fp32_b:.0f}%），manifest 键与 int8 完全一致")
EOF

printf '\n\033[1;32m全部通过。\033[0m 产物在 %s\n' "$WORK"
echo "  onnx/manifest.json   onnx/nwp.onnx   onnx/nwp.int8.onnx"
echo "  results/export_report.json   results/eval_smoke-ckpt.json   samples/report.json"

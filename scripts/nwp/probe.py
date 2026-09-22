#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Phase 1 质量探针：两个底座各微调一版，同数据、同步数、同评测集，出一张对比表。

为什么必须先做这一步（实施 prompt §4 Phase 1）：底座选错了，后面几周的正式微调、
导出、端侧接入全都要重来。探针的成本只有几小时，所以「先探针、再正式训练」。

同时**必须把两个基线一起跑**：一个打不过词频（unigram）基线的神经模型，
说明数据/标签/评测里有问题，而不是「模型选得不好」。

产出：
- `<work>/probe/<backbone>/`：各自的 checkpoint 与评测
- `<work>/probe/report.md`：对比表 + 明确结论「用哪个底座、为什么」
- `<work>/probe/report.json`

`--smoke` 用两个随机小骨干跑通整条流程（CPU 几十秒），结论会标注「仅验证流程」。
真实探针（两个 110M 底座）命令见 README。
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from nwp_common import (  # noqa: E402
    BACKBONES,
    add_work_arg,
    layout_from_args,
    log,
    markdown_table,
    prepare_hf_env,
    read_json,
    write_json,
)

HERE = Path(__file__).resolve().parent
CANDIDATES = ["cluecorpus", "wenzhong"]


def run(cmd: list[str], log_path: Path) -> int:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log(f"$ {' '.join(str(c) for c in cmd)}")
    with log_path.open("w", encoding="utf-8") as fh:
        proc = subprocess.run([str(c) for c in cmd], stdout=fh, stderr=subprocess.STDOUT, text=True)
    return proc.returncode


def main() -> int:
    ap = argparse.ArgumentParser(description="Phase 1 质量探针：两个底座对比")
    ap.add_argument("--backbones", default=",".join(CANDIDATES), help="逗号分隔的底座短名")
    ap.add_argument("--smoke", action="store_true", help="用两个随机小骨干验证流程（结论不作数）")
    ap.add_argument("--steps", type=int, default=3000, help="每个底座的训练步数（必须一致）")
    ap.add_argument("--batch-size", type=int, default=8)
    ap.add_argument("--grad-accum", type=int, default=8)
    ap.add_argument("--lora-rank", type=int, default=16)
    ap.add_argument("--limit", type=int, default=2000, help="评测/基线用的样本数")
    ap.add_argument("--python", default=sys.executable)
    add_work_arg(ap)
    args = ap.parse_args()

    lay = layout_from_args(args)
    prepare_hf_env(lay.root)  # 子进程会继承，保证 train.py/eval.py 也走镜像与同一份缓存
    probe_dir = lay.root / "probe"
    probe_dir.mkdir(parents=True, exist_ok=True)
    names = ["smoke", "smoke-b"] if args.smoke else [b.strip() for b in args.backbones.split(",") if b.strip()]
    steps = 40 if args.smoke else args.steps

    runs: dict[str, dict] = {}
    for name in names:
        out_dir = probe_dir / name
        log(f"=== 探针：{name} ===")
        train_cmd = [args.python, HERE / "train.py", "--work", lay.root, "--backbone", name,
                     "--max-steps", steps, "--batch-size", args.batch_size,
                     "--grad-accum", args.grad_accum, "--lora-rank", args.lora_rank,
                     "--out", out_dir / "ckpt",
                     "--eval-limit", min(512, args.limit)]
        if args.smoke:
            train_cmd += ["--smoke", "--grad-accum", "1"]
        rc = run(train_cmd, probe_dir / f"{name}-train.log")
        if rc != 0:
            log(f"⚠ {name} 训练失败（见 {probe_dir / (name + '-train.log')}）")
            runs[name] = {"error": f"train exit {rc}"}
            continue
        eval_cmd = [args.python, HERE / "eval.py", "run", "--work", lay.root,
                    "--checkpoint", out_dir / "ckpt" / "best.pt", "--baseline", "all",
                    "--limit", args.limit, "--name", name,
                    "--out", out_dir / "eval.json", "--md", out_dir / "eval.md"]
        rc = run(eval_cmd, probe_dir / f"{name}-eval.log")
        if rc != 0:
            runs[name] = {"error": f"eval exit {rc}"}
            continue
        data = read_json(out_dir / "eval.json")
        train_report = read_json(lay.results / f"train_{name}{'_smoke' if args.smoke else ''}.json")
        runs[name] = {
            "backbone": name,
            "hf_id": BACKBONES.get(name, {}).get("hf_id", name),
            "params": BACKBONES.get(name, {}).get("params", "?"),
            "steps": steps,
            "sec_per_step": train_report.get("sec_per_step"),
            "valid_top1": train_report.get("final_valid", {}).get("top1"),
            "eval": data["results"],
        }

    rows = []
    for name, r in runs.items():
        if "error" in r:
            rows.append([name, r["error"], "-", "-", "-", "-"])
            continue
        model = r["eval"].get("model", {})
        uni = r["eval"].get("unigram", {})
        ng = r["eval"].get("ngram", {})
        rows.append([
            name, f"{model.get('top1', 0) * 100:.2f}", f"{model.get('top5', 0) * 100:.2f}",
            f"{model.get('mrr', 0):.4f}",
            f"{uni.get('top1', 0) * 100:.2f}",
            f"{ng.get('top1', 0) * 100:.2f}" if ng.get("available", True) else "不可用",
        ])
    table = markdown_table(["底座", "top-1", "top-5", "MRR", "unigram top-1", "n-gram top-1"], rows)

    # 结论：按 top-1 取优，并显式对照基线
    ok = {k: v for k, v in runs.items() if "error" not in v}
    conclusion_lines = []
    if len(ok) >= 2:
        best = max(ok.items(), key=lambda kv: kv[1]["eval"].get("model", {}).get("top1", 0))
        worst = min(ok.items(), key=lambda kv: kv[1]["eval"].get("model", {}).get("top1", 0))
        delta = (best[1]["eval"]["model"]["top1"] - worst[1]["eval"]["model"]["top1"]) * 100
        if delta > 0.5:
            conclusion_lines.append(
                f"**用哪个底座**：`{best[0]}`（{best[1]['hf_id']}）。"
                f"在完全相同的 {steps} 步、相同数据与评测集上，它的 top-1 比 `{worst[0]}` 高 {delta:.2f} 个百分点。"
            )
        else:
            conclusion_lines.append(
                f"**用哪个底座**：两者持平（top-1 只差 {delta:.2f} 个百分点，落在噪声里）。"
                "这时按**端侧成本**选：体积/int8 掉点、许可证、tokenizer 的合并率（合并越多，"
                "字级输入丢的信息越多，见 build_vocab 的 `merge_rate`）。"
            )
        uni1 = best[1]["eval"].get("unigram", {}).get("top1", 0)
        if best[1]["eval"]["model"]["top1"] <= uni1:
            conclusion_lines.append(
                f"⚠ **早期告警**：最好的神经模型 top-1 仍不高于 unigram 基线（{uni1 * 100:.2f}%）。"
                "这说明数据/标签/评测口径有问题，先别加大训练量。"
            )
        else:
            conclusion_lines.append(
                f"神经模型 top-1 已高于 unigram 基线（{uni1 * 100:.2f}%），方向正确。"
            )
        if args.smoke:
            conclusion_lines.append(
                "> 本次是 `--smoke`：两个底座都是随机初始化的小模型，**上表数字只证明流程可跑通**，"
                "选型结论必须以真实底座（110M）的探针为准。"
            )
    else:
        conclusion_lines.append("可用结果不足两个，无法给出选型结论——请检查上面的错误日志。")

    md = ["# Phase 1 质量探针报告", "", f"- 步数：{steps}（两个底座完全一致）", "",
          "- 指标：top-1 / top-5 / MRR（跨分词器不比 PPL）", "", table, "",
          "## 结论", ""] + conclusion_lines + [""]
    (probe_dir / "report.md").write_text("\n".join(md), encoding="utf-8")
    write_json(probe_dir / "report.json", {"runs": runs, "steps": steps, "smoke": args.smoke})
    print("\n".join(md))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

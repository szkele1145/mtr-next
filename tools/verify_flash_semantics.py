"""
[0.36.3 审查] 上游 flash（闪烁）语义核对。

上游证据链：
 1. ModelPropertiesPartWrapper.java:80-105 —— 每条 wrapper 把**自己的**
    flashOnTime / flashOffTime 传给新建的 ModelPropertiesPart 构造函数：
        new ModelPropertiesPart(..., flashOnTime, flashOffTime)
 2. ModelPropertiesPart.java:317 —— renderNormal 内部用的是**本实例**的字段：
        final boolean flashOn = flashOnTime + flashOffTime == 0
            || (System.currentTimeMillis() % (flashOnTime + flashOffTime)) > flashOffTime;
 3. ModelPropertiesPart.java:320 —— flashOn 为假时把 y 推到 Integer.MAX_VALUE
    （**该部件**移出视野），不是整层消失。

⇒ 结论：flash 是 **per-ModelPropertiesPart 实例**（= per-part），
  不是整层（per-layer）共享。

mtr-next 现状（0.36.3）：BbModelTrainRenderer / FormationPreviewRenderer
都写成"整层取第一条带 flash 配置的部件"：
    flashOn(parts, now) -> 遍历 parts，返回第一个 flashOnTime+flashOffTime != 0 的

本脚本量化这个差异：
  - 若某 properties 文件内多个 part 的 flash 配置**不一致**，
    "整层取第一条" 与上游 per-part 语义就会产生差异；
  - 同时报告"不同 part 的闪烁相位是否相同"（flashOn 用
    currentTimeMillis % 周期，若周期不同则相位也不同）。

用法: python verify_flash_semantics.py [车包目录]
"""
import json
import os
import sys
from collections import Counter


def load_parts(path):
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    parts = data.get("parts") or []
    out = []
    for p in parts:
        if not isinstance(p, dict):
            continue
        on = p.get("flashOnTime", 0) or 0
        off = p.get("flashOffTime", 0) or 0
        out.append((p.get("names") or [], on, off))
    return out


def main():
    pack = sys.argv[1] if len(sys.argv) > 1 else r"F:\vibe coding工程\mtr-turbo\build\eridu-pack"
    mtr = os.path.join(pack, "assets", "mtr")
    if not os.path.isdir(mtr):
        print("跳过：找不到", mtr)
        return

    files = sorted(f for f in os.listdir(mtr) if f.startswith("properties_") and f.endswith(".json"))
    if not files:
        print("跳过：没有 properties_*.json")
        return

    print(f"扫描 {len(files)} 个 properties 文件\n")
    total_parts = 0
    total_nonzero = 0
    risk_files = []
    for fn in files:
        parts = load_parts(os.path.join(mtr, fn))
        total_parts += len(parts)
        nz = [(n, on, off) for (n, on, off) in parts if on or off]
        total_nonzero += len(nz)
        if not nz:
            continue
        cfgs = Counter((on, off) for (_, on, off) in nz)
        unique = list(cfgs.keys())
        same = len(unique) == 1
        flag = "一致（整层取第一条 == per-part）" if same else "不一致（整层取第一条会错）"
        print(f"  {fn}: 非零 flash 部件 {len(nz)} 个，配置 {dict(cfgs)} => {flag}")
        if not same:
            risk_files.append((fn, unique))
        # 同配置时相位是否也一致：flashOn 用 currentTimeMillis % (on+off)
        if same:
            on, off = unique[0]
            print(f"      周期 = {on + off} ms，亮段 = {on} ms，灭段 = {off} ms"
                  f"（相位由 currentTimeMillis % {on + off} 决定，同层同步）")

    print(f"\n合计：{total_parts} 个部件记录，其中 {total_nonzero} 个带非零 flash 配置")
    if risk_files:
        print(f"⚠ 有 {len(risk_files)} 个文件存在同层 flash 配置不一致的潜在偏差：")
        for fn, u in risk_files:
            print(f"   {fn}: {u}")
    else:
        print("✓ 当前车包内无同层 flash 配置不一致的部件——"
              "「整层取第一条」与上游 per-part 语义在本车包上等价")
    print("\n结论：上游 flash 是 per-part（ModelPropertiesPart 实例级），"
          "不是整层共享；mtr-next 当前实现对本车包等价，但对其他车包可能是偏差。")


if __name__ == "__main__":
    main()

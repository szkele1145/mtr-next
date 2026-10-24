"""
[0.35.3] 核对：同一顶层组下，"正位记录"与"翻转位记录"的门参数是否一致？

上游语义（ModelPropertiesPart.iteratePositions L654-656）：
  positions       → flipped=false（本体）
  positionsFlipped → flipped=true（镜像副本）
上游两条记录的 position 坐标可能相同（ERIDU 全为 0.0），
故 flipped 副本 = 同位置 + 几何镜像 + 门位移反号。

本脚本检查：按组聚合 part 记录，输出各组的
  - 记录条数 / 位定义模式
  - 各条记录的门参数（dx/dz/animation/condition/renderStage/type）
  - 两条记录门参数是否相同
"""
import json
import os
import sys
from collections import defaultdict


def load_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def main():
    pack = sys.argv[1] if len(sys.argv) > 1 else r"F:\vibe coding工程\mtr-turbo\build\eridu-pack"
    assets = os.path.join(pack, "assets", "mtr")

    defs = {}
    for name in os.listdir(assets):
        if name.startswith("definition_") and name.endswith(".json"):
            for d in load_json(os.path.join(assets, name)).get("positionDefinitions", []):
                defs[d["name"]] = (len(d.get("positions", [])), len(d.get("positionsFlipped", [])))

    # 组 → 记录列表
    group_records = defaultdict(list)
    for name in sorted(os.listdir(assets)):
        if not (name.startswith("properties_") and name.endswith(".json")):
            continue
        for p in load_json(os.path.join(assets, name)).get("parts", []):
            modes = []
            for uuid in p.get("positionDefinitions", []) or []:
                np, nf = defs.get(uuid, (0, 0))
                if np > 0 and nf > 0:
                    modes.append("BOTH")
                elif np > 0:
                    modes.append("NORMAL")
                elif nf > 0:
                    modes.append("FLIPPED")
                else:
                    modes.append("EMPTY")
            for gname in p.get("names", []):
                group_records[gname].append((name, p, modes))

    def door_params(p):
        return (
            p.get("doorXMultiplier", 0.0),
            p.get("doorZMultiplier", 0.0),
            p.get("doorAnimationType", ""),
            p.get("condition", ""),
            p.get("renderStage", ""),
            p.get("type", ""),
        )

    same = 0
    diff = 0
    diff_groups = []
    for gname in sorted(group_records):
        recs = group_records[gname]
        if len(recs) < 2:
            continue
        params = {door_params(p) for _, p, _ in recs}
        if len(params) == 1:
            same += 1
        else:
            diff += 1
            diff_groups.append(gname)

    print(f"多记录组：门参数一致 {same} 组，不一致 {diff} 组")
    if diff_groups:
        print("不一致的组：")
        for gname in diff_groups:
            print(f"  {gname}")
            for fname, p, modes in group_records[gname]:
                print(f"    [{fname}] modes={modes} params={door_params(p)}")

    # 门组抽样明细（men2/men3/win/door）
    print()
    print("=== 门组抽样明细 ===")
    for gname in ["men2", "men2_n", "men3", "men3_n", "win", "win_n", "door", "door_n",
                  "doordeng", "doordiban", "windeng", "windiban"]:
        if gname not in group_records:
            continue
        print(f"  {gname}:")
        for fname, p, modes in group_records[gname]:
            print(f"    modes={modes} params={door_params(p)}")

    # 所有含 FLIPPED 位的组里，那些"两条记录"的组有多少
    print()
    flipped_groups = []
    for gname, recs in group_records.items():
        if any("FLIPPED" in m or "BOTH" in m for _, _, ms in recs for m in ms):
            flipped_groups.append(gname)
    multi = [g for g in flipped_groups if len(group_records[g]) >= 2]
    single = [g for g in flipped_groups if len(group_records[g]) == 1]
    print(f"含翻转位的组共 {len(flipped_groups)}：多记录 {len(multi)}，单记录 {len(single)}")
    print(f"  单记录且含翻转位（本体即翻转位）：{sorted(single)[:40]}")


if __name__ == "__main__":
    main()

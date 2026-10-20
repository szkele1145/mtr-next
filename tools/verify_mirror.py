"""
[0.35.3] 用真实 ERIDU 车包数据验证镜像展开判定。

不依赖 Minecraft 运行时：直接复刻 BbModelLoader 的组名匹配 + 位定义
flipped 判定逻辑，统计"哪些顶层组会被判为需要镜像"，并与上游语义
（上游 ModelPropertiesPart.iteratePositions 同时渲染 positions 与
positionsFlipped）比对。

用法：python tools/verify_mirror.py <pack_dir>
"""
import json
import os
import sys
from collections import defaultdict


def load_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def collect_defs(pack):
    """名字 -> {np: 正位数, nf: 翻转位数}"""
    defs = {}
    assets = os.path.join(pack, "assets", "mtr")
    if not os.path.isdir(assets):
        return defs
    for name in os.listdir(assets):
        if not (name.startswith("definition_") and name.endswith(".json")):
            continue
        root = load_json(os.path.join(assets, name))
        for d in root.get("positionDefinitions", []):
            defs[d["name"]] = {
                "np": len(d.get("positions", [])),
                "nf": len(d.get("positionsFlipped", [])),
            }
    return defs


def collect_parts(pack):
    assets = os.path.join(pack, "assets", "mtr")
    out = []
    for name in sorted(os.listdir(assets)):
        if not (name.startswith("properties_") and name.endswith(".json")):
            continue
        root = load_json(os.path.join(assets, name))
        for p in root.get("parts", []):
            out.append((name, p))
    return out


def bbmodel_top_groups(pack):
    """bbmodel 顶层 outliner 组名 + 各组 X 侧分布"""
    assets = os.path.join(pack, "assets", "mtr")
    res = {}
    for name in sorted(os.listdir(assets)):
        if not name.endswith(".bbmodel"):
            continue
        root = load_json(os.path.join(assets, name))
        elements = {e["uuid"]: e for e in root.get("elements", [])}
        for g in root.get("outliner", []):
            gname = g.get("name", "")
            xs = []
            for c in g.get("children", []):
                if isinstance(c, str) and c in elements:
                    e = elements[c]
                    xs.append((e["from"][0] + e["to"][0]) / 2.0)
            res.setdefault(name, {})[gname] = xs
    return res


def main():
    pack = sys.argv[1] if len(sys.argv) > 1 else r"F:\vibe coding工程\mtr-turbo\build\eridu-pack"
    defs = collect_defs(pack)
    parts = collect_parts(pack)
    groups = bbmodel_top_groups(pack)

    print("=== 上游语义（ModelPropertiesPart.iteratePositions L654-656）===")
    print("  positions      → flipped=false")
    print("  positionsFlipped → flipped=true, X 取反（渲染为镜像副本）")
    print()

    # 每个 part 记录：组名 → 该记录的位定义模式
    group_modes = defaultdict(set)
    for fname, p in parts:
        for gname in p.get("names", []):
            for uuid in p.get("positionDefinitions", []) or []:
                d = defs.get(uuid)
                if d is None:
                    mode = "UNDEF"
                elif d["np"] > 0 and d["nf"] > 0:
                    mode = "BOTH"
                elif d["np"] > 0:
                    mode = "NORMAL"
                elif d["nf"] > 0:
                    mode = "FLIPPED"
                else:
                    mode = "EMPTY"
                group_modes[gname].add(mode)

    # mtr-next 的判定：顶层组只要有任一 part 记录引用了含翻转位的位定义 → 镜像
    mirror_groups = {g for g, modes in group_modes.items() if "FLIPPED" in modes or "BOTH" in modes}

    print("=== 需镜像的顶层组（含 flipped 位）===")
    for g in sorted(mirror_groups):
        print("  " + g)
    print(f"  共 {len(mirror_groups)} 组")
    print()

    # 关键核对：几何只在 +X 侧、却判为需要镜像 → 补齐左半侧
    print("=== 几何只在 +X 侧但需镜像（缺镜像则该侧完全无此部件）===")
    total_need = 0
    for bbname, gmap in groups.items():
        for gname, xs in gmap.items():
            if gname not in mirror_groups or not xs:
                continue
            only_right = all(x > 5 for x in xs)
            only_left = all(x < -5 for x in xs)
            both = any(x > 5 for x in xs) and any(x < -5 for x in xs)
            if only_right or only_left:
                side = "+X(右)" if only_right else "-X(左)"
                print(f"  [{bbname}] {gname}: 仅 {side} 侧有 {len(xs)} 个盒 → 镜像补齐另一侧")
                total_need += 1
            elif both:
                print(f"  [{bbname}] {gname}: 两侧都有 → 镜像会重叠？需复核")
    print(f"  共 {total_need} 组仅单侧")
    print()

    # 已含 flipped 位的组统计
    flipped_only = {g for g, m in group_modes.items() if "FLIPPED" in m}
    both = {g for g, m in group_modes.items() if "BOTH" in m}
    normal = {g for g, m in group_modes.items() if "NORMAL" in m}
    print("=== 位定义模式统计（按顶层组）===")
    print(f"  含 FLIPPED 位的组: {len(flipped_only)}")
    print(f"  含 BOTH 位的组:    {len(both)}")
    print(f"  仅 NORMAL 的组:    {len(normal)}")
    print(f"  门组（men2/men3/men2_n/men3_n/men2guang/men3guang）: "
          f"{sorted(g for g in group_modes if g.startswith('men'))}")


if __name__ == "__main__":
    main()

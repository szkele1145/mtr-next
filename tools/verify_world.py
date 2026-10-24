#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用上游 MTR4 公式复算 ERIDU 车体世界 AABB —— 决定性验证。

上游链路（逐字，来自 upstream_*.java）：
  BlockbenchOutline.add:
      顶层组 (previous==null): origin 原样, rotation -> (0,0,0)（WARN+忽略）
      子组   (previous!=null): origin - previous.origin, rotation 自身
  GroupTransformations 构造: pivot=(-ox,-oy,+oz), rot=(-r0,-r1,+r2) 弧度
  GroupTransformations.create(ModelPart):
      q_i = pivot_i - combined_{i-1}   (y 再减 offset，offset 仅首级)
      combined_i = combined_{i-1} + pivot_i
      节点姿态 = T(q_i/16) * Rz(rz) * Ry(ry) * Rx(rx)
  BlockbenchElement.setModelPart:
      originX=-ox, originY=-oy-16*off, originZ=+oz
      local = (-to.x - originX, -to.y - originY - 16*off, from.z - originZ)
            = (-to.x+ox, -to.y+oy, from.z-oz)
      元素级 = 链上再追加一级 GroupTransformation(origin=元素origin, rotation=元素rotation)
  宿主 RenderVehicles: Ry(yaw+PI) * Rx(pitch+PI) * [模型空间]
      yaw=pitch=0 -> diag(-1,-1,1) = Rz(PI)
"""
import json, sys, math

def mul(a, b):
    n = 4
    return [[sum(a[i][k]*b[k][j] for k in range(n)) for j in range(n)] for i in range(n)]

def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return [[1,0,0,0],[0,c,-s,0],[0,s,c,0],[0,0,0,1]]

def rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return [[c,0,s,0],[0,1,0,0],[-s,0,c,0],[0,0,0,1]]

def rot_z(a):
    c, s = math.cos(a), math.sin(a)
    return [[c,-s,0,0],[s,c,0,0],[0,0,1,0],[0,0,0,1]]

def trans(x, y, z):
    return [[1,0,0,x],[0,1,0,y],[0,0,1,z],[0,0,0,1]]

IDENT = [[1,0,0,0],[0,1,0,0],[0,0,1,0],[0,0,0,1]]
RZ_PI = rot_z(math.pi)

def apply(m, v):
    x, y, z = v
    return (m[0][0]*x+m[0][1]*y+m[0][2]*z+m[0][3],
            m[1][0]*x+m[1][1]*y+m[1][2]*z+m[1][3],
            m[2][0]*x+m[2][1]*y+m[2][2]*z+m[2][3])

def build_chain_matrix(chain, model_y_offset):
    """chain: [(origin, rotation, is_root)] 根->叶（叶子是元素级）。"""
    m = IDENT
    combined = [0.0, 0.0, 0.0]
    offset = model_y_offset * 16.0
    for (o, r, is_root) in chain:
        p = (-o[0], -o[1], o[2])
        q = (p[0]-combined[0], p[1]-combined[1]-offset, p[2]-combined[2])
        combined = [combined[0]+p[0], combined[1]+p[1], combined[2]+p[2]]
        if is_root:
            rx = ry = rz = 0.0
        else:
            rx = math.radians(-r[0]); ry = math.radians(-r[1]); rz = math.radians(r[2])
        node = mul(trans(*q), mul(rot_z(rz), mul(rot_y(ry), rot_x(rx))))
        m = mul(m, node)
        offset = 0.0
    return m

def main():
    path = sys.argv[1]
    offset = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0
    only = sys.argv[3] if len(sys.argv) > 3 else None
    with open(path, 'r', encoding='utf-8') as f:
        root = json.load(f)
    print("top-level keys:", sorted(root.keys()))
    print("flip_y top:", root.get('modded_entity_flip_y'), " meta:", root.get('meta'))
    elements = {e['uuid']: e for e in root.get('elements', [])}
    outliner = root.get('outliner', [])

    all_pts = []
    elem_origin_vs_group = []
    for grp in outliner:
        if not isinstance(grp, dict):
            continue
        gname = grp.get('name', '?')
        if only and gname != only:
            continue
        gorigin = grp.get('origin', [0,0,0])
        grot = grp.get('rotation', [0,0,0]) or [0,0,0]
        kids = grp.get('children', []) or []
        # 顶层组链
        for kid in kids:
            if not isinstance(kid, str):
                continue
            e = elements.get(kid)
            if e is None:
                continue
            eo = e.get('origin', [0,0,0]) or [0,0,0]
            er = e.get('rotation', [0,0,0]) or [0,0,0]
            elem_origin_vs_group.append((gname, tuple(gorigin), tuple(eo), tuple(er)))
            chain = [(gorigin, grot, True), (eo, er, False)]
            m = build_chain_matrix(chain, offset)
            wm = mul(RZ_PI, m)
            frm = e['from']; to = e['to']
            local = (-to[0]+eo[0], -to[1]+eo[1], frm[2]-eo[2])
            for i in (0,1):
                for j in (0,1):
                    for k in (0,1):
                        p = (local[0]+i*(to[0]-frm[0]),
                             local[1]+j*(to[1]-frm[1]),
                             local[2]+k*(to[2]-frm[2]))
                        w = apply(wm, p)
                        all_pts.append(w)

    if not all_pts:
        print("no points"); return
    xs = [p[0]/16 for p in all_pts]; ys = [p[1]/16 for p in all_pts]; zs = [p[2]/16 for p in all_pts]
    print("WORLD AABB (blocks):")
    print("  X [%.3f, %.3f]  len=%.3f" % (min(xs), max(xs), max(xs)-min(xs)))
    print("  Y [%.3f, %.3f]  len=%.3f" % (min(ys), max(ys), max(ys)-min(ys)))
    print("  Z [%.3f, %.3f]  len=%.3f" % (min(zs), max(zs), max(zs)-min(zs)))
    # 元素 origin vs 组 origin
    same = sum(1 for (_, g, e, _) in elem_origin_vs_group if g == e)
    diff = [ (g,e) for (_,g,e,_) in elem_origin_vs_group if g != e ]
    print("elem origin == group origin: %d / %d ; differing: %d" % (same, len(elem_origin_vs_group), len(diff)))
    for g, e in diff[:6]:
        print("   group", g, "elem", e)
    nzrot = sum(1 for (_,_,_,r) in elem_origin_vs_group if any(abs(x) > 1e-6 for x in r))
    print("elements with non-zero rotation:", nzrot, "/", len(elem_origin_vs_group))

main()

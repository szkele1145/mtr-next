#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用上游 MTR 4 公式复算 ERIDU bbmodel 的世界坐标，验证组链 pivot 语义。

上游链路（逐字）：
  BlockbenchOutline.add: 顶层组 origin 原样；子组 origin - parent.origin
  GroupTransformations:   pivot = (-ox, -oy, +oz)
                          rot   = (-r0, -r1, +r2)  (弧度)
  GroupTransformations.create(ModelPart):
      setPivot(pg_i - combined_{i-1}, ...) ; combined += pg_i
  BlockbenchElement.setModelPart:
      cuboid local x = -to[0] + origin[0]
                      y = -to[1] + origin[1]
                      z =  from[2] - origin[2]
      元素级 pivot = (-ox, -oy, +oz) ; 元素级 rot = (-r0,-r1,+r2)
  宿主: Ry(yaw+PI) * Rx(pitch+PI)   -> yaw=pitch=0 时 = Rz(PI)=diag(-1,-1,1)
"""
import json, sys, os, math

def load(p):
    with open(p, 'r', encoding='utf-8') as f:
        return json.load(f)

def sign3(o):
    return (-o[0], -o[1], o[2])

def mat_from_chain(chain):
    """chain: [(origin_abs, rot)] 根->叶。返回 4x4 (row-major list of rows)。"""
    m = [[1,0,0,0],[0,1,0,0],[0,0,1,0],[0,0,0,1]]
    combined = [0.0,0.0,0.0]
    for (o, r) in chain:
        p = sign3(o)
        q = (p[0]-combined[0], p[1]-combined[1], p[2]-combined[2])
        combined = (combined[0]+p[0], combined[1]+p[1], combined[2]+p[2])
        m = mul(m, make_node(q, r))
    return m

def make_node(q, r):
    rx, ry, rz = [math.radians(x) for x in (-r[0], -r[1], r[2])]
    rxm = [[1,0,0],[0,math.cos(rx),-math.sin(rx)],[0,math.sin(rx),math.cos(rx)]]
    rym = [[math.cos(ry),0,math.sin(ry)],[0,1,0],[-math.sin(ry),0,math.cos(ry)]]
    rzm = [[math.cos(rz),-math.sin(rz),0],[math.sin(rz),math.cos(rz),0],[0,0,1]]
    rot = mul3(mul3(rzm, rym), rxm)
    t = [[rot[0][0],rot[0][1],rot[0][2],q[0]],
         [rot[1][0],rot[1][1],rot[1][2],q[1]],
         [rot[2][0],rot[2][1],rot[2][2],q[2]],
         [0,0,0,1]]
    return t

def mul3(a, b):
    return [[sum(a[i][k]*b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]

def mul(a, b):
    return [[sum(a[i][k]*b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]

def apply(m, v):
    x, y, z = v
    return (m[0][0]*x+m[0][1]*y+m[0][2]*z+m[0][3],
            m[1][0]*x+m[1][1]*y+m[1][2]*z+m[1][3],
            m[2][0]*x+m[2][1]*y+m[2][2]*z+m[2][3])

def rot_pi(m):
    """Rz(PI) = diag(-1,-1,1)"""
    r = [[1,0,0,0],[0,1,0,0],[0,0,1,0],[0,0,0,1]]
    r[0][0] = -1; r[1][1] = -1
    return mul(r, m)

def main():
    path = sys.argv[1]
    root = load(path)
    print("=== meta:", json.dumps(root.get('meta', {}), ensure_ascii=False))
    print("=== resolution:", json.dumps(root.get('resolution', {}), ensure_ascii=False))
    groups = root.get('groups', []) or []
    outliner = root.get('outliner', []) or []
    print("=== groups count:", len(groups), " outliner count:", len(outliner))
    for i, g in enumerate(groups[:10]):
        print("  group[%d] name=%r origin=%s rotation=%s children_uuid=%d child_objs=%d" % (
            i, g.get('name'), g.get('origin'), g.get('rotation'),
            len(g.get('children', []) or []), 0))
    for i, g in enumerate(outliner[:10]):
        if isinstance(g, dict):
            print("  outliner[%d] name=%r origin=%s rotation=%s children=%s" % (
                i, g.get('name'), g.get('origin'), g.get('rotation'),
                type(g.get('children')).__name__))
        else:
            print("  outliner[%d] = uuid %s" % (i, g))

    # 统计嵌套深度
    def walk(el, depth, chain_names):
        if isinstance(el, str):
            return
        if not isinstance(el, dict):
            return
        nm = el.get('name', '?')
        org = el.get('origin', [0,0,0])
        rot = el.get('rotation', [0,0,0])
        kids = el.get('children', [])
        print("   %s%-14s origin=%s rot=%s kids=%d" % ('  '*depth, nm, org, rot, len(kids)))
        if depth < 2:
            for c in kids[:4]:
                walk(c, depth+1, chain_names+[nm])
    print("=== outliner tree (前若干):")
    for el in outliner:
        walk(el, 0, [])
        print("   ---")

main()

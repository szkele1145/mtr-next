# -*- coding: utf-8 -*-
"""
[0.35] bbmodel box-UV 布局实证：MC(1.20.1) 布局 vs Blockbench 原生布局。

问题：上游 MTR 4 把 BB 的 `uv_offset` 直接喂给 MC `CubeListBuilder.addBox(..., u, v)`，
完全忽略 `faces[].uv`。若 MC 布局 != BB 布局，上游自家模型也会 UV 错位。
本脚本从 MC 字节码复刻布局，逐元素对比 BB 写入的 faces[].uv —— 用数据判定。

MC 布局（javap -c ModelPart$Cube 读出）：
  u0=u, u1=u+dz, u2=u+dz+dx, u3=u+dz+2dx, u4=u+2dz+dx
  v0=v, v1=v+dz, v2=v+dz+dy
"""
import json
import sys

DIRS = ('north', 'south', 'east', 'west', 'up', 'down')


def load(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def mc_layout(u, v, dx, dy, dz):
    """MC Cube 布局 → {方向: (x1,y1,x2,y2)}，方向名按 MC 的语义命名。"""
    u0, u1, u2, u3, u4 = u, u + dz, u + dz + dx, u + dz + 2 * dx, u + 2 * dz + dx
    v0, v1, v2 = v, v + dz, v + dz + dy
    return {
        'down':  (u1, v0, u2, v1),
        'up':    (u2, v0, u3, v1),
        'west':  (u0, v1, u1, v2),
        'north': (u1, v1, u2, v2),
        'east':  (u2, v1, u3, v2),
        'south': (u3, v1, u4, v2),
    }


def analyse(path, label):
    root = load(path)
    els = root.get('elements') or []
    box_uv_flags = set()
    with_uvoff = 0
    exact = 0
    swapped_ew = 0
    total_cmp = 0
    mismatch_samples = []
    for e in els:
        box_uv_flags.add(bool(e.get('box_uv')))
        if 'uv_offset' not in e:
            continue
        with_uvoff += 1
        frm, to = e.get('from'), e.get('to')
        if not frm or not to:
            continue
        u, v = e['uv_offset']
        dx = int(round(abs(to[0] - frm[0])))
        dy = int(round(abs(to[1] - frm[1])))
        dz = int(round(abs(to[2] - frm[2])))
        want = mc_layout(u, v, dx, dy, dz)
        faces = e.get('faces') or {}
        ok, ok_sw = True, True
        for d in DIRS:
            f = faces.get(d)
            if not f or 'uv' not in f:
                continue
            got = tuple(f['uv'])
            total_cmp += 1
            if got != want[d]:
                ok = False
            other = 'west' if d == 'east' else ('east' if d == 'west' else d)
            if got != want[other]:
                ok_sw = False
        if ok:
            exact += 1
        if ok_sw and not ok:
            swapped_ew += 1
        if not ok and len(mismatch_samples) < 3:
            mismatch_samples.append((e.get('name'), (dx, dy, dz), (u, v),
                                     {d: faces.get(d, {}).get('uv') for d in DIRS},
                                     want))
    print('=== %s ===' % label)
    print('  elements=%d  box_uv flags=%s  with uv_offset=%d' % (len(els), box_uv_flags, with_uvoff))
    print('  exact match (MC layout, 同名方向) = %d / %d elements' % (exact, with_uvoff))
    print('  match only if east<->west swapped   = %d' % swapped_ew)
    print('  faces compared = %d' % total_cmp)
    for s in mismatch_samples:
        print('  MISMATCH %s dims=%s uv_offset=%s' % (s[0], s[1], s[2]))
        print('     bb  = %s' % s[3])
        print('     mc  = %s' % s[4])
    return exact, with_uvoff


if __name__ == '__main__':
    for p, lab in [
        (r'F:\vibe coding工程\mtr-next\tools\upstream_s700.bbmodel', 'upstream s700'),
        (r'F:\vibe coding工程\mtr-turbo\build\eridu-pack\assets\mtr\eridudanguei.bbmodel', 'ERIDU danguei'),
    ]:
        try:
            analyse(p, lab)
        except Exception as ex:
            print('FAILED %s: %s' % (p, ex))
        print()

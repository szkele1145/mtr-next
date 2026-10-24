# -*- coding: utf-8 -*-
"""
[0.35] 为什么 west/east 映射分裂？检验：分裂是否由 autouv / uv_per_face 决定。

BB 的 box-UV 与 MC 的 box-UV 布局在"区域形状"上完全一致（已实证 MC 真实矩形），
只有面名归属不同：MC west<->BB east、MC east<->BB west、MC up<->BB down、MC down<->BB up。
这正好由上游的 X 取反 / Y 取反补偿。

本脚本检验：不互换的那 291 个（ERIDU）是否 autouv=0 或 uv_per_face=true。
"""
import json
from collections import Counter

MC_FACES = ('down', 'up', 'west', 'north', 'east', 'south')


def mc_regions(u, v, w, h, d):
    return {
        'down':  (u + d,         v,         u + d + w,         v + d),
        'up':    (u + d + w,     v,         u + d + 2 * w,     v + d),
        'west':  (u,             v + d,     u + d,             v + d + h),
        'north': (u + d,         v + d,     u + d + w,         v + d + h),
        'east':  (u + d + w,     v + d,     u + 2 * d + w,     v + d + h),
        'south': (u + 2 * d + w, v + d,     u + 2 * d + 2 * w, v + d + h),
    }


def norm(r):
    a, b, c, d = r
    return (min(a, c), min(b, d), max(a, c), max(b, d))


def run(path, label):
    with open(path, encoding='utf-8') as f:
        root = json.load(f)
    els = root.get('elements') or []
    print('=== %s ===' % label)
    keys = set()
    for e in els:
        keys.update(e.keys())
    print('  element keys:', sorted(k for k in keys if 'uv' in k or 'auto' in k or k == 'rotation'))
    tally = Counter()
    for e in els:
        if 'uv_offset' not in e:
            continue
        frm, to = e.get('from'), e.get('to')
        faces = e.get('faces') or {}
        if not frm or not to:
            continue
        w = int(round(abs(to[0] - frm[0])))
        h = int(round(abs(to[1] - frm[1])))
        d = int(round(abs(to[2] - frm[2])))
        u, v = e['uv_offset']
        reg = {k: norm(vv) for k, vv in mc_regions(u, v, w, h, d).items()}
        fw = faces.get('west') or {}
        if 'uv' not in fw:
            continue
        r = norm(tuple(fw['uv']))
        if r == reg['east']:
            state = 'swapped'
        elif r == reg['west']:
            state = 'asis'
        else:
            state = 'other'
        autouv = e.get('autouv', 'MISSING')
        tally[(state, autouv)] += 1
    for k, c in sorted(tally.items(), key=lambda t: -t[1]):
        print('  west=%-8s autouv=%-8s : %d' % (k[0], k[1], c))
    print()


if __name__ == '__main__':
    run(r'F:\vibe coding工程\mtr-next\tools\upstream_s700.bbmodel', 'upstream s700')
    run(r'F:\vibe coding工程\mtr-turbo\build\eridu-pack\assets\mtr\eridudanguei.bbmodel', 'ERIDU danguei')

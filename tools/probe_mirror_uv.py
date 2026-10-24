# -*- coding: utf-8 -*-
"""
[0.35] 检验 mirror_uv 是否解释 west/east 映射分裂。
BB 的 mirror_uv=true 会把盒子的 UV 布局水平镜像，导致 east/west 区域互换。
"""
import json
from collections import Counter


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
        state = 'swapped' if r == reg['east'] else ('asis' if r == reg['west'] else 'other')
        tally[(bool(e.get('mirror_uv')), state)] += 1
    print('=== %s ===' % label)
    for k, c in sorted(tally.items(), key=lambda t: (t[0][0], -t[1])):
        print('  mirror_uv=%-5s west=%-8s : %d' % (k[0], k[1], c))
    print()


if __name__ == '__main__':
    run(r'F:\vibe coding工程\mtr-next\tools\upstream_s700.bbmodel', 'upstream s700')
    run(r'F:\vibe coding工程\mtr-turbo\build\eridu-pack\assets\mtr\eridudanguei.bbmodel', 'ERIDU danguei')

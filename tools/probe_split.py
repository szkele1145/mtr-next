# -*- coding: utf-8 -*-
"""
[0.35] 解释 west/east 映射分裂（645 互换 vs 291 不互换）。
假说：BB 对"负尺寸"元素（from[axis] > to[axis]，BB 允许）的 box-UV
      east/west 区域会镜像回来（等价于把盒子当正尺寸排布）。
      验证：分裂组与 from[0] > to[0]（负 dx）是否一一对应。
"""
import json

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


def norm(rect):
    a, b, c, d = rect
    return (min(a, c), min(b, d), max(a, c), max(b, d))


def run(path, label):
    with open(path, encoding='utf-8') as f:
        root = json.load(f)
    els = root.get('elements') or []
    # 按 (是否负dx, west匹配到谁) 计数
    from collections import Counter
    tally = Counter()
    samples = {}
    for e in els:
        if 'uv_offset' not in e:
            continue
        frm, to = e.get('from'), e.get('to')
        faces = e.get('faces') or {}
        if not frm or not to:
            continue
        raw_dx = to[0] - frm[0]
        w = int(round(abs(raw_dx)))
        h = int(round(abs(to[1] - frm[1])))
        d = int(round(abs(to[2] - frm[2])))
        u, v = e['uv_offset']
        reg = {k: norm(vv) for k, vv in mc_regions(u, v, w, h, d).items()}
        fw = faces.get('west') or {}
        if 'uv' not in fw:
            continue
        r = norm(tuple(fw['uv']))
        if r == reg['east']:
            key = 'swapped'
        elif r == reg['west']:
            key = 'not-swapped'
        else:
            key = 'other:%s' % (r,)
        negdx = raw_dx < -0.01
        tally[(negdx, key)] += 1
        if key not in samples:
            samples[key] = (e.get('name'), frm, to, e['uv_offset'], r)
    print('=== %s ===' % label)
    for (negdx, key), c in sorted(tally.items()):
        print('  negdx=%-5s %-12s : %d' % (negdx, key, c))
    print()


if __name__ == '__main__':
    run(r'F:\vibe coding工程\mtr-next\tools\upstream_s700.bbmodel', 'upstream s700')
    run(r'F:\vibe coding工程\mtr-turbo\build\eridu-pack\assets\mtr\eridudanguei.bbmodel', 'ERIDU danguei')

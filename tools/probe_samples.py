# -*- coding: utf-8 -*-
"""
[0.35] 打印 west/east 映射分裂的具体样本，直观数据说话。
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


def norm(r):
    a, b, c, d = r
    return (min(a, c), min(b, d), max(a, c), max(b, d))


def run(path, label, want_state, limit=4):
    with open(path, encoding='utf-8') as f:
        root = json.load(f)
    els = root.get('elements') or []
    shown = 0
    print('=== %s (%s) ===' % (label, want_state))
    for e in els:
        if 'uv_offset' not in e or shown >= limit:
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
        if state != want_state:
            continue
        shown += 1
        print('  name=%s' % e.get('name'))
        print('    from=%s to=%s dims=(w=%d,h=%d,d=%d) uv_offset=%s' % (frm, to, w, h, d, (u, v)))
        print('    mc regions: %s' % reg)
        print('    bb faces uv:')
        for f in MC_FACES:
            ff = faces.get(f) or {}
            if 'uv' in ff:
                print('      %-6s %s' % (f, ff['uv']))
        print()


def swapcase_idx(frm, to):
    return 0


if __name__ == '__main__':
    run(r'F:\vibe coding工程\mtr-turbo\build\eridu-pack\assets\mtr\eridudanguei.bbmodel', 'ERIDU danguei', 'asis', 4)

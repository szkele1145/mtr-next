# -*- coding: utf-8 -*-
"""
[0.35] 暴力反推 BB 的真实 box-UV 布局。

方法：对每个 element，计算 MC 1.20.1 的 6 个面区域矩形，
      再看 BB 的 faces[].uv 各自等于哪个 MC 面区域，统计 BB面 -> MC面 的映射频次。
      同时报告无法匹配的 element（带 rotation / autouv 等信息）。

MC 1.20.1 box-UV 布局（ModelPart$Cube 字节码逐面读出）：
  f27=u f28=u+z f29=u+z+x f30=u+z+2x f31=u+2z+x f32=u+2z+2x
  f33=v f34=v+z f35=v+z+y
  DOWN: (f28,f33)-(f29,f34)      UP:   (f29,f34)-(f30,f33)
  WEST: (f27,f34)-(f28,f35)      NORTH:(f28,f34)-(f29,f35)
  EAST: (f29,f34)-(f31,f35)      SOUTH:(f31,f34)-(f32,f35)
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


def norm(rect):
    a, b, c, d = rect
    return (min(a, c), min(b, d), max(a, c), max(b, d))


def bb_rect(face):
    if not face or 'uv' not in face:
        return None
    return norm(tuple(face['uv']))


def run(path, label):
    with open(path, encoding='utf-8') as f:
        root = json.load(f)
    els = root.get('elements') or []
    tally = Counter()
    total_faces = 0
    unmatched = []
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
        bad_here = []
        for bbf in MC_FACES:
            r = bb_rect(faces.get(bbf))
            if r is None:
                continue
            total_faces += 1
            hit = [m for m in MC_FACES if reg[m] == r]
            if hit:
                for m in hit:
                    tally[(bbf, m)] += 1
            else:
                bad_here.append((bbf, r))
        if bad_here:
            unmatched.append((e.get('name'), (w, h, d), (u, v), bad_here,
                               e.get('rotation'), e.get('autouv'), e.get('uv_per_face')))

    print('=== %s ===' % label)
    print('  elements=%d  faces compared=%d  unmatched elements=%d'
          % (len(els), total_faces, len(unmatched)))
    # 找每个 BB 面最常见的匹配
    print('  BB面 -> MC面 映射频次:')
    for bbf in MC_FACES:
        parts = [(m, c) for (b, m), c in tally.items() if b == bbf]
        parts.sort(key=lambda t: -t[1])
        if parts:
            print('    %-6s -> %s' % (bbf, ', '.join('%s:%d' % (m, c) for m, c in parts)))
    if unmatched:
        print('  未匹配样本(最多6):')
        for um in unmatched[:6]:
            print('    name=%s dims=%s off=%s rot=%s autouv=%s perface=%s'
                  % (um[0], um[1], um[2], um[3], um[4], um[5]))
            print('      bad=%s' % (um[3],))
    print()


if __name__ == '__main__':
    run(r'F:\vibe coding工程\mtr-next\tools\upstream_s700.bbmodel', 'upstream s700')
    run(r'F:\vibe coding工程\mtr-turbo\build\eridu-pack\assets\mtr\eridudanguei.bbmodel', 'ERIDU danguei')

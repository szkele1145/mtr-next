# -*- coding: utf-8 -*-
"""
[0.35] 验证假说：
  上游 MTR 4 把 BB 的 bbmodel 几何做 X/Y 取反（originX=-origin[0], originY=-origin[1]），Z 保留。
  MC 的 box-UV 布局把 west/east、up/down 区域与 BB 的 faces[].uv 错位（north/south 对齐）。
  若假说成立，则：
     MC west 区域 == BB east 区域   (X 取反)
     MC east 区域 == BB west 区域   (X 取反)
     MC down 区域 == BB up 区域     (Y 取反)
     MC up   区域 == BB down 区域   (Y 取反)
     MC north区域 == BB north 区域  (Z 保留)
     MC south区域 == BB south 区域  (Z 保留)
  ⇒ 原生构造（X/Y 取反 + MC addBox(uv_offset)）可精确复现 BB 的逐面 UV。
"""
import json

DIRS = ('north', 'south', 'east', 'west', 'up', 'down')

# MC 1.20.1 box-UV 布局（从 ModelPart$Cube 字节码逐面读出）
# f27=u, f28=u+z, f29=u+z+x, f30=u+z+2x, f31=u+2z+x, f32=u+2z+2x
# f33=v, f34=v+z, f35=v+z+y
# DOWN: f28,f33 → f29,f34    UP: f29,f34 → f30,f33
# WEST: f27,f34 → f28,f35   NORTH: f28,f34 → f29,f35
# EAST: f29,f34 → f31,f35   SOUTH: f31,f34 → f32,f35
def mc_regions(u, v, w, h, d):
    return {
        'down':  (u + d,         v,         u + d + w,         v + d),
        'up':    (u + d + w,     v,         u + d + 2 * w,     v + d),
        'west':  (u,             v + d,     u + d,             v + d + h),
        'north': (u + d,         v + d,     u + d + w,         v + d + h),
        'east':  (u + d + w,     v + d,     u + 2 * d + w,     v + d + h),
        'south': (u + 2 * d + w, v + d,     u + 2 * d + 2 * w, v + d + h),
    }

# 假说映射：MC 面 ← BB 面
MC_TO_BB = {'down': 'up', 'up': 'down', 'west': 'east', 'east': 'west',
            'north': 'north', 'south': 'south'}


def bb_rect(faces, d):
    f = faces.get(d)
    if not f or 'uv' not in f:
        return None
    a, b, c, e = f['uv']
    return (min(a, c), min(b, e), max(a, c), max(b, e))


def check(path, label):
    with open(path, encoding='utf-8') as f:
        root = json.load(f)
    els = root.get('elements') or []
    tot = ok = 0
    bad = []
    for e in els:
        if 'uv_offset' not in e:
            continue
        frm, to = e.get('from'), e.get('to')
        faces = e.get('faces') or {}
        if not frm or not to or not faces:
            continue
        w = int(round(abs(to[0] - frm[0])))
        h = int(round(abs(to[1] - frm[1])))
        d = int(round(abs(to[2] - frm[2])))
        u, v = e['uv_offset']
        reg = mc_regions(u, v, w, h, d)
        for mcface in DIRS:
            bbf = MC_TO_BB[mcface]
            want = reg[mcface]
            got = bb_rect(faces, bbf)
            if got is None:
                continue
            tot += 1
            if got == want:
                ok += 1
            elif len(bad) < 3:
                bad.append((e.get('name'), mcface, bbf, want, got, (w, h, d), (u, v)))
    print('=== %s ===' % label)
    print('  elements=%d, uv_offset=%d' % (len(els), sum(1 for e in els if 'uv_offset' in e)))
    print('  假说映射下 face-UV 精确匹配 = %d / %d (%.2f%%)' % (ok, tot, 100.0 * ok / tot if tot else 0))
    for b in bad:
        print('  MISMATCH %s mc=%s bb=%s want=%s got=%s dims=%s off=%s' % b)
    return ok, tot


if __name__ == '__main__':
    check(r'F:\vibe coding工程\mtr-next\tools\upstream_s700.bbmodel', 'upstream s700')
    print()
    check(r'F:\vibe coding工程\mtr-turbo\build\eridu-pack\assets\mtr\eridudanguei.bbmodel', 'ERIDU danguei')

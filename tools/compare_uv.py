#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验证 BB per-face uv 与 MC box-UV 布局（CubeListBuilder）是否等价。

MC 标准 box UV（宽 w、高 h、深 d，贴图偏移 (u,v)）：
  north(-Z): u+d          .. u+d+w
  east (+X): u            .. u+d
  south(+Z): u+d+w        .. u+d+w+d
  west (-X): u+w+d        .. u+w+d+d
  up   (+Y): (u+d, v)     .. (u+d+w, v+d)
  down (-Y): (u+d+w, v)   .. (u+d+w+w, v+d)
"""
import json, sys

FACES = ['north', 'east', 'south', 'west', 'up', 'down']

def mc_box_uv(u, v, w, h, d):
    """返回 {face: (u1, v1, u2, v2)}"""
    return {
        'north': (u + d,         v,         u + d + w,     v + h),
        'east':  (u,             v,         u + d,         v + h),
        'south': (u + d + w,     v,         u + d + w + d, v + h),
        'west':  (u + w + d,     v,         u + w + d + d, v + h),
        'up':    (u + d,         v,         u + d + w,     v + d),
        'down':  (u + d + w,     v,         u + d + w + w, v + d),
    }

def main():
    path = sys.argv[1]
    with open(path, 'r', encoding='utf-8') as f:
        root = json.load(f)
    elems = root.get('elements', [])
    total = 0
    match = 0
    per_face_stat = {f: [0, 0] for f in FACES}
    samples = []
    for e in elems:
        uvo = e.get('uv_offset')
        faces = e.get('faces')
        if uvo is None or not faces:
            continue
        fr = e['from']; to = e['to']
        w = int(round(to[0] - fr[0])); h = int(round(to[1] - fr[1])); d = int(round(to[2] - fr[2]))
        if w <= 0 or h <= 0 or d <= 0:
            continue
        mc = mc_box_uv(int(uvo[0]), int(uvo[1]), w, h, d)
        total += 1
        allok = True
        for f in FACES:
            if f not in faces: 
                allok = False
                break
            uv = faces[f].get('uv')
            if uv is None:
                allok = False
                break
            bb = (uv[0], uv[1], uv[2], uv[3])
            m = mc[f]
            per_face_stat[f][1] += 1
            if bb == m:
                per_face_stat[f][0] += 1
            else:
                allok = False
        if allok:
            match += 1
        elif len(samples) < 6:
            samples.append((e.get('name'), uvo, (w, h, d),
                            {f: faces[f].get('uv') for f in FACES if f in faces},
                            mc))
    print("box_uv elements compared: %d ; full match: %d (%.1f%%)" % (total, match, 100.0*match/max(total,1)))
    for f in FACES:
        a, b = per_face_stat[f]
        print("   %-6s match %d / %d" % (f, a, b))
    for s in samples:
        print("--- sample", s[0], "uv_offset", s[1], "size(w,h,d)", s[2])
        for f in FACES:
            print("      %-6s bb=%s  mc=%s" % (f, s[3].get(f), s[4][f]))

main()

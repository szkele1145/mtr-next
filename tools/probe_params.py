# -*- coding: utf-8 -*-
"""[0.35] 统计 inflate / shade / rotation 使用情况，为原生构造提供参数面。"""
import json
from collections import Counter

for path, label in [
    (r'F:\vibe coding工程\mtr-next\tools\upstream_s700.bbmodel', 's700'),
    (r'F:\vibe coding工程\mtr-turbo\build\eridu-pack\assets\mtr\eridudanguei.bbmodel', 'eridu-danguei'),
]:
    with open(path, encoding='utf-8') as f:
        root = json.load(f)
    els = root.get('elements') or []
    infl = Counter()
    shade = Counter()
    rot = Counter()
    nonboxuv = 0
    for e in els:
        infl[round(float(e.get('inflate', 0)), 4)] += 1
        shade[e.get('shade', 'MISSING')] += 1
        rot[tuple(e.get('rotation') or (0, 0, 0))] += 1
        if e.get('box_uv') is False:
            nonboxuv += 1
    print('=== %s: %d elements ===' % (label, len(els)))
    print('  inflate:', dict(infl))
    print('  shade:', dict(shade))
    print('  non-box_uv:', nonboxuv)
    print('  rotation:', {k: v for k, v in rot.items() if any(k)})
    print()

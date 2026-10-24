# -*- coding: utf-8 -*-
"""[0.35] 结构勘察：outliner 顶层条目类型、groups 数组、嵌套 children 形态。"""
import json

for path, label in [
    (r'F:\vibe coding工程\mtr-next\tools\upstream_s700.bbmodel', 's700'),
    (r'F:\vibe coding工程\mtr-turbo\build\eridu-pack\assets\mtr\eridudanguei.bbmodel', 'eridu'),
]:
    with open(path, encoding='utf-8') as f:
        root = json.load(f)
    print('=== %s ===' % label)
    print('  top keys:', sorted(root.keys()))
    print('  format_version:', root.get('format_version'), ' meta.model_format:', (root.get('meta') or {}).get('model_format'))
    print('  resolution:', root.get('resolution'), ' visible_box:', root.get('visible_box'))
    groups = root.get('groups')
    print('  groups array:', 'ABSENT' if groups is None else len(groups))
    out = root.get('outliner') or []

    def kind(x):
        if isinstance(x, str):
            return 'uuid'
        if isinstance(x, dict):
            return 'dict'
        return type(x).__name__

    print('  outliner len=%d, top kinds=%s' % (len(out), sorted({kind(x) for x in out})))
    # 检查顶层 dict 的键
    d0 = next((x for x in out if isinstance(x, dict)), None)
    if d0 is not None:
        print('  top dict keys:', sorted(d0.keys()))
    # 检查 children 里的类型
    allkinds = set()
    nested = 0
    def walk(arr, depth):
        global nested
        for x in arr:
            allkinds.add(kind(x))
            if isinstance(x, dict):
                nested += 1
                if depth < 3:
                    walk(x.get('children') or [], depth + 1)
    walk(out, 0)
    print('  all children kinds:', sorted(allkinds), ' total dict nodes:', nested)
    # 顶层组的名字样本
    names = [x.get('name') for x in out if isinstance(x, dict)][:12]
    print('  top group names sample:', names)
    if groups:
        print('  groups[0] keys:', sorted(groups[0].keys()))
        print('  groups names sample:', [g.get('name') for g in groups][:12])
    print()

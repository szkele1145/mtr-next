import json, sys, collections

path = sys.argv[1]
with open(path, 'r', encoding='utf-8') as f:
    j = json.load(f)

els = {e['uuid']: e for e in j['elements']}

def bounds_of(uuids):
    xs, ys, zs = [], [], []
    for u in uuids:
        e = els.get(u)
        if not e:
            continue
        fr, to = e['from'], e['to']
        xs += [fr[0], to[0]]
        ys += [fr[1], to[1]]
        zs += [fr[2], to[2]]
    if not xs:
        return None
    return (min(xs), max(xs), min(ys), max(ys), min(zs), max(zs))

def walk(children, out, depth=0, parent=''):
    for c in children:
        if isinstance(c, str):
            e = els.get(c)
            if e:
                out.append((depth, parent, c, e))
        elif isinstance(c, dict):
            out.append((depth, parent, c.get('name'), c))
            walk(c.get('children', []), out, depth + 1, c.get('name'))

out = []
walk(j.get('outliner', []), out)

# group -> direct element children
groups = []
def collect(children, path):
    for c in children:
        if isinstance(c, dict):
            groups.append((path + [c.get('name')], c))
            collect(c.get('children', []), path + [c.get('name')])
collect(j.get('outliner', []), [])

print('=== TOP-LEVEL GROUP BOUNDS (own + descendants) ===')
def descend_elements(g):
    acc = []
    def rec(node):
        for c in node.get('children', []):
            if isinstance(c, str):
                acc.append(c)
            else:
                rec(c)
    rec(g)
    return acc

for p, g in groups:
    if len(p) <= 1:
        b = bounds_of(descend_elements(g))
        if b:
            print('  %-22s X[%7.1f..%7.1f] Y[%7.1f..%7.1f] Z[%7.1f..%7.1f]  n=%d' % (
                '/'.join(str(x) for x in p), b[0], b[1], b[2], b[3], b[4], b[5], len(descend_elements(g))))

print()
print('=== LARGE SLAB ELEMENTS (any dim >= 40) — body panels ===')
cnt = 0
for e in j['elements']:
    fr, to = e['from'], e['to']
    w, h, d = to[0]-fr[0], to[1]-fr[1], to[2]-fr[2]
    if max(w, h, d) >= 40:
        print('  from=%s to=%s  size=(%.1f,%.1f,%.1f)' % (fr, to, w, h, d))
        cnt += 1
        if cnt > 40:
            print('  ... truncated')
            break

print()
print('=== ELEMENT SIZE HISTOGRAM (rounded) ===')
hist = collections.Counter()
for e in j['elements']:
    fr, to = e['from'], e['to']
    key = (round(to[0]-fr[0]), round(to[1]-fr[1]), round(to[2]-fr[2]))
    hist[key] += 1
for k, v in hist.most_common(15):
    print('  size=%s  x%d' % (str(k), v))

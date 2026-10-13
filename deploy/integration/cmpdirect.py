#!/usr/bin/env python3
"""Group the direct-probe differences of two diffprobe results by kind (helper for docs/INTEGRATION.md)."""
import json, sys
A = json.load(open(sys.argv[1]))['direct']; B = json.load(open(sys.argv[2]))['direct']
def f(v):
    if v is None: return 'MISSING'
    d = v.get('data', 'ABSENT'); d = d if isinstance(d, str) else 'obj'
    return f"{v['status']} data={d} ext={v.get('extKeys')} " + ' | '.join(
        f"{(e['message'] or '')[:150]} @{e['path']} ext={e['extKeys']} loc={e['locations']}" for e in v.get('errors', [])[:2]) + (v.get('nonJson', '') or '')
def strip(v, ignore):
    v = {x: y for x, y in v.items() if x not in ('dataValue',) + ignore}
    v['errors'] = [{k: w for k, w in e.items() if not (k == 'locations' and 'entities' in ignore)} for e in v.get('errors', [])]
    return v
ftv1 = only = 0
for k in sorted(set(A) | set(B)):
    a, b = A.get(k), B.get(k)
    if a == b or (a and b and strip(a, ()) == strip(b, ())):
        continue
    if a and b and strip(a, ('extKeys', 'keys')) == strip(b, ('extKeys', 'keys')) and 'ftv1' in (a.get('extKeys') or []):
        ftv1 += 1; continue
    print(k); print('   O:', f(a)); print('   K:', f(b))
print(f"\n{ftv1} differences only in the top-level extensions.ftv1 (Kotlin originals; off in all ports by project decision)")

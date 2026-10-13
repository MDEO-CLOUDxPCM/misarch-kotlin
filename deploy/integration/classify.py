#!/usr/bin/env python3
"""Classify the direct-probe differences of two diffprobe results into the accepted categories of
docs/INTEGRATION.md §4.2; prints the counts and every difference that fits no category."""
import json, sys
A = json.load(open(sys.argv[1]))['direct']; B = json.load(open(sys.argv[2]))['direct']
def clean(v, drop_loc=False, drop_ext=False, drop_st=False):
    v = {k: w for k, w in v.items() if k not in ('dataValue',) + (('extKeys', 'keys') if drop_ext else ())}
    errs = []
    for e in v.get('errors', []):
        e = dict(e)
        if drop_loc: e.pop('locations', None)
        if drop_st: e['extKeys'] = [x for x in e['extKeys'] if x != 'stacktrace']
        errs.append(e)
    v['errors'] = errs
    return v
cats = {}; other = []
for k in sorted(set(A) | set(B)):
    a, b = A.get(k), B.get(k)
    if a == b or clean(a) == clean(b): continue
    if clean(a, drop_ext=True) == clean(b, drop_ext=True): c = 'ftv1 only'
    elif '_entities' in k and clean(a, True, True) == clean(b, True, True): c = '_entities without locations (+ftv1)'
    elif clean(a, drop_st=True) == clean(b, drop_st=True): c = 'no stacktrace extension (TS)'
    elif k.split('/')[1] in ('syntax-error', 'validation:unknown-field') or k.endswith(':bad-id'): c = 'engine texts of request-level errors'
    elif k.startswith('media/getMedias'): c = 'media: object store present (original has none)'
    else: c = 'OTHER'; other.append((k, a, b))
    cats[c] = cats.get(c, 0) + 1
for c, n in sorted(cats.items(), key=lambda x: -x[1]): print(f"{n:4d}  {c}")
for k, a, b in other:
    print(k); print('   O:', json.dumps(clean(a))[:400]); print('   K:', json.dumps(clean(b))[:400])

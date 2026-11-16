import json, re, sys, time, unicodedata, urllib.request, urllib.parse
from concurrent.futures import ThreadPoolExecutor
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
def get(url):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=20) as r: return json.loads(r.read())
    except Exception as e: return None
def embed(pid):
    with urllib.request.urlopen(urllib.request.Request(f"https://open.spotify.com/embed/playlist/{pid}", headers={"User-Agent": UA}), timeout=20) as r: html = r.read().decode()
    d = json.loads(re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', html, re.S).group(1))
    out = []
    def f(e):
        if isinstance(e, dict):
            if isinstance(e.get("trackList"), list): out.extend(e["trackList"]); return
            for v in e.values(): f(v)
        elif isinstance(e, list):
            for v in e: f(v)
    f(d); return [(t["title"], t.get("subtitle",""), t.get("duration")) for t in out]
def search(q, need_preview):
    o = get("https://api.deezer.com/search?limit=10&q=" + urllib.parse.quote(q))
    if o is None: return None, False
    if "error" in o: return [], True
    return [t for t in o.get("data", []) if (t.get("preview") or not need_preview)], False
# ---- old
def old(t):
    title, artist, _ = t
    clean = re.sub(r"\s*[\(\[].*?[\)\]]", "", title); clean = re.sub(r"\s+-\s+.*$", "", clean).strip()
    first = re.split(r",|&| feat| ft\.", artist)[0].strip()
    r, q = search(f'artist:"{first}" track:"{clean}"', True)
    if r: return True, q
    r2, q2 = search(f"{clean} {first}", True)
    return bool(r2), q or q2
# ---- new (same logic as SongMatcher, simplified)
def norm(s):
    s = unicodedata.normalize("NFD", s.lower()); s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    s = s.replace("&", " and "); s = re.sub(r"[^a-z0-9\s]", " ", s); return re.sub(r"\s+", " ", s).strip()
def core(t):
    t = re.sub(r"(?i)\s*[\(\[][^\)\]]*(remaster|version|edit|mix|radio|mono|live|acoustic|deluxe|from |soundtrack)[^\)\]]*[\)\]]", "", t)
    t = re.sub(r"(?i)\s+-\s+.*(remaster|version|edit|mix|radio|mono|live|acoustic|deluxe|from |single|\d{4}).*$", "", t)
    t = re.sub(r"(?i)\s*[\(\[]?\s*(feat\.?|ft\.?|featuring|with)\s+[^\)\]]*[\)\]]?", "", t)
    return t.strip() or t
def sim(a, b):
    x, y = norm(a), norm(b)
    if not x or not y: return 0
    if x == y: return 1
    tx, ty = set(x.split()), set(y.split())
    jac = len(tx & ty) / len(tx | ty)
    import difflib
    return max(jac, difflib.SequenceMatcher(None, x, y).ratio())
def score(w, c):
    title = max(sim(w[0], c["title"]), sim(core(w[0]), core(c["title"])))
    wa = [norm(a) for a in re.split(r",|&| x | feat\.? | ft\.? ", w[1]) if a.strip()]
    art = max([sim(a, c["artist"]["name"]) for a in wa] or [0.6])
    sc = 0.6 * title + 0.4 * art
    if w[2] and c.get("duration"):
        d = abs(w[2] / 1000 - c["duration"]); sc += 0.05 if d <= 3 else 0 if d <= 10 else -0.08 if d <= 30 else -0.2
    return sc
def patient(q):
    for i in range(8):
        r, quota = search(q, False)
        if r is not None and not quota: return r
        time.sleep(0.9 + i * 0.6)
    return []
def new(w, relaxed=False):
    title = core(w[0]); arts = [a.strip() for a in re.split(r",|&|feat\.?|ft\.?", w[1]) if a.strip()]; first = arts[0] if arts else ""
    qs = [f'artist:"{first}" track:"{title}"', f"{title} {first}"] + ([f"{w[0]} {first}"] if title != w[0] else []) + [f'artist:"{a}" track:"{title}"' for a in arts[1:3]]
    if relaxed: qs += [f'track:"{title}"', title]
    best = 0; bt = None
    for q in qs:
        for c in patient(q):
            s = score(w, c)
            if s > best: best, bt = s, c
        if best >= 0.88: break
    return (bt, best) if best >= (0.6 if relaxed else 0.72) else (None, best)
for pid in sys.argv[1:]:
    tracks = embed(pid)
    print("playlist", pid, len(tracks), "tracks")
    t0 = time.time()
    with ThreadPoolExecutor(6) as ex: res = list(ex.map(old, tracks))
    print(f"OLD: found {sum(1 for f,_ in res if f)}/{len(tracks)}  quota-errors {sum(1 for _,q in res if q)}  in {time.time()-t0:.0f}s")
    time.sleep(6)
    t0 = time.time()
    with ThreadPoolExecutor(6) as ex: res2 = list(ex.map(new, tracks))
    miss = [i for i,(c,_) in enumerate(res2) if c is None]
    with ThreadPoolExecutor(6) as ex: res3 = list(ex.map(lambda i: new(tracks[i], True), miss))
    for i, r in zip(miss, res3): res2[i] = r
    found = sum(1 for c,_ in res2 if c)
    print(f"NEW: found {found}/{len(tracks)} in {time.time()-t0:.0f}s")
    for (w,(c,s)) in zip(tracks, res2):
        if c is None: print("  missing:", w[0], "-", w[1], f"best={s:.2f}")
        elif s < 0.8: print(f"  unsure {s:.2f}:", w[0], "-", w[1], "=>", c["title"], "-", c["artist"]["name"])

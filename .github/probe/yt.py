import json, re, sys, urllib.request
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
DUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
CK = "SOCS=CAI; CONSENT=YES+cb.20210328-17-p0.it+FX+000"
def get(url, ua=UA, data=None, headers=None):
    h = {"User-Agent": ua, "Accept-Language": "it-IT,it;q=0.9", "Cookie": CK}
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=20) as r: return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e: return e.code, e.read().decode("utf-8","replace")
def find(e, key, out):
    if isinstance(e, dict):
        for k, v in e.items():
            if k == key and isinstance(v, dict): out.append(v)
            else: find(v, key, out)
    elif isinstance(e, list):
        for x in e: find(x, key, out)
    return out
def text(o):
    if not isinstance(o, dict): return None
    return o.get("simpleText") or "".join(r.get("text","") for r in o.get("runs",[]))
lists = sys.argv[1:]
for L in lists:
    print("=====", L)
    for ua_name, ua in (("mobile", UA), ("desktop", DUA)):
        st, html = get(f"https://www.youtube.com/playlist?list={L}&hl=it&gl=IT", ua)
        m = re.search(r"ytInitialData\s*=\s*(\{.*?\});\s*</script>", html, re.S)
        m2 = re.search(r"ytInitialData\s*=\s*'(.*?)';", html, re.S)
        print(f"www {ua_name}: http {st} len {len(html)} initialData={'json' if m else ('escaped' if m2 else 'NONE')} consent={'consent.youtube' in html}")
        if m:
            d = json.loads(m.group(1))
            vids = find(d, "playlistVideoRenderer", [])
            lock = find(d, "lockupViewModel", [])
            pvm = find(d, "playlistVideoListRenderer", [])
            meta = find(d, "playlistMetadataRenderer", [])
            hdr = find(d, "pageHeaderViewModel", [])
            alerts = find(d, "alertRenderer", [])
            print("   items playlistVideoRenderer", len(vids), "lockup", len(lock), "listRenderer", len(pvm), "meta", [x.get("title") for x in meta][:1],
                  "alerts", [text(a.get("text")) for a in alerts][:2])
            for v in vids[:3]: print("   ->", text(v.get("title")), "|", text(v.get("shortBylineText")))
            keys = set()
            def walk(e, depth=0):
                if depth > 14: return
                if isinstance(e, dict):
                    for k, v in e.items():
                        if k.endswith("Renderer") or k.endswith("ViewModel"): keys.add(k)
                        walk(v, depth+1)
                elif isinstance(e, list):
                    for x in e: walk(x, depth+1)
            walk(d); print("   renderers:", sorted(keys)[:60])
    # music.youtube.com innertube
    body = json.dumps({"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20241030.01.00","hl":"it","gl":"IT"}},"browseId":"VL"+L}).encode()
    st, res = get("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", DUA, body, {"Content-Type":"application/json","Origin":"https://music.youtube.com","Referer":"https://music.youtube.com/"})
    print("ytm innertube: http", st, "len", len(res))
    try:
        d = json.loads(res)
        rows = find(d, "musicResponsiveListItemRenderer", [])
        print("   rows", len(rows))
        for r in rows[:4]:
            cols = [text(c.get("musicResponsiveListItemFlexColumnRenderer",{}).get("text")) for c in r.get("flexColumns",[])]
            print("   ->", cols)
        hdrs = find(d, "musicResponsiveHeaderRenderer", []) + find(d, "musicDetailHeaderRenderer", []) + find(d,"musicEditablePlaylistDetailHeaderRenderer",[])
        print("   header:", [text(h.get("title")) for h in hdrs][:2])
        cont = find(d, "continuationCommand", []) + find(d, "nextContinuationData", [])
        print("   continuation:", bool(cont))
    except Exception as e: print("   parse error", e, res[:300])

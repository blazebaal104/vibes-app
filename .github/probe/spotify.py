import json, re, sys, base64, urllib.request, urllib.parse
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
def get(url, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "it-IT,it;q=0.9", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=20) as r: return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e: return e.code, e.read().decode("utf-8", "replace")[:300]
    except Exception as e: return -1, str(e)
for pid in sys.argv[1:]:
    print("=====", pid)
    st, page = get(f"https://open.spotify.com/playlist/{pid}")
    print("page", st, len(page), "tracks uri", len(set(re.findall(r'spotify:track:([A-Za-z0-9]{22})', page))))
    for m in re.finditer(r'<script id="([^"]+)" type="([^"]+)"[^>]*>(.*?)</script>', page, re.S):
        sid, typ, body = m.group(1), m.group(2), m.group(3)
        print(" script", sid, typ, len(body))
        if sid in ("initialState", "appServerConfig", "session"):
            try:
                dec = base64.b64decode(body).decode("utf-8", "replace")
            except Exception: dec = body
            print("  tracks in", sid, len(set(re.findall(r'spotify:track:([A-Za-z0-9]{22})', dec))), "token?", '"accessToken"' in dec, dec[:300].replace("\n"," "))
    for u in ["https://open.spotify.com/get_access_token?reason=transport&productType=web_player",
              "https://open.spotify.com/api/token?reason=init&productType=web-player"]:
        st2, b = get(u); print("tok", u[:60], st2, b[:200])
    st3, b = get(f"https://open.spotify.com/embed/playlist/{pid}")
    m = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', b, re.S)
    if m:
        d = m.group(1); print("embed next keys has 'session'", '"session"' in d, "'token'", d.count("oken"))
        for k in re.findall(r'"([a-zA-Z]*[Tt]oken[a-zA-Z]*)"\s*:\s*"?([^",}]{0,40})', d)[:10]: print("  ", k)

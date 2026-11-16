import json, re, sys, urllib.request, urllib.parse
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
def get(url, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=20) as r: return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e: return e.code, e.read().decode("utf-8", "replace")[:300]
    except Exception as e: return -1, str(e)
for pid in sys.argv[1:]:
    print("=====", pid)
    st, html = get(f"https://open.spotify.com/embed/playlist/{pid}")
    m = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', html, re.S)
    if not m: print("embed", st, "no next data"); continue
    data = json.loads(m.group(1))
    s = json.dumps(data)
    tl = []
    def find(e):
        if isinstance(e, dict):
            if isinstance(e.get("trackList"), list): tl.append(e); return
            for v in e.values(): find(v)
        elif isinstance(e, list):
            for v in e: find(v)
    find(data)
    ent = tl[0] if tl else {}
    print("embed tracks", len(ent.get("trackList", [])), "keys", list(ent.keys())[:20])
    if ent.get("trackList"): print("sample item", json.dumps(ent["trackList"][0])[:600])
    tok = re.search(r'"accessToken":"([^"]+)"', s)
    print("token?", bool(tok))
    if tok:
        t = tok.group(1)
        for u in [f"https://api.spotify.com/v1/playlists/{pid}/tracks?offset=100&limit=100&fields=total,items(track(name,artists(name),external_ids,duration_ms))",
                  f"https://api.spotify.com/v1/playlists/{pid}?fields=tracks.total"]:
            st2, body = get(u, {"Authorization": "Bearer " + t})
            print("api", st2, body[:400])
        # partner graphql (web player) with the embed token
        q = {"operationName":"fetchPlaylist","variables":json.dumps({"uri":f"spotify:playlist:{pid}","offset":100,"limit":100}),
             "extensions":json.dumps({"persistedQuery":{"version":1,"sha256Hash":"19ff1327c29e99c208c86d7a9d8f1929cfdf3d3202a0ff4253c821f1901aa94d"}})}
        st3, body = get("https://api-partner.spotify.com/pathfinder/v1/query?" + urllib.parse.urlencode(q), {"Authorization": "Bearer " + t, "App-Platform": "WebPlayer"})
        print("partner", st3, body[:400])
    st4, page = get(f"https://open.spotify.com/playlist/{pid}")
    print("page", st4, len(page), "music:song metas", len(re.findall(r'music:song"', page)), "tracks links", len(set(re.findall(r'/track/([A-Za-z0-9]{22})', page))))
    st5, page2 = get(f"https://open.spotify.com/embed/playlist/{pid}?offset=100")
    m2 = re.search(r'"trackList":\[(.*?)\]', page2)
    print("embed offset param", st5, page2.count('"uri":"spotify:track'))

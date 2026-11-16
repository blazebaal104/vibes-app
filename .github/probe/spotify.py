import json, re, sys, urllib.request
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
for tid in sys.argv[1:]:
    req = urllib.request.Request(f"https://open.spotify.com/embed/track/{tid}", headers={"User-Agent": UA})
    html = urllib.request.urlopen(req, timeout=20).read().decode()
    print("size", len(html))
    d = json.loads(re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', html, re.S).group(1))
    def find(e, path=""):
        if isinstance(e, dict):
            if str(e.get("uri", "")).startswith("spotify:track:") and ("name" in e or "title" in e):
                print("FOUND at", path, json.dumps({k: v for k, v in e.items() if k not in ("visualIdentity", "coverArt", "audioPreview")})[:700]); return True
            return any(find(v, path + "/" + k) for k, v in e.items())
        if isinstance(e, list): return any(find(v, path + "[]") for v in e)
        return False
    find(d)
    try:
        o = urllib.request.urlopen(urllib.request.Request(f"https://open.spotify.com/oembed?url=https://open.spotify.com/track/{tid}", headers={"User-Agent": UA}), timeout=20).read().decode()
        print("oembed", o[:300])
    except Exception as e: print("oembed err", e)

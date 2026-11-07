"""
Builds the Vibes sample catalog from Freesound (runs in GitHub Actions with the FREESOUND_KEY secret).
Only musical, usable sounds: melodic loops, chords, vocal chops, pads, instruments.
No drum kits / 808 / one-hit percussion / screams / field recordings / noise.
Licences: CC0 and CC-BY only (free to use in your own tracks; CC-BY needs credit, shown in the app).
The app downloads this one JSON (fast, searchable offline); audio = Freesound CDN previews.
"""
import json, os, re, sys, time, urllib.parse, urllib.request

KEY = os.environ["FREESOUND_KEY"].strip()
OUT = sys.argv[1] if len(sys.argv) > 1 else "catalog.json"

CATS = {
    "Piano": ["piano loop", "piano chords", "rhodes", "electric piano", "piano melody"],
    "Chitarra": ["guitar loop", "acoustic guitar melody", "guitar riff", "guitar chords", "nylon guitar"],
    "Voci": ["vocal chop", "vocal sample", "vocal phrase", "choir", "humming"],
    "Pad": ["pad", "ambient pad", "synth pad", "atmosphere pad", "texture drone"],
    "Synth": ["synth melody", "synth loop", "arpeggio", "lead synth", "synthwave"],
    "Archi": ["strings loop", "violin melody", "cello", "string ensemble", "pizzicato"],
    "Fiati": ["trumpet", "saxophone", "flute melody", "brass section", "clarinet"],
    "Soul & Jazz": ["soul sample", "jazz chords", "jazz loop", "gospel organ", "funk guitar"],
    "Lo-fi": ["lofi loop", "lo-fi melody", "lofi chords", "dusty sample", "vinyl melody"],
    "Bassi": ["bass loop", "bassline", "synth bass", "upright bass", "bass guitar riff"],
    "Plucks & Bells": ["bells melody", "pluck", "kalimba", "marimba", "music box"],
    "Cinematic": ["orchestral", "cinematic", "epic strings", "piano ambient", "film score"],
}
BANNED = re.compile(r"(?i)\b(808|kick|snare|hi-?hat|hats|drum|drums|drumloop|percussion|clap|cymbal|tom|beatbox|scream|shout|yell|noise|glitch|fx|sfx|foley|field-recording|ambience|footstep|explosion|horror|creepy|speech|talking|voice-over|asmr|bird|rain|wind|traffic)\b")
FIELDS = "id,name,username,license,tags,duration,previews,avg_rating,num_downloads,num_ratings,ac_analysis"

def api(path, params):
    params = dict(params, token=KEY)
    url = "https://freesound.org/apiv2/" + path + "?" + urllib.parse.urlencode(params)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "VibesCatalog/1.0"}), timeout=40) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            if e.code == 429: time.sleep(20); continue
            raise
        except Exception:
            time.sleep(5)
    return {"results": []}

def lic(l):
    l = l or ""
    if "/zero/" in l or "publicdomain" in l: return "CC0"
    if "/by/" in l: return "CC-BY"
    return None  # NC / sampling+ / others: excluded

items, seen = [], set()
for cat, queries in CATS.items():
    got = 0
    for q in queries:
        for page in (1, 2):
            res = api("search/text/", {
                "query": q, "page": page, "page_size": 80, "fields": FIELDS, "sort": "downloads_desc",
                "filter": 'duration:[1.5 TO 45] license:("Creative Commons 0" OR "Attribution")',
            })
            time.sleep(1.1)
            for s in res.get("results", []):
                if s["id"] in seen: continue
                L = lic(s.get("license"))
                if not L: continue
                text = s.get("name", "") + " " + " ".join(s.get("tags", []))
                if BANNED.search(text): continue
                if (s.get("num_ratings") or 0) >= 2 and (s.get("avg_rating") or 0) < 3: continue
                prev = (s.get("previews") or {}).get("preview-hq-mp3")
                if not prev: continue
                ac = s.get("ac_analysis") or {}
                bpm = ac.get("ac_tempo")
                m = re.search(r"(?i)(\d{2,3})\s?bpm", s.get("name", ""))
                if m: bpm = int(m.group(1))
                bpm = int(round(bpm)) if isinstance(bpm, (int, float)) and 50 <= bpm <= 200 else None
                key = ac.get("ac_tonality")  # e.g. "C minor"
                if isinstance(key, str) and key:
                    k, _, mode = key.partition(" ")
                    key = k + ("m" if mode.startswith("min") else "")
                else: key = None
                tags = [t for t in s.get("tags", []) if len(t) < 18][:8]
                name = re.sub(r"\.(wav|mp3|aif+|flac|ogg)$", "", s.get("name", ""), flags=re.I).replace("_", " ").strip()
                seen.add(s["id"])
                items.append({
                    "id": f"fs:{s['id']}", "n": name[:80], "u": s.get("username", ""), "l": L, "c": cat,
                    "t": tags, "d": round(s.get("duration", 0), 1), "b": bpm, "k": key, "p": prev,
                    "r": round(s.get("avg_rating") or 0, 1), "dl": s.get("num_downloads") or 0,
                    "loop": any(t in ("loop", "loops", "loopable") for t in s.get("tags", [])),
                })
                got += 1
            if len(res.get("results", [])) < 80: break
    print(f"{cat}: {got}", flush=True)

items.sort(key=lambda x: -x["dl"])
json.dump({"version": int(time.time()), "source": "freesound.org", "items": items}, open(OUT, "w"), ensure_ascii=False, separators=(",", ":"))
print("total", len(items), "bytes", os.path.getsize(OUT))
if len(items) < 300: sys.exit("too few samples, not publishing")

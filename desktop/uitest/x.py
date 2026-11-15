#!/usr/bin/env python3
"""UI driver for the desktop app on Xvfb (CI): click X Y | type TEXT | key NAME | ctrl K | scroll X Y N | sleep S | shot NAME
login: types the CI test account (env VIBES_EMAIL / VIBES_PASSWORD) in the login form."""
import sys, time, subprocess, os
from Xlib import X, XK, display
from Xlib.ext import xtest
d = display.Display(os.environ.get("DISPLAY", ":99"))
def move(x, y):
    xtest.fake_input(d, X.MotionNotify, x=int(x), y=int(y)); d.sync()
def click(x, y, b=1):
    move(x, y); time.sleep(0.05)
    xtest.fake_input(d, X.ButtonPress, b); d.sync(); time.sleep(0.05)
    xtest.fake_input(d, X.ButtonRelease, b); d.sync()
def key(name, mods=()):
    ks = XK.string_to_keysym(name)
    kc = d.keysym_to_keycode(ks)
    mcs = [d.keysym_to_keycode(XK.string_to_keysym(m)) for m in mods]
    for m in mcs: xtest.fake_input(d, X.KeyPress, m)
    xtest.fake_input(d, X.KeyPress, kc); d.sync(); time.sleep(0.02)
    xtest.fake_input(d, X.KeyRelease, kc)
    for m in mcs: xtest.fake_input(d, X.KeyRelease, m)
    d.sync()
special = {' ': 'space', '@': 'at', '.': 'period', '-': 'minus', '_': 'underscore', '!': 'exclam', ':': 'colon', '/': 'slash', ',': 'comma'}
def typ(s):
    for ch in s:
        name = special.get(ch, ch)
        shift = ch.isupper() or ch in '@_!:'
        key(name, ('Shift_L',) if shift else ())
        time.sleep(0.02)
a = sys.argv[1:]
while a:
    c = a.pop(0)
    if c == 'click': click(a.pop(0), a.pop(0))
    elif c == 'rclick': click(a.pop(0), a.pop(0), 3)
    elif c == 'move': move(a.pop(0), a.pop(0))
    elif c == 'type': typ(a.pop(0))
    elif c == 'key': key(a.pop(0))
    elif c == 'ctrl': key(a.pop(0), ('Control_L',))
    elif c == 'scroll':
        x, y, n = int(a.pop(0)), int(a.pop(0)), int(a.pop(0)); move(x, y)
        b = 5 if n > 0 else 4
        for _ in range(abs(n)):
            xtest.fake_input(d, X.ButtonPress, b); xtest.fake_input(d, X.ButtonRelease, b); d.sync(); time.sleep(0.03)
    elif c == 'sleep': time.sleep(float(a.pop(0)))
    elif c == 'shot': subprocess.run(['import', '-window', 'root', os.path.join(os.environ.get('SHOTS', '.'), a.pop(0) + '.png')])
    elif c == 'login':
        click(720, 404); typ(os.environ["VIBES_EMAIL"]); click(720, 480); typ(os.environ["VIBES_PASSWORD"]); click(720, 556)

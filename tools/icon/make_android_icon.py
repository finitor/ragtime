#!/usr/bin/env python3
"""Makes the Android app's icons from Ragtime's icon, app/res/ragtime.svg
(drawn by make_leaf_svg.py): the same leaf going digital, as vector
drawables under android/app/src/main/res/.

  drawable/ic_launcher_foreground.xml   the leaf, its stem and its blocks
  drawable/ic_launcher_monochrome.xml   the same in one colour, for the
                                        themed icons of Android 13 and later
  drawable/ic_stat_ragtime.xml          the whole leaf, small and white, for
                                        notifications
  values/ic_launcher_colors.xml         the cover's cream, the background

An Android launcher cuts every icon to its own shape (a circle, a
squircle...), so the desktop icon's rounded square and red rim cannot come
along: the leaf sits on the cream alone, inside the 66 dp circle every
launcher shows. Run it after changing the SVG, and commit what it writes.
It needs nothing but Python.

    tools/icon/make_android_icon.py [--preview FILE.svg]
"""

import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = os.path.join(HERE, '..', '..', 'app', 'res', 'ragtime.svg')
RES = os.path.join(HERE, '..', '..', 'android', 'app', 'src', 'main', 'res')

CANVAS = 108.0      # an adaptive icon's layer, dp
SAFE = 66.0         # the circle no launcher cuts into
FIT = 63.0          # the drawing's reach across that circle, dp

HEADER = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Made by tools/icon/make_android_icon.py from app/res/ragtime.svg; edit
     that script or the SVG's own, not this. -->
'''


def read_source():
    svg = open(SOURCE).read()
    place = re.search(r'transform="translate\(([\d.]+) ([\d.]+)\) rotate\(([\d.]+)\) scale\(([\d.]+)\)"', svg)
    tx, ty, degrees, scale = (float(g) for g in place.groups())
    leaf = re.search(r'<path fill="(#[0-9a-f]+)"[^>]* d="(M[^"]+Z)"', svg)
    green = leaf.group(1)
    points = [tuple(float(v) for v in p.split()) for p in re.split(r'\s*[ML]\s*', leaf.group(2).rstrip(' Z')) if p]
    stem = re.search(r'stroke-width="([\d.]+)"[^>]* d="M([-\d. ]+) C([-\d. ]+)"', svg)
    stem_width = float(stem.group(1))
    stem_points = [float(v) for v in (stem.group(2) + ' ' + stem.group(3)).split()]
    stem_points = list(zip(stem_points[0::2], stem_points[1::2]))
    split = float(re.search(r'<clipPath id="smooth"><rect width="([\d.]+)"', svg).group(1))
    cream = re.search(r'<rect x="8" y="8" width="240" height="240" rx="44" fill="(#[0-9a-f]+)"', svg).group(1)
    blocks = [(float(x), float(y), float(w), float(h), colour) for x, y, w, h, colour in re.findall(
        r'<rect x="([\d.]+)" y="([\d.]+)" width="([\d.]+)" height="([\d.]+)" fill="(#[0-9a-f]+)"/>', svg)]

    turn = math.radians(degrees)

    def placed(p):
        x, y = p[0] * scale, p[1] * scale
        return (tx + x * math.cos(turn) - y * math.sin(turn), ty + x * math.sin(turn) + y * math.cos(turn))

    return {'leaf': [placed(p) for p in points], 'stem': [placed(p) for p in stem_points],
            'stem_width': stem_width * scale, 'split': split, 'blocks': blocks, 'green': green, 'cream': cream}


def clipped(polygon, limit):
    """The polygon left of x = limit (Sutherland-Hodgman, one edge)."""
    out = []
    for i, a in enumerate(polygon):
        b = polygon[(i + 1) % len(polygon)]
        if a[0] <= limit:
            out.append(a)
        if (a[0] <= limit) != (b[0] <= limit):
            t = (limit - a[0]) / (b[0] - a[0])
            out.append((limit, a[1] + t * (b[1] - a[1])))
    return out


def number(v):
    return ('%.2f' % v).rstrip('0').rstrip('.')


def path(points):
    return 'M' + ' L'.join('%s,%s' % (number(x), number(y)) for x, y in points) + ' Z'


def main():
    art = read_source()
    smooth = clipped(art['leaf'], art['split'])
    # The drawing's middle and reach: the circle about it that holds the
    # leaf, the stem's end and every block.
    reach = smooth + art['stem'] + [(x + dx, y + dy) for x, y, w, h, _ in art['blocks'] for dx in (0, w) for dy in (0, h)]
    cx = (min(p[0] for p in reach) + max(p[0] for p in reach)) / 2
    cy = (min(p[1] for p in reach) + max(p[1] for p in reach)) / 2
    radius = max(math.hypot(p[0] - cx, p[1] - cy) for p in reach)

    def fitted(size, across):
        k = across / (2 * radius)
        return k, (lambda p: ((p[0] - cx) * k + size / 2, (p[1] - cy) * k + size / 2))

    def stem_path(to):
        a, b, c, d = (to(p) for p in art['stem'])
        return 'M%s,%s C%s,%s %s,%s %s,%s' % tuple(number(v) for p in (a, b, c, d) for v in p)

    def launcher(one_colour):
        k, to = fitted(CANVAS, FIT)
        out = [HEADER + '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
               '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">']
        green = '#000000' if one_colour else art['green']
        out.append('    <path android:fillColor="%s" android:pathData="%s"/>' % (green, path([to(p) for p in smooth])))
        out.append('    <path android:strokeColor="%s" android:strokeWidth="%s" android:strokeLineCap="round" android:pathData="%s"/>'
                   % (green, number(art['stem_width'] * k), stem_path(to)))
        for x, y, w, h, colour in art['blocks']:
            x0, y0 = to((x, y))
            out.append('    <path android:fillColor="%s" android:pathData="M%s,%s h%s v%s h-%s Z"/>'
                       % ('#000000' if one_colour else colour, number(x0), number(y0), number(w * k), number(h * k), number(w * k)))
        out.append('</vector>')
        return '\n'.join(out) + '\n'

    def status():
        # The whole leaf, not going digital: at a status bar's size the
        # blocks would be dust. White: Android tints it.
        whole = art['leaf'] + art['stem']
        wx = (min(p[0] for p in whole) + max(p[0] for p in whole)) / 2
        wy = (min(p[1] for p in whole) + max(p[1] for p in whole)) / 2
        k = 22.0 / max(max(p[0] for p in whole) - min(p[0] for p in whole), max(p[1] for p in whole) - min(p[1] for p in whole))

        def to(p):
            return ((p[0] - wx) * k + 12, (p[1] - wy) * k + 12)

        return (HEADER + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
                '    <path android:fillColor="#ffffff" android:pathData="%s"/>\n'
                '    <path android:strokeColor="#ffffff" android:strokeWidth="%s" android:strokeLineCap="round" android:pathData="%s"/>\n'
                '</vector>\n') % (path([to(p) for p in art['leaf']]), number(max(1.2, art['stem_width'] * k)), stem_path(to))

    files = {
        'drawable/ic_launcher_foreground.xml': launcher(False),
        'drawable/ic_launcher_monochrome.xml': launcher(True),
        'drawable/ic_stat_ragtime.xml': status(),
        'values/ic_launcher_colors.xml': HEADER + '<resources>\n    <color name="ic_launcher_background">%s</color>\n</resources>\n' % art['cream'],
    }
    for name, text in files.items():
        target = os.path.join(RES, name)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, 'w') as f:
            f.write(text)

    if '--preview' in sys.argv:
        # The launcher icon as a circle and as a squircle would cut it, with
        # the safe circle drawn in, for a look before building.
        k, to = fitted(CANVAS, FIT)
        shapes = ['<path fill="%s" d="%s"/>' % (art['green'], path([to(p) for p in smooth])),
                  '<path fill="none" stroke="%s" stroke-width="%s" stroke-linecap="round" d="%s"/>'
                  % (art['green'], number(art['stem_width'] * k), stem_path(to).replace(',', ' '))]
        for x, y, w, h, colour in art['blocks']:
            x0, y0 = to((x, y))
            shapes.append('<rect x="%s" y="%s" width="%s" height="%s" fill="%s"/>' % (number(x0), number(y0), number(w * k), number(h * k), colour))
        art_svg = '\n'.join(shapes)
        svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="640" height="320" viewBox="0 0 216 108">'
               '<rect width="216" height="108" fill="#888"/>'
               '<defs><clipPath id="c"><circle cx="54" cy="54" r="36"/></clipPath>'
               '<clipPath id="s"><rect x="18" y="18" width="72" height="72" rx="22"/></clipPath></defs>'
               '<g clip-path="url(#c)"><rect width="108" height="108" fill="%s"/>%s</g>'
               '<circle cx="54" cy="54" r="33" fill="none" stroke="#f00" stroke-width="0.3"/>'
               '<g transform="translate(108 0)"><g clip-path="url(#s)"><rect width="108" height="108" fill="%s"/>%s</g></g>'
               '</svg>') % (art['cream'], art_svg, art['cream'], art_svg)
        with open(sys.argv[sys.argv.index('--preview') + 1], 'w') as f:
            f.write(svg)


if __name__ == '__main__':
    main()

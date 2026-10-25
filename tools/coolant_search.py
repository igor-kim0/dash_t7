#!/usr/bin/env python3
"""
Поиск температуры ОЖ в записи приборки (ClusterRecorder, cluster_rec_*.txt).

Эталон — °C OBD-сканера из строк REF («elm_coolant=82.0»), а если сканера
в записи нет — метки пользователя («### МЕТКА: ож 65»). Кандидаты — каждый
байт (u8) и каждое 16-битное слово (u16 LE, со всех смещений) всех
массивов data PPS, а также числовые поля прочих json-атрибутов
(«thermal:json:{"THS1": 9}»).

Для каждого кандидата берём его последнее значение на момент каждой
эталонной точки и считаем:
  r      — корреляция Пирсона с °C (линейная зависимость);
  rho    — Спирмена (монотонная, для нелинейной шкалы вроде стрелки);
  r_t    — корреляция со временем: при прогреве °C растут со временем,
           и счётчики/одометр тоже «коррелируют» — у них r_t ≈ rho;
  fit    — °C ≈ a·raw + b и средняя ошибка (rmse) этой прямой.
Лучший кандидат — с |rho| ≈ 1, малой rmse, правдоподобным a/b (например
×1−40, ×0.75−48, /10) и rho заметно выше r_t. Надёжнее всего запись,
где ОЖ и росла, и падала (прогрев, потом стоянка с заглушенным).

  tools/coolant_search.py logs/.../cluster_rec_*.txt [--ref elm_coolant] [--top 30]
"""

import argparse
import json
import math
import re
import sys

TIME = re.compile(r"^(\d\d):(\d\d):(\d\d)\.(\d{3}) (.*)$")
OBJ = re.compile(r"^\[([^\]]*)\] (\w+):json:(.*)$")
MARK = re.compile(r"### МЕТКА:.*?\b(?:ож|ОЖ|Ож|вода)\s*(-?\d+(?:[.,]\d+)?)")


def seconds(m):
    h, mi, s, ms = (int(x) for x in m.groups()[:4])
    return h * 3600 + mi * 60 + s + ms / 1000


def candidates(obj, attr, body):
    """(имя, значение) из одной строки PPS."""
    try:
        js = json.loads(body)
    except ValueError:
        return
    if isinstance(js, dict) and isinstance(js.get("data"), list):
        data = js["data"]
        if all(isinstance(v, int) for v in data):
            for i, v in enumerate(data):
                yield f"{obj}.{attr} b{i}", v
            for i in range(len(data) - 1):
                yield f"{obj}.{attr} w{i}", data[i] | (data[i + 1] << 8)
        return
    if isinstance(js, dict):
        for k, v in js.items():
            if isinstance(v, (int, float)) and not isinstance(v, bool):
                yield f"{obj}.{attr}.{k}", v


def parse(path, ref_key):
    series = {}   # имя -> [(t, v)]
    refs = []     # (t, °C)
    marks = []
    day = 0.0
    last_t = None
    ref_re = re.compile(r"\b" + re.escape(ref_key) + r"=(-?\d+(?:\.\d+)?)")
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = TIME.match(line.rstrip("\n"))
            if not m:
                continue
            t = seconds(m)
            if last_t is not None and t + day < last_t - 3600:
                day += 86400  # запись через полночь
            t += day
            last_t = t
            rest = m.group(5)
            if rest.startswith("REF"):
                r = ref_re.search(rest)
                if r:
                    refs.append((t, float(r.group(1))))
                continue
            mk = MARK.search(rest)
            if mk:
                marks.append((t, float(mk.group(1).replace(",", "."))))
                continue
            o = OBJ.match(rest)
            if o:
                for name, v in candidates(*o.groups()):
                    series.setdefault(name, []).append((t, v))
    return series, refs, marks


def at(points, t):
    """Последнее значение не позже t (points отсортированы по времени)."""
    lo, hi = 0, len(points)
    while lo < hi:
        mid = (lo + hi) // 2
        if points[mid][0] <= t:
            lo = mid + 1
        else:
            hi = mid
    return points[lo - 1][1] if lo else None


def pearson(xs, ys):
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    syy = sum((y - my) ** 2 for y in ys)
    if sxx == 0 or syy == 0:
        return 0.0
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / math.sqrt(sxx * syy)


def ranks(vs):
    order = sorted(range(len(vs)), key=lambda i: vs[i])
    r = [0.0] * len(vs)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and vs[order[j + 1]] == vs[order[i]]:
            j += 1
        for k in range(i, j + 1):
            r[order[k]] = (i + j) / 2
        i = j + 1
    return r


def fit(xs, ys):
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    a = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx
    b = my - a * mx
    rmse = math.sqrt(sum((a * x + b - y) ** 2 for x, y in zip(xs, ys)) / n)
    return a, b, rmse


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="+")
    ap.add_argument("--ref", default="elm_coolant", help="ключ эталона в строках REF")
    ap.add_argument("--top", type=int, default=30)
    ap.add_argument("--min-distinct", type=int, default=4,
                    help="сколько разных значений должно быть у кандидата")
    args = ap.parse_args()

    for path in args.files:
        series, refs, marks = parse(path, args.ref)
        source = f"REF {args.ref}"
        if len(refs) < 5:
            refs, source = marks, "метки «ож N»"
        print(f"=== {path}")
        if len(refs) < 3:
            print("  эталона нет (ни REF elm_coolant, ни меток «ож N»)")
            continue
        temps = [c for _, c in refs]
        print(f"  эталон: {source}, точек {len(refs)}, °C {min(temps):.0f}..{max(temps):.0f}, "
              f"кандидатов {len(series)}")
        rows = []
        for name, pts in series.items():
            pairs = [(at(pts, t), c, t) for t, c in refs]
            pairs = [p for p in pairs if p[0] is not None]
            if len(pairs) < max(3, len(refs) // 2):
                continue
            xs = [p[0] for p in pairs]
            if len(set(xs)) < args.min_distinct:
                continue
            ys = [p[1] for p in pairs]
            ts = [p[2] for p in pairs]
            rho = pearson(ranks(xs), ranks(ys))
            r = pearson(xs, ys)
            r_t = pearson(ranks(xs), ranks(ts))
            a, b, rmse = fit(xs, ys)
            rows.append((abs(rho), rho, r, r_t, a, b, rmse, name, min(xs), max(xs), len(set(xs))))
        rows.sort(key=lambda x: (-x[0], x[6]))
        print(f"  {'кандидат':28} {'rho':>6} {'r':>6} {'r_t':>6}   °C ≈ a·raw + b        rmse   raw")
        for _, rho, r, r_t, a, b, rmse, name, lo, hi, nd in rows[:args.top]:
            print(f"  {name:28} {rho:6.3f} {r:6.3f} {r_t:6.3f}   {a:9.4f}·raw {b:+8.2f}  {rmse:6.2f}   "
                  f"{lo}..{hi} ({nd} зн.)")


if __name__ == "__main__":
    sys.exit(main())

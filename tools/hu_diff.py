#!/usr/bin/env python3
"""Сравнение срезов из tools/hu_dump.sh.

Для каждой пары соседних срезов (по имени каталога) печатает, какие ячейки
шины Desay (group,id) и какие строки Vehicle HAL изменились. Так по сценарию
«включил A/C» видно, какой байт за это отвечает.

    tools/hu_diff.py logs/hu_dump_20261012_140000            # все шаги
    tools/hu_diff.py logs/hu_dump_... --only 10_ac_on         # один шаг
    tools/hu_diff.py logs/hu_dump_... --series                # watch: как менялись
                                                              # ячейки во времени
"""
import argparse
import re
import sys
from pathlib import Path

DESAY_LINE = re.compile(r"^(\d+),(\d+): ?(.*)$")


def load_desay(path: Path) -> dict:
    cells = {}
    if not path.exists():
        return cells
    for line in path.read_text(errors="replace").splitlines():
        m = DESAY_LINE.match(line)
        if m:
            cells[(int(m.group(1)), int(m.group(2)))] = m.group(3)
    return cells


def load_vhal(snap: Path) -> dict:
    """Строки lshal debug / dumpsys car_service, ключ — файл + номер строки без значения.

    Формат дампа VHAL у вендоров разный, поэтому сравниваем строки целиком:
    ключ — начало строки до первого числа после «value»/«=».
    """
    lines = {}
    for f in sorted(snap.glob("lshal_debug_*.txt")) + [snap / "dumpsys_car_service.txt"]:
        if not f.exists():
            continue
        for i, line in enumerate(f.read_text(errors="replace").splitlines()):
            key = re.split(r"(value|Value|=)", line, maxsplit=1)[0].strip() or f"#{i}"
            lines[(f.name, key)] = line.strip()
    return lines


def snaps(root: Path) -> list:
    d = root / "snapshots"
    if not d.is_dir():
        sys.exit(f"нет каталога {d}")
    return sorted(p for p in d.iterdir() if p.is_dir())


def diff_pair(prev: Path, cur: Path) -> None:
    a, b = load_desay(prev / "desay.txt"), load_desay(cur / "desay.txt")
    print(f"\n=== {prev.name} → {cur.name}")
    changed = [k for k in sorted(set(a) | set(b)) if a.get(k) != b.get(k)]
    for k in changed:
        print(f"  desay {k[0]:>2},{k[1]:<3}  {a.get(k, '—'):<24} → {b.get(k, '—')}")
    va, vb = load_vhal(prev), load_vhal(cur)
    vchanged = [k for k in sorted(set(va) | set(vb)) if va.get(k) != vb.get(k)]
    for k in vchanged[:200]:
        print(f"  vhal  {k[0]}: {va.get(k, '—')}\n        → {vb.get(k, '—')}")
    if len(vchanged) > 200:
        print(f"  … ещё {len(vchanged) - 200} строк VHAL")
    if not changed and not vchanged:
        print("  (без изменений)")


def series(all_snaps: list) -> None:
    """Ячейки Desay, которые менялись за время watch, со всеми значениями."""
    data = [(s.name, load_desay(s / "desay.txt")) for s in all_snaps]
    keys = sorted({k for _, d in data for k in d})
    for k in keys:
        values = [d.get(k, "—") for _, d in data]
        if len(set(values)) > 1:
            print(f"desay {k[0]},{k[1]}: " + " | ".join(values))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("dump", type=Path)
    ap.add_argument("--only", help="показать только переход к этому срезу")
    ap.add_argument("--series", action="store_true", help="режим watch: значения во времени")
    args = ap.parse_args()
    all_snaps = snaps(args.dump)
    if args.series:
        series(all_snaps)
        return
    for prev, cur in zip(all_snaps, all_snaps[1:]):
        if args.only and cur.name != args.only:
            continue
        diff_pair(prev, cur)


if __name__ == "__main__":
    main()

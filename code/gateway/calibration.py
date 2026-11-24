"""Calibration helper: consumes actual supplied measurements; never fabricates them."""
import argparse
import csv
import json
import math
from pathlib import Path


def calibrate(measurements):
    if len(measurements) < 2 or not any(grams == 0 for _, grams in measurements):
        raise ValueError('At least two points, including a zero-mass tare, are required')
    if any(not math.isfinite(raw) or not math.isfinite(grams) or grams < 0 for raw, grams in measurements):
        raise ValueError('Non-finite readings or negative reference mass')
    n = len(measurements)
    x = sum(raw for raw, _ in measurements) / n
    y = sum(grams for _, grams in measurements) / n
    variance = sum((raw - x) ** 2 for raw, _ in measurements)
    if variance == 0:
        raise ValueError('No measurable scale variation')
    scale = sum((raw - x) * (grams - y) for raw, grams in measurements) / variance
    if scale == 0:
        raise ValueError('Reference masses do not vary')
    offset = y - scale * x
    residual = math.sqrt(sum((scale * raw + offset - grams) ** 2 for raw, grams in measurements) / n)
    return dict(gramsPerRaw=scale, offsetGrams=offset, residualRmsGrams=residual, points=n,
                provenance='supplied measurements; physical validation remains the operator responsibility')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('csv', help='Columns raw,grams; include tare at grams=0')
    parser.add_argument('output')
    args = parser.parse_args()
    with open(args.csv, newline='', encoding='utf-8') as source:
        points = [(float(row['raw']), float(row['grams'])) for row in csv.DictReader(source)]
    Path(args.output).write_text(json.dumps(calibrate(points), indent=2) + '\n', encoding='utf-8')

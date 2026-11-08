"""Prepare finite Anemone equations; this script is never called by the runtime."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def generate():
    policy_bytes = (ROOT / 'config/temperature-policy.json').read_bytes()
    policy = json.loads(policy_bytes)
    low, high = round(policy['min'] * 10), round(policy['max'] * 10)
    hysteresis = round(policy.get('hysteresis', 0) * 10)
    bands = [(round(b['from'] * 10), b['power']) for b in policy['bands']]
    if not (low < high and 0 <= hysteresis <= 20 and bands[0][0] == low
            and all(bands[i][0] < bands[i + 1][0] for i in range(len(bands) - 1))):
        raise ValueError('Invalid thermal policy')
    powers = [power for _, power in bands]
    lines = ['set Temperatures={' + ','.join(f't{q}' for q in range(low, high + 1)) + '}.',
             'Powers={' + ','.join(f'p{p}' for p in powers) + '}.', 'eqn']
    for q in range(low, high + 1):
        nominal = max(i for i, (threshold, _) in enumerate(bands) if q >= threshold)
        lines.append(f'fan(t{q})={powers[nominal]}.')
        for previous, power in enumerate(powers):
            chosen = previous if nominal < previous and q >= bands[previous][0] - hysteresis else nominal
            lines.append(f'fan(t{q},p{power})={powers[chosen]}.')
    definitions = ('\n'.join(lines) + '\n').encode('utf-8')
    metadata = (f'minTenths={low}\nmaxTenths={high}\ninitialTenths={round(policy["initial"] * 10)}\n'
                f'policySha256={hashlib.sha256(policy_bytes).hexdigest()}\n'
                f'definitionsSha256={hashlib.sha256(definitions).hexdigest()}\n').encode('utf-8')
    agent = b'sum q in Temperatures: get(temperature(q));tell(temperature_current(q));sum p in Powers: get(previous(p));tell(ventilation(fan(q,p)))\n'
    return {'definitions.anemone': definitions, 'metadata.properties': metadata, 'regulation.anemone': agent}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for name, content in generate().items():
        path = ROOT / 'anemone-scenarios/temperature' / name
        if args.check:
            if path.read_bytes() != content:
                raise SystemExit('Regenerate thermal scenario: ' + name)
        else:
            path.write_bytes(content)
    print('Thermal scenario: deterministic equations and hashes OK')

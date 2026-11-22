#!/usr/bin/env python3
"""Summarize complete P33 measurements; never infer test success or a timing SLA."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import statistics
import sys


ROOT = Path(__file__).resolve().parents[3]
DEFAULT_INPUT = ROOT / 'test-artifacts/full-audit/s1/notice-pagination'
PROBES = {
    'uniform-5001': ('first', 'home-oldest'),
    'single-hot-100000': ('first-including-hot-count', 'after-hot', 'oldest'),
    'sparse-self': ('self-all-modules', 'self-owner-filter', 'self-module-filter',
                    'all-current-owner', 'no-modules', 'anonymous'),
    'empty-headers-100000': ('first', 'oldest'),
    'equal-timestamps': ('newest', 'oldest'),
    'deep-tail-5001': ('newest-tail', 'newest-empty', 'oldest-tail', 'oldest-empty'),
}
VARIANTS = ('production-group-count', 'group-count-after-page', 'visible-head-lateral')
ROUNDS = 3


def require(condition, message):
    if not condition:
        raise ValueError(message)


def number(value):
    require(type(value) in (int, float) and math.isfinite(value) and value >= 0,
            'Plan metrics must be finite nonnegative numbers')
    return value


def metrics(document, results):
    require(isinstance(document, list) and len(document) == 1 and isinstance(document[0], dict),
            'Expected one EXPLAIN JSON result')
    entry = document[0]
    plan = entry['Plan']
    require(number(plan['Actual Rows']) == results and number(plan['Actual Loops']) == 1,
            'EXPLAIN output rows do not match this probe resultCount')
    scans = {}

    def visit(node):
        if 'Relation Name' in node:
            rows = sum(number(node.get(key, 0)) for key in
                       ('Actual Rows', 'Rows Removed by Filter', 'Rows Removed by Index Recheck'))
            relation = node['Relation Name']
            scans[relation] = scans.get(relation, 0) + rows * number(node['Actual Loops'])
        for child in node.get('Plans', []):
            visit(child)

    visit(plan)
    return {
        'executionMs': number(entry['Execution Time']),
        'sharedHits': number(plan['Shared Hit Blocks']),
        'sharedReads': number(plan['Shared Read Blocks']),
        'tempWrites': number(plan['Temp Written Blocks']),
        'scanRowsIncludingFiltered': scans,
    }


def summarize(root):
    expected_probes = {f'{distribution}/{probe}' for distribution, probes in PROBES.items() for probe in probes}
    actual_probes = {str(path.parent.relative_to(root)) for path in root.glob('*/*/summary.json')}
    require(actual_probes == expected_probes,
            f'Expected 19 probes; missing={sorted(expected_probes - actual_probes)}, '
            f'unexpected={sorted(actual_probes - expected_probes)}')
    expected_plans = {f'{probe}/{variant}-{round_}.json' for probe in expected_probes
                      for variant in VARIANTS for round_ in range(ROUNDS)}
    actual_plans = {str(path.relative_to(root)) for path in root.glob('*/*/*.json') if path.name != 'summary.json'}
    require(actual_plans == expected_plans,
            f'Expected 171 plans; missing={sorted(expected_plans - actual_plans)}, '
            f'unexpected={sorted(actual_plans - expected_plans)}')
    fingerprints = {}

    def read(relative):
        content = (root / relative).read_bytes()
        fingerprints[relative] = hashlib.sha256(content).hexdigest()
        return json.loads(content)

    comparisons = []
    for probe_path in sorted(expected_probes):
        summary_path = f'{probe_path}/summary.json'
        summary = read(summary_path)
        require(type(summary['resultCount']) is int and summary['resultCount'] >= 0,
                f'{summary_path}: invalid resultCount')
        require(type(summary['oldest']) is bool and isinstance(summary['cursor'], str),
                f'{summary_path}: invalid ordering/cursor metadata')
        require(set(summary['samples']) == set(VARIANTS), f'{summary_path}: expected all three variants')
        variants = {}
        for variant in VARIANTS:
            recorded = summary['samples'][variant]
            require(isinstance(recorded, list) and len(recorded) == ROUNDS,
                    f'{summary_path}: {variant} must contain three samples')
            paths = [f'{probe_path}/{variant}-{round_}.json' for round_ in range(ROUNDS)]
            samples = []
            for round_, path in enumerate(paths):
                sample = metrics(read(path), summary['resultCount'])
                require(sample == recorded[round_], f'{path}: raw plan disagrees with probe summary sample {round_}')
                samples.append(sample)
            times = [sample['executionMs'] for sample in samples]
            relations = set().union(*(sample['scanRowsIncludingFiltered'] for sample in samples))
            variants[variant] = {
                'planPaths': paths,
                'executionMsMedian': statistics.median(times),
                'executionMsMin': min(times),
                'executionMsMax': max(times),
                **{key + 'Median': statistics.median(sample[key] for sample in samples)
                   for key in ('sharedHits', 'sharedReads', 'tempWrites')},
                'scanRowsIncludingFilteredMedian': {
                    relation: statistics.median(sample['scanRowsIncludingFiltered'].get(relation, 0) for sample in samples)
                    for relation in sorted(relations)
                },
            }
        distribution, probe = probe_path.split('/')
        comparisons.append({'distribution': distribution, 'probe': probe, 'results': summary['resultCount'],
                            'oldest': summary['oldest'], 'cursor': summary['cursor'],
                            'summaryPath': summary_path, 'variants': variants})

    # Reject a concurrent producer overwriting this snapshot. CI runs after the successful backend step.
    for path, digest in fingerprints.items():
        require(hashlib.sha256((root / path).read_bytes()).hexdigest() == digest,
                f'{path}: evidence changed while summarizing; rerun after the producer completes')
    return {
        'sourceRoot': str(root.relative_to(ROOT)) if root.is_relative_to(ROOT) else str(root),
        'probeCount': len(expected_probes), 'planCount': len(expected_plans), 'roundsPerVariant': ROUNDS,
        'distributions': {name: {'probes': list(probes), 'probeCount': len(probes),
                                 'planCount': len(probes) * len(VARIANTS) * ROUNDS} for name, probes in PROBES.items()},
        'comparisons': comparisons, 'sourceSha256': fingerprints,
        'measurementLimit': 'Computed only from the current probe summaries and raw EXPLAIN JSON. '
                            'Completeness and metric agreement do not prove test success or a production SLA. '
                            'No historical aggregate, JUnit report, timing threshold or implementation decision is imported.',
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-dir', type=Path, default=DEFAULT_INPUT)
    args = parser.parse_args()
    root = args.input_dir.resolve()
    output = root / 'comparison-summary.json'
    temporary = root / 'comparison-summary.json.tmp'
    try:
        # A failed regeneration must not leave a previous run's aggregate looking current.
        output.unlink(missing_ok=True)
        temporary.unlink(missing_ok=True)
        result = summarize(root)
        temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')
        temporary.replace(output)
    except (OSError, ValueError, KeyError, TypeError) as error:
        temporary.unlink(missing_ok=True)
        print(f'P33 summary failed: {error}', file=sys.stderr)
        return 1
    print(f'P33 summarized {result["probeCount"]} probes / {result["planCount"]} plans: {output}')
    return 0


if __name__ == '__main__':
    sys.exit(main())

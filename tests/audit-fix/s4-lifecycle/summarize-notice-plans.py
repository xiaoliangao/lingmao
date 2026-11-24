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
                    'all-current-owner', 'all-no-owner', 'no-modules', 'anonymous'),
    'empty-headers-100000': ('first', 'oldest'),
    'equal-timestamps': ('newest', 'oldest'),
    'deep-tail-5001': ('newest-tail', 'newest-empty', 'oldest-tail', 'oldest-empty'),
}
VARIANTS = ('production-group-count', 'group-count-after-page', 'visible-head-lateral', 'synchronous-summary')
AUXILIARY = ('legacy-total', 'summary-total', 'bounded-previews')
ALL_VARIANTS = VARIANTS + AUXILIARY
ROUNDS = 3
FIXTURES = {
    'uniform-5001': (5001, 100125, 20000, 5001, 13),
    'single-hot-100000': (121, 100360, 0, 121, 11),
    'sparse-self': (5008, 100024, 0, 5008, 14),
    'empty-headers-100000': (100025, 500, 0, 25, 1),
    'equal-timestamps': (192, 4032, 960, 192, 6),
    'deep-tail-5001': (5001, 100020, 0, 5001, 11),
}
# Fixed fixture facts: page size, page-query rows including lookahead, authorized groups,
# and preview rows after trimming lookahead. These are semantic counts, never timing gates.
EXPECTED_READS = {
    'uniform-5001/first': (50, 51, 5001, 1080),
    'uniform-5001/home-oldest': (20, 21, 5001, 20),
    'single-hot-100000/first-including-hot-count': (50, 51, 121, 247),
    'single-hot-100000/after-hot': (50, 51, 121, 150),
    'single-hot-100000/oldest': (20, 21, 121, 20),
    'sparse-self/self-all-modules': (50, 8, 8, 24),
    'sparse-self/self-owner-filter': (50, 6, 6, 18),
    'sparse-self/self-module-filter': (50, 4, 4, 12),
    'sparse-self/all-current-owner': (50, 51, 5002, 1000),
    'sparse-self/all-no-owner': (50, 51, 5008, 1000),
    'sparse-self/no-modules': (50, 0, 0, 0),
    'sparse-self/anonymous': (50, 0, 0, 0),
    'empty-headers-100000/first': (50, 25, 25, 500),
    'empty-headers-100000/oldest': (20, 21, 25, 20),
    'equal-timestamps/newest': (17, 18, 192, 357),
    'equal-timestamps/oldest': (17, 18, 192, 17),
    'deep-tail-5001/newest-tail': (50, 17, 5001, 340),
    'deep-tail-5001/newest-empty': (50, 0, 5001, 0),
    'deep-tail-5001/oldest-tail': (50, 17, 5001, 17),
    'deep-tail-5001/oldest-empty': (50, 0, 5001, 0),
}


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


def header_relation_loops(document):
    """Count executions of current-header relation nodes, including rejected PK probes.

    EXPLAIN reports average output/filter rows per loop, rounded to whole rows. Recording
    relation-node loops separately exposes repeated rejected probes that row proxies can
    understate. A loop can also be a sequential/parallel scan, so it is not a row count.
    """
    loops = {}

    def visit(node):
        relation = node.get('Relation Name')
        if relation in ('pur_order', 'prd_order', 'mrp_calc'):
            loops[relation] = loops.get(relation, 0) + number(node['Actual Loops'])
        for child in node.get('Plans', []):
            visit(child)

    visit(document[0]['Plan'])
    return loops


def summarize(root):
    expected_probes = {f'{distribution}/{probe}' for distribution, probes in PROBES.items() for probe in probes}
    require(set(EXPECTED_READS) == expected_probes, 'Every probe must have fixed semantic fixture counts')
    actual_probes = {str(path.parent.relative_to(root)) for path in root.glob('*/*/summary.json')}
    require(actual_probes == expected_probes,
            f'Expected {len(expected_probes)} probes; missing={sorted(expected_probes - actual_probes)}, '
            f'unexpected={sorted(actual_probes - expected_probes)}')
    expected_plans = {f'{probe}/{variant}-{round_}.json' for probe in expected_probes
                      for variant in ALL_VARIANTS for round_ in range(ROUNDS)}
    actual_plans = {str(path.relative_to(root)) for path in root.glob('*/*/*.json')
                    if path.name != 'summary.json' and path.parent.name != 'write-plans'}
    require(actual_plans == expected_plans,
            f'Expected {len(expected_plans)} plans; missing={sorted(expected_plans - actual_plans)}, '
            f'unexpected={sorted(actual_plans - expected_plans)}')
    expected_sql = {f'{probe}/{variant}.sql' for probe in expected_probes for variant in ALL_VARIANTS}
    actual_sql = {str(path.relative_to(root)) for path in root.glob('*/*/*.sql')}
    require(actual_sql == expected_sql,
            f'Expected {len(expected_sql)} SQL sources; missing={sorted(expected_sql - actual_sql)}, '
            f'unexpected={sorted(actual_sql - expected_sql)}')
    fingerprints = {}

    def read(relative):
        content = (root / relative).read_bytes()
        fingerprints[relative] = hashlib.sha256(content).hexdigest()
        return json.loads(content)

    comparisons = []
    for probe_path in sorted(expected_probes):
        summary_path = f'{probe_path}/summary.json'
        summary = read(summary_path)
        require(type(summary.get('measurementVersion')) is int and summary['measurementVersion'] == 2,
                f'{summary_path}: expected measurementVersion 2')
        require(type(summary['resultCount']) is int and summary['resultCount'] >= 0,
                f'{summary_path}: invalid resultCount')
        require(type(summary['oldest']) is bool and isinstance(summary['cursor'], str),
                f'{summary_path}: invalid ordering/cursor metadata')
        require(set(summary['samples']) == set(VARIANTS), f'{summary_path}: expected all four page variants')
        require(set(summary['auxiliarySamples']) == set(AUXILIARY), f'{summary_path}: expected count and preview samples')
        require(type(summary['totalGroups']) is int and summary['totalGroups'] >= summary['resultCount'],
                f'{summary_path}: invalid totalGroups')
        require(type(summary.get('pageSize')) is int and 1 <= summary['pageSize'] <= 100
                and summary['resultCount'] <= summary['pageSize'] + 1,
                f'{summary_path}: invalid pageSize or lookahead result count')
        require(type(summary.get('previewGroups')) is int
                and summary['previewGroups'] == min(summary['pageSize'], summary['resultCount']),
                f'{summary_path}: previews must exclude the lookahead group')
        preview_limit = 1 if summary['oldest'] else 100
        require(type(summary['previewRows']) is int
                and summary['previewGroups'] <= summary['previewRows'] <= summary['previewGroups'] * preview_limit,
                f'{summary_path}: invalid previewRows')
        require((summary['pageSize'], summary['resultCount'], summary['totalGroups'], summary['previewRows'])
                == EXPECTED_READS[probe_path], f'{summary_path}: semantic fixture counts changed')
        require(summary['oldest'] == (probe_path.split('/')[1] in ('home-oldest', 'oldest', 'oldest-tail', 'oldest-empty')),
                f'{summary_path}: wrong fixture ordering')
        expected_auxiliary_rows = {'legacy-total': 1, 'summary-total': 1, 'bounded-previews': summary['previewRows']}
        require(summary['auxiliaryRows'] == expected_auxiliary_rows, f'{summary_path}: invalid auxiliary output rows')
        require(all(type(value) is int for value in summary['auxiliaryRows'].values()),
                f'{summary_path}: auxiliary output rows must be integers')
        all_samples = {**summary['samples'], **summary['auxiliarySamples']}
        variants = {}
        for variant in ALL_VARIANTS:
            recorded = all_samples[variant]
            require(isinstance(recorded, list) and len(recorded) == ROUNDS,
                    f'{summary_path}: {variant} must contain three samples')
            sql_path = f'{probe_path}/{variant}.sql'
            sql_content = (root / sql_path).read_bytes()
            require(bool(sql_content.strip()), f'{sql_path}: empty SQL source')
            fingerprints[sql_path] = hashlib.sha256(sql_content).hexdigest()
            output_rows = expected_auxiliary_rows.get(variant, summary['resultCount'])
            paths = [f'{probe_path}/{variant}-{round_}.json' for round_ in range(ROUNDS)]
            samples = []
            header_loops = []
            for round_, path in enumerate(paths):
                document = read(path)
                sample = metrics(document, output_rows)
                require(sample == recorded[round_], f'{path}: raw plan disagrees with probe summary sample {round_}')
                samples.append(sample)
                header_loops.append(header_relation_loops(document))
            times = [sample['executionMs'] for sample in samples]
            relations = set().union(*(sample['scanRowsIncludingFiltered'] for sample in samples))
            variants[variant] = {
                'planPaths': paths, 'sqlPath': sql_path, 'outputRows': output_rows,
                'executionMsMedian': statistics.median(times),
                'executionMsMin': min(times),
                'executionMsMax': max(times),
                **{key + 'Median': statistics.median(sample[key] for sample in samples)
                   for key in ('sharedHits', 'sharedReads', 'tempWrites')},
                'scanRowsIncludingFilteredMedian': {
                    relation: statistics.median(sample['scanRowsIncludingFiltered'].get(relation, 0) for sample in samples)
                    for relation in sorted(relations)
                },
                'currentHeaderRelationLoopsMedian': {
                    relation: statistics.median(sample.get(relation, 0) for sample in header_loops)
                    for relation in ('pur_order', 'prd_order', 'mrp_calc')
                },
            }
        distribution, probe = probe_path.split('/')
        comparisons.append({'distribution': distribution, 'probe': probe, 'results': summary['resultCount'],
                            'totalGroups': summary['totalGroups'], 'pageSize': summary['pageSize'],
                            'previewGroups': summary['previewGroups'], 'previewRows': summary['previewRows'],
                            'oldest': summary['oldest'], 'cursor': summary['cursor'],
                            'summaryPath': summary_path, 'variants': variants})

    expected_writes = {f'{distribution}/write-plans/insert-{index}.json'
                       for distribution, fixture in FIXTURES.items() for index in range(fixture[4])}
    actual_writes = {str(path.relative_to(root)) for path in root.glob('*/write-plans/*.json')}
    require(actual_writes == expected_writes,
            f'Expected {len(expected_writes)} actual INSERT plans; missing={sorted(expected_writes - actual_writes)}, '
            f'unexpected={sorted(actual_writes - expected_writes)}')
    maintenance = {}
    for distribution, (headers, pending, handled, groups, statements) in FIXTURES.items():
        fixture = read(f'{distribution}/fixture.json')
        for name, value in {'headers': headers, 'pending': pending, 'handled': handled, 'groups': groups}.items():
            require(type(fixture[name]) is int and fixture[name] == value,
                    f'{distribution}: {name} fixture size changed')
        writes = read(f'{distribution}/maintenance.json')
        require(type(writes['insertStatements']) is int and writes['insertStatements'] == statements,
                f'{distribution}: INSERT statement count changed')
        require(isinstance(writes['samples'], list) and len(writes['samples']) == statements,
                f'{distribution}: incomplete INSERT samples')
        rows_by_state = {False: 0, True: 0}
        samples = []
        for index, sample in enumerate(writes['samples']):
            path = f'{distribution}/write-plans/insert-{index}.json'
            require(sample['planPath'] == path, f'{distribution}: wrong INSERT plan path')
            require(type(sample['handled']) is bool, f'{path}: invalid handled flag')
            for key in ('insertedRows', 'documentKeys', 'revisionBefore', 'revisionAfter', 'revisionDelta'):
                require(type(sample[key]) is int and sample[key] >= 0, f'{path}: invalid {key}')
            require(1 <= sample['documentKeys'] <= 500 and sample['insertedRows'] >= sample['documentKeys'],
                    f'{path}: invalid bounded fixture batch')
            require(sample['revisionAfter'] - sample['revisionBefore'] == sample['revisionDelta']
                    == (0 if sample['handled'] else sample['documentKeys']),
                    f'{path}: expected one logical pending revision per document key')
            revisions = sample.get('revisions')
            require(isinstance(revisions, list) and len(revisions) == sample['documentKeys'],
                    f'{path}: incomplete per-key revision evidence')
            seen_ids = set()
            for revision in revisions:
                doc_id = revision['docId']
                require(type(doc_id) is str and doc_id.isascii() and doc_id.isdigit()
                        and str(int(doc_id)) == doc_id and 0 < int(doc_id) <= 9223372036854775807,
                        f'{path}: invalid document ID string')
                require(doc_id not in seen_ids, f'{path}: duplicate revision document key')
                seen_ids.add(doc_id)
                for key in ('before', 'after', 'delta'):
                    require(type(revision[key]) is int and revision[key] >= 0, f'{path}: invalid per-key {key}')
                require(revision['after'] - revision['before'] == revision['delta'] == (0 if sample['handled'] else 1),
                        f'{path}: a document key has the wrong revision delta')
            for key, aggregate in (('before', 'revisionBefore'), ('after', 'revisionAfter'), ('delta', 'revisionDelta')):
                require(sum(revision[key] for revision in revisions) == sample[aggregate],
                        f'{path}: per-key revisions do not match {aggregate}')
            number(sample['statementWallMs'])
            rows_by_state[sample['handled']] += sample['insertedRows']
            document = read(path)
            require(isinstance(document, list) and len(document) == 1, f'{path}: invalid INSERT plan')
            entry = document[0]
            plan = entry['Plan']
            require(plan['Node Type'] == 'ModifyTable' and plan['Operation'] == 'Insert'
                    and plan['Relation Name'] == 'biz_doc_notice', f'{path}: not the actual notice INSERT')
            require(number(plan['Actual Rows']) == 0 and number(plan['Actual Loops']) == 1,
                    f'{path}: unexpected INSERT output')
            children = plan.get('Plans', [])
            require(len(children) == 1 and number(children[0]['Actual Rows']) == sample['insertedRows']
                    and number(children[0]['Actual Loops']) == 1, f'{path}: inserted input row count mismatch')
            triggers = [{'name': trigger['Trigger Name'], 'calls': number(trigger['Calls']),
                         'timeMs': number(trigger['Time'])} for trigger in entry.get('Triggers', [])]
            summary_triggers = [trigger for trigger in triggers if trigger['name'] == 'notice_pending_insert']
            require(len(summary_triggers) == 1 and summary_triggers[0]['calls'] == 1,
                    f'{path}: missing or repeated statement-level notice summary trigger')
            for key in ('WAL Records', 'WAL FPI', 'WAL Bytes'):
                require(key in plan, f'{path}: missing measured {key}')
            samples.append({**sample, 'executionMs': number(entry['Execution Time']), 'triggers': triggers,
                            'rootNodeWalRecords': number(plan['WAL Records']),
                            'rootNodeWalFpi': number(plan['WAL FPI']),
                            'rootNodeWalBytes': number(plan['WAL Bytes'])})
        require(rows_by_state == {False: pending, True: handled}, f'{distribution}: INSERT totals differ from fixture')
        maintenance[distribution] = {'insertStatements': statements, 'samples': samples,
                                     'measurementLimit': writes['measurementLimit']}

    isolation = read('sparse-self/all-no-owner-isolation.json')
    require(isolation.get('method') == 'savepoint-normal-delete' and isolation.get('scope') == 'ALL'
            and isolation.get('ownerFilter') is False and isolation.get('probeCompleted') is True,
            'ALL without owner must use its actual production scope and complete inside the fixture savepoint')
    for key in ('outsidePendingBefore', 'outsidePendingDeleted', 'outsidePendingDuringProbe',
                'affectedSummaryRowsBefore', 'fixturePendingDuringProbe',
                'restoredNoticeDifferences', 'restoredSummaryDifferences'):
        require(type(isolation.get(key)) is int and isolation[key] >= 0, f'ALL probe: invalid {key}')
    require(isolation['outsidePendingDeleted'] == isolation['outsidePendingBefore']
            and isolation['outsidePendingDuringProbe'] == 0
            and isolation['fixturePendingDuringProbe'] == FIXTURES['sparse-self'][1]
            and isolation['restoredNoticeDifferences'] == isolation['restoredSummaryDifferences'] == 0,
            'ALL probe must isolate non-scenario pending rows and restore full source/summary tuples afterward')

    # Reject a concurrent producer overwriting this snapshot. CI runs after the successful backend step.
    for path, digest in fingerprints.items():
        require(hashlib.sha256((root / path).read_bytes()).hexdigest() == digest,
                f'{path}: evidence changed while summarizing; rerun after the producer completes')
    return {
        'sourceRoot': str(root.relative_to(ROOT)) if root.is_relative_to(ROOT) else str(root),
        'measurementVersion': 2, 'probeCount': len(expected_probes), 'planCount': len(expected_plans),
        'sqlSourceCount': len(expected_sql), 'roundsPerVariant': ROUNDS,
        'pageVariants': list(VARIANTS), 'auxiliaryQueries': list(AUXILIARY),
        'maintenancePlanCount': len(expected_writes), 'maintenance': maintenance,
        'allWithoutOwnerIsolation': isolation,
        'distributions': {name: {'probes': list(probes), 'probeCount': len(probes),
                                 'planCount': len(probes) * len(ALL_VARIANTS) * ROUNDS} for name, probes in PROBES.items()},
        'comparisons': comparisons, 'sourceSha256': fingerprints,
        'measurementLimit': 'Computed only from the current probe summaries and raw EXPLAIN JSON. '
                            'Page, exact count and bounded preview costs remain separate. EXPLAIN COUNT returns '
                            'one row; its aggregate value is asserted by the producer test, not recoverable '
                            'from its EXPLAIN output. Completeness and metric agreement do not prove test '
                            'success, a production SLA, or an O(pageSize) bound for authorization and exact count. '
                            'Current-header relation loops count plan-node executions, including rejected probes; '
                            'they are not distinct row counts and must be read together with node type and row proxies. '
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

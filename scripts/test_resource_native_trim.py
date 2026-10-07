import csv
import hashlib
import tempfile
import unittest
import shutil
from pathlib import Path

from resource_native_trim import make_trim_row, trim_receipt, verify_trim_file, write_trim_file
from resource_report import verify_dataset
from native_trim_report import render
import json


class NativeTrimReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'native-heap-trim.csv'
        self.closed = dict(phase='closed', pid=1234, elapsedSeconds=62.0, rssBytes=170000000,
            heapUsedBytes=12000000, nativeNonHeapCommittedBytes=42000000)
        self.detail = dict(phase='closed', pid=1234, elapsedSeconds=62.0, rollupRssBytes=170000000,
            rollupAnonymousBytes=120000000, rollupPssBytes=130000000)
        self.after = dict(pid=1234, elapsedSeconds=64.5, rssBytes=150000000,
            heapUsedBytes=12000000, nativeNonHeapCommittedBytes=42000000)
        self.after_detail = dict(rollupRssBytes=150000000, rollupAnonymousBytes=100000000,
            rollupPssBytes=110000000)

    def fixture(self):
        before = make_trim_row('before', self.closed, self.detail)
        after = make_trim_row('after', self.after, self.after_detail)
        write_trim_file(self.path, (before, after))
        return trim_receipt(self.path)

    def test_binds_two_numeric_samples_to_original_closed_sample_and_hash(self):
        receipt = self.fixture()
        rows = verify_trim_file(self.path, receipt, self.closed, self.detail, 'linux')
        self.assertEqual([row['phase'] for row in rows], ['before', 'after'])
        self.assertEqual(rows[0]['rssBytes'] - rows[1]['rssBytes'], 20000000)
        self.assertEqual(receipt['sha256'], hashlib.sha256(self.path.read_bytes()).hexdigest())
        self.assertEqual(set(receipt), {'version', 'file', 'rows', 'sha256'})

    def test_rejects_tampering_even_if_hash_is_rewritten_when_closed_sample_no_longer_matches(self):
        receipt = self.fixture()
        changed = self.path.read_text(encoding='utf-8').replace('170000000', '171000000', 1)
        self.path.write_text(changed, encoding='utf-8')
        with self.assertRaises(ValueError):
            verify_trim_file(self.path, receipt, self.closed, self.detail, 'linux')
        receipt['sha256'] = hashlib.sha256(self.path.read_bytes()).hexdigest()
        with self.assertRaises(ValueError):
            verify_trim_file(self.path, receipt, self.closed, self.detail, 'linux')

    def test_rejects_changed_pid_nonmonotonic_time_and_wrong_platform(self):
        receipt = self.fixture()
        with self.assertRaises(ValueError):
            verify_trim_file(self.path, receipt, self.closed, self.detail, 'windows')
        for key, value in (('pid', '4321'), ('elapsedSeconds', '62.0')):
            self.fixture()
            with self.path.open(newline='', encoding='utf-8') as source:
                rows = list(csv.DictReader(source))
            rows[1][key] = value
            with self.path.open('w', newline='', encoding='utf-8') as target:
                writer = csv.DictWriter(target, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
            receipt = trim_receipt(self.path)
            with self.assertRaises(ValueError):
                verify_trim_file(self.path, receipt, self.closed, self.detail, 'linux')

    def test_rejects_missing_file_invalid_receipt_and_unreported_linux_page_numbers(self):
        receipt = self.fixture()
        with self.assertRaises(ValueError):
            verify_trim_file(self.path.with_name('missing.csv'), receipt, self.closed, self.detail, 'linux')
        with self.assertRaises(ValueError):
            verify_trim_file(self.path, {**receipt, 'rows': 3}, self.closed, self.detail, 'linux')
        with self.assertRaises(ValueError):
            make_trim_row('after', self.after, {**self.after_detail, 'rollupAnonymousBytes': None})

    def test_two_mode_report_requires_one_verified_workflow_run_id(self):
        archived = Path(__file__).resolve().parents[1] / 'docs/validation/samples/2026-10-07-http-timing/60s'
        source = '8f2df4b2a66b3cab90ab68dc85694f05953e4418'
        root = Path(self.temp.name)
        summaries = []
        for mode, run_id in (('http-disabled', 121), ('http-enabled', 122)):
            folder = root / mode
            folder.mkdir()
            for name in ('summary.json', 'memory.csv', 'memory-details.csv'):
                shutil.copyfile(archived / (mode + '-linux') / name, folder / name)
            meta, base = verify_dataset(folder / 'summary.json', source, 60)
            closed, detail = base[-1], meta['_memoryDetailsRows'][-1]
            after = dict(closed, elapsedSeconds=closed['elapsedSeconds'] + 2)
            path = folder / 'native-heap-trim.csv'
            write_trim_file(path, (make_trim_row('before', closed, detail), make_trim_row('after', after, detail)))
            meta.pop('_memoryDetailsRows')
            meta['nativeHeapTrim'] = trim_receipt(path)
            meta['workflowRunId'] = run_id
            (folder / 'summary.json').write_text(json.dumps(meta), encoding='utf-8')
            summaries.append(folder / 'summary.json')
        with self.assertRaises(ValueError):
            render(root, source, 60, 121)
        meta = json.loads(summaries[1].read_text(encoding='utf-8'))
        meta['workflowRunId'] = 121
        summaries[1].write_text(json.dumps(meta), encoding='utf-8')
        table = render(root, source, 60, 121)
        self.assertIn('| enabled |', table)
        self.assertIn('| disabled |', table)


if __name__ == '__main__':
    unittest.main()

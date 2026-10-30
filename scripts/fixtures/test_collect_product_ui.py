#!/usr/bin/env python3
import copy
import json
from pathlib import Path
import unittest

from collect_product_ui import methods_for, prefix_for, state_tag_for, validate

PLAN = json.loads((Path(__file__).parent / 'product-ui-capture-plan.json').read_text())
COMMIT = 'a' * 40


def ledger(method):
    spec = PLAN['methods'][method]
    prefix = prefix_for(method, spec)
    expected = [f'{prefix}-{x}.png' for x in spec['suffixes']]
    return {'success': True, 'method': method, 'source_commit': COMMIT,
            'package': 'com.rdp.sync.preview3', 'sdk': 35, 'synthetic_ui_only': True,
            'credential_screenshots': False, 'physical_foldable_test': False,
            'locale': spec['locale'], 'dark': spec['dark'], 'font_scale': spec['font_scale'],
            'device_width_dp': 640 if method == 'expandedWidth' else 320,
            'started_at_epoch_ms': 1001000, 'expected_files': expected,
            'captured_files': [{'filename': name, 'sha256': 'b' * 64, 'captured_at_epoch_ms': 1002000,
                                'draw_barriers': 1, 'active_state_tag': state_tag_for(name[len(prefix) + 1:-4])}
                               for name in expected]}


class UiCollectorTest(unittest.TestCase):
    def check(self, value, method='englishLight'):
        return validate(value, method, PLAN['methods'][method], COMMIT, 1000)

    def test_exact_six_methods_and_finite_plan(self):
        methods = methods_for('phone') + methods_for('expanded')
        self.assertEqual(set(methods), set(PLAN['methods']))
        self.assertEqual(sum(len(x['suffixes']) for x in PLAN['methods'].values()), 78)
        for method in methods: self.check(ledger(method), method)

    def test_wrong_source_and_old_package_fail(self):
        for key, value in [('source_commit', 'c' * 40), ('package', 'com.rdp.sync.preview2')]:
            item = ledger('englishLight'); item[key] = value
            with self.assertRaises(AssertionError): self.check(item)

    def test_failed_or_fabricated_scope_fails(self):
        for key in ['success', 'synthetic_ui_only']:
            item = ledger('englishLight'); item[key] = False
            with self.assertRaises(AssertionError): self.check(item)
        item = ledger('englishLight'); item['physical_foldable_test'] = True
        with self.assertRaises(AssertionError): self.check(item)

    def test_actual_narrow_window_required(self):
        item = ledger('narrowLargeText'); item['device_width_dp'] = 411
        with self.assertRaises(AssertionError): self.check(item, 'narrowLargeText')

    def test_expanded_is_actual_minimum_not_a_claimed_constraint(self):
        item = ledger('expandedWidth'); item['device_width_dp'] = 599
        with self.assertRaises(AssertionError): self.check(item, 'expandedWidth')

    def test_missing_and_duplicate_frame_fail(self):
        item = ledger('englishLight'); item['captured_files'].pop()
        with self.assertRaises(AssertionError): self.check(item)
        item = ledger('englishLight'); item['captured_files'][-1] = copy.deepcopy(item['captured_files'][0])
        with self.assertRaises(AssertionError): self.check(item)

    def test_unplanned_frame_fails(self):
        item = ledger('englishLight'); item['captured_files'][-1]['filename'] = 'unrelated.png'
        with self.assertRaises(AssertionError): self.check(item)

    def test_fresh_start_and_capture_time_required(self):
        item = ledger('englishLight'); item['started_at_epoch_ms'] = 999999
        with self.assertRaises(AssertionError): self.check(item)
        item = ledger('englishLight'); item['captured_files'][0]['captured_at_epoch_ms'] = 1000999
        with self.assertRaises(AssertionError): self.check(item)

    def test_missing_or_invalid_draw_barrier_fails(self):
        for value in [None, 0, -1, 6, True, '1']:
            item = ledger('englishLight'); row = item['captured_files'][0]
            if value is None: del row['draw_barriers']
            else: row['draw_barriers'] = value
            with self.assertRaises((AssertionError, KeyError)): self.check(item)

    def test_wrong_or_missing_rendered_state_fails(self):
        for value in [None, '', 'product-ui-unrelated', 'profiles']:
            item = ledger('englishLight'); row = item['captured_files'][0]
            if value is None: del row['active_state_tag']
            else: row['active_state_tag'] = value
            with self.assertRaises((AssertionError, KeyError)): self.check(item)

    def test_dialog_suffix_maps_to_actual_underlying_state(self):
        self.assertEqual(state_tag_for('delete-confirmation'), 'product-ui-detail')
        self.assertEqual(state_tag_for('drive-write-consent'), 'product-ui-drive_consent')
        self.assertEqual(state_tag_for('profiles'), 'product-ui-profiles')

    def test_hash_and_configuration_required(self):
        item = ledger('englishLight'); item['captured_files'][0]['sha256'] = 'not a hash'
        with self.assertRaises(AssertionError): self.check(item)
        item = ledger('englishLight'); item['font_scale'] = 1.5
        with self.assertRaises(AssertionError): self.check(item)


if __name__ == '__main__': unittest.main()

#!/usr/bin/env python3
"""Static product UI contract. Does not replace rendered screenshots or TalkBack testing."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def resources(directory):
    return {entry.attrib['name']: ''.join(entry.itertext())
            for path in (ROOT / 'app/src/main/res' / directory).glob('*.xml')
            for entry in ET.parse(path).getroot() if entry.tag == 'string'}


def luminance(hex_value):
    values = [int(hex_value[i:i + 2], 16) / 255 for i in (2, 4, 6)]
    linear = [x / 12.92 if x <= .04045 else ((x + .055) / 1.055) ** 2.4 for x in values]
    return sum(a * b for a, b in zip(linear, (.2126, .7152, .0722)))


def contrast(a, b):
    high, low = sorted((luminance(a), luminance(b)), reverse=True)
    return (high + .05) / (low + .05)


class ProductUiContract(unittest.TestCase):
    def test_english_chinese_resource_coverage_and_placeholders(self):
        english, chinese = resources('values'), resources('values-zh')
        unlocalized = {entry.attrib['name'] for path in (ROOT / 'app/src/main/res/values').glob('*.xml')
                       for entry in ET.parse(path).getroot() if entry.tag == 'string' and entry.attrib.get('translatable') == 'false'}
        self.assertEqual(set(english) - unlocalized, set(chinese))
        for key in chinese:
            self.assertEqual(re.findall(r'%\d+\$[dsf]', english[key]), re.findall(r'%\d+\$[dsf]', chinese[key]), key)
        used = set()
        for path in (ROOT / 'app/src/main/java/com/rdp/sync/ui').rglob('*.kt'):
            used.update(re.findall(r'R\.string\.(\w+)', path.read_text()))
        self.assertFalse(used - english.keys(), used - english.keys())

    def test_paired_brand_tokens_have_normal_text_contrast(self):
        theme = (ROOT / 'app/src/main/java/com/rdp/sync/ui/theme/Theme.kt').read_text()
        for name in ('DarkColorScheme', 'LightColorScheme'):
            source = theme.split(f'val {name} = ')[1].split('\n)')[0]
            pairs = dict(re.findall(r'(\w+) = Color\(0x([A-Fa-f0-9]{8})\)', source))
            pairs.update({key: 'FFFFFFFF' for key in re.findall(r'(\w+) = Color.White', source)})
            for foreground, background in [('onPrimary', 'primary'), ('onPrimaryContainer', 'primaryContainer'),
                    ('onSecondary', 'secondary'), ('onSecondaryContainer', 'secondaryContainer'),
                    ('onBackground', 'background'), ('onSurface', 'surface'), ('onSurfaceVariant', 'surfaceVariant'),
                    ('onError', 'error'), ('onErrorContainer', 'errorContainer'), ('primary', 'surface'), ('error', 'surface')]:
                ratio = contrast(pairs[foreground], pairs[background])
                self.assertGreaterEqual(ratio, 4.5, f'{name}: {foreground}/{background} = {ratio:.2f}')


if __name__ == '__main__':
    unittest.main()

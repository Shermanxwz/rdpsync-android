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


class ClipboardFocusContract(unittest.TestCase):
    """Source-only guard; actual GNOME/xrdp taps still require hosted execution."""
    def setUp(self):
        self.source = (ROOT / 'app/src/androidTest/java/com/rdp/sync/ClipboardTransferScenario.kt').read_text()
        self.focus = self.source.split('private fun focusRealPeerSurface()', 1)[1].split('private fun navigateDocumentsFolder', 1)[0]

    def test_one_original_gesture_and_post_input_gate(self):
        self.assertEqual(1, self.focus.count('physicalTap('))
        self.assertIn('physicalTap(x, y, 40, injections)', self.focus)
        self.assertIn('val deadline = started + 30_000', self.focus)
        self.assertIn('(deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)', self.focus)
        self.assertIn('timeout: Long = 30_000', self.source)
        self.assertIn('s.getInt("clicks") == before + 1', self.focus)
        for axis, target in ((0, 400), (1, 300)):
            self.assertIn(f'position.getDouble({axis}) - {target}) <= 1', self.focus)
        self.assertNotIn('SystemClock.sleep', self.focus)
        self.assertNotIn('settings put', self.focus)
        self.assertNotIn('performClick', self.focus)

    def test_peer_generation_and_actual_source_pixels_not_local_view_alone(self):
        for condition in ('FixturePeerSurfaceReadiness.ready(',
                          'RdpConnector.sessionGeneration() == generation',
                          'viewport.remoteWidth == peerState.optInt("width")',
                          'viewport.remoteHeight == peerState.optInt("height")',
                          'view.hasWindowFocus()', 'automation.takeScreenshot()',
                          'p.x < view.width && p.y < view.height',
                          'listOf(0xff0000, 0x00ff00, 0x0000ff)',
                          'if (peer == ClipboardFixturePeer.GNOME) 2 else 0'):
            self.assertIn(condition, self.focus)
        self.assertEqual(3, self.focus.count('request("GET", "/state")'))  # Two pre-input reads, one post-input.

    def test_bounded_observations_and_original_injection_receipts(self):
        self.assertIn('samples.length() < 61', self.focus)
        self.assertIn('current_presentation_event_monotonic', self.source)
        for field in ('peer_focus_attempts', 'readiness_samples', 'peer_after_pixels',
                      'rendered_source_pixels', 'tap_started_elapsed_ms', 'tap_completed_elapsed_ms'):
            self.assertIn('"' + field + '"', self.focus)
        for field in ('down_time_ms', 'event_time_ms', 'accepted', 'completed_uptime_ms'):
            self.assertIn('"' + field + '"', self.source)


    def test_actual_java_readiness_predicate_rejects_invalid_presentations(self):
        import os
        import shutil
        import subprocess
        import tempfile
        java_home = os.environ.get('JAVA_HOME')
        def tool(name):
            path = Path(java_home) / 'bin' / name if java_home else None
            result = str(path) if path and path.is_file() else shutil.which(name)
            self.assertTrue(result, name + ' is required; this check must not skip')
            return result
        source = ROOT / 'app/src/androidTest/java/com/rdp/sync/FixturePeerSurfaceReadiness.java'
        harness = r'''import java.util.*;
import com.rdp.sync.FixturePeerSurfaceReadiness;
public class PeerReadinessHostTest {
  static int count;
  static Map<String,Object> good() {
    Map<String,Object> m=new HashMap<>();
    for(String key:new String[]{"ready","mapped","visible","fullscreen"}) m.put(key,true);
    for(String key:new String[]{"presentation_generation","painted_generation","after_paint_generation"}) m.put(key,4L);
    m.put("width",1024);m.put("height",768);m.put("monitor_width",1024);m.put("monitor_height",768);
    m.put("session_id","fixture-session");m.put("desktop_pid",101);
    return m;
  }
  static boolean ready(Map<String,Object> now,Map<String,Object> prior,long nativeGen,long expected,long time,int width,int height) {
    return FixturePeerSurfaceReadiness.ready(now,prior,true,nativeGen,expected,time,30000,width,height);
  }
  static void check(boolean actual,boolean expected,String why) {if(actual!=expected)throw new AssertionError(why);count++;}
  public static void main(String[] args) {
    Map<String,Object> prior=good(); prior.put("presentation_generation",3L);
    check(ready(good(),prior,7,7,1,1024,768),true,"new output");
    check(ready(good(),good(),7,7,1,1024,768),true,"legitimate output reuse");
    for(String key:new String[]{"ready","mapped","visible","fullscreen"}) {
      Map<String,Object> m=good();m.put(key,false);check(ready(m,prior,7,7,1,1024,768),false,key);
    }
    for(String key:new String[]{"painted_generation","after_paint_generation"}) {
      Map<String,Object> m=good();m.put(key,3L);check(ready(m,prior,7,7,1,1024,768),false,key);
    }
    for(String key:new String[]{"width","height","monitor_width","monitor_height","desktop_pid"}) {
      Map<String,Object> m=good();m.put(key,999);check(ready(m,prior,7,7,1,1024,768),false,key);
    }
    Map<String,Object> stale=good();stale.put("presentation_generation",2L);
    stale.put("painted_generation",2L);stale.put("after_paint_generation",2L);
    check(ready(stale,prior,7,7,1,1024,768),false,"stale generation");
    Map<String,Object> replaced=good();replaced.put("session_id","replacement");
    check(ready(replaced,prior,7,7,1,1024,768),false,"replaced peer");
    check(ready(good(),prior,8,7,1,1024,768),false,"new native generation");
    check(ready(good(),prior,0,0,1,1024,768),false,"no native session");
    check(ready(good(),prior,7,7,30000,1024,768),false,"deadline boundary");
    check(ready(good(),prior,7,7,30001,1024,768),false,"past deadline");
    check(ready(good(),prior,7,7,1,720,1024),false,"stale local viewport");
    check(ready(good(),null,7,7,1,1024,768),false,"missing prior peer");
    System.out.println("Actual Java readiness predicate: "+count+" checks passed");
  }
}'''
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'PeerReadinessHostTest.java').write_text(harness)
            subprocess.run([tool('javac'), '-d', str(path), str(source), str(path / 'PeerReadinessHostTest.java')], check=True, timeout=30)
            subprocess.run([tool('java'), '-cp', str(path), 'PeerReadinessHostTest'], check=True, timeout=30)


if __name__ == '__main__':
    unittest.main()

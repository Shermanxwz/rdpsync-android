#!/usr/bin/env python3
"""Finite fake GTK lifecycle tests; not proof of real compositor presentation."""
import unittest
from presentation import Presentation


class Surface:
    def __init__(self): self.visible=False; self.calls=[]
    def get_visible(self): return self.visible
    def hide(self): self.visible=False; self.calls.append('hide')
    def fullscreen(self): self.calls.append('fullscreen')
    def show_all(self): self.visible=True; self.calls.append('show')
    def present(self): self.calls.append('present')
    def grab_focus(self): self.calls.append('focus')
    def queue_draw(self): self.calls.append('draw')


class TestPresentation(unittest.TestCase):
    def setUp(self):
        self.surface=Surface();self.events=[]
        self.p=Presentation(self.surface,self.surface,lambda event,**kw:self.events.append((event,kw)))
        self.monitor=object()

    def test_old_count_only_counterfactual_and_new_geometry_gate(self):
        old=Surface()
        # Exact old condition: a reported monitor exists, window not visible.
        if 1 > 0 and not old.get_visible(): old.show_all();old.fullscreen();old.present()
        self.assertEqual(old.calls,['show','fullscreen','present'])
        self.assertFalse(self.p.observe(self.monitor,(0,0,0,0)))
        self.assertEqual(self.surface.calls,[])
        self.assertTrue(self.p.observe(self.monitor,(0,0,1024,768)))
        self.assertEqual(self.surface.calls,['fullscreen','show','present','focus','draw'])

    def test_repeated_monitor_callbacks_never_remap_or_redraw(self):
        self.p.observe(self.monitor,(0,0,1024,768))
        for _ in range(100): self.p.observe(self.monitor,(0,0,1024,768))
        self.assertEqual(self.p.generation,1)
        self.assertEqual(self.surface.calls,['fullscreen','show','present','focus','draw'])

    def test_resize_same_output_never_remaps(self):
        self.p.observe(self.monitor,(0,0,1024,768));self.p.observe(self.monitor,(0,0,1280,720))
        self.assertEqual(self.p.generation,1)
        self.assertEqual(self.surface.calls.count('show'),1)

    def test_negative_without_output_then_positive_once(self):
        for _ in range(10): self.p.observe(None,None)
        self.assertEqual(self.surface.calls,[])
        self.p.observe(self.monitor,(0,0,1024,768));self.assertEqual(self.p.generation,1)

    def test_output_removed_recreated_same_geometry_new_generation(self):
        self.p.observe(self.monitor,(0,0,1024,768));self.p.observe(None,None);self.p.observe(None,None)
        other=object();self.p.observe(other,(0,0,0,0));self.assertFalse(self.surface.visible)
        self.p.observe(other,(0,0,1024,768));self.assertEqual(self.p.generation,2)
        self.assertEqual(self.surface.calls.count('hide'),1);self.assertEqual(self.surface.calls.count('show'),2)

    def test_replacement_without_absent_callback_and_invalid_dimensions(self):
        for geometry in (None,(0,0,0,768),(0,0,1024,-1)):
            self.assertFalse(self.p.observe(self.monitor,geometry))
        self.p.observe(self.monitor,(0,0,1024,768));self.p.observe(object(),(0,0,1024,768))
        self.assertEqual(self.p.generation,2);self.assertEqual(self.surface.calls.count('hide'),1)


if __name__=='__main__': unittest.main()

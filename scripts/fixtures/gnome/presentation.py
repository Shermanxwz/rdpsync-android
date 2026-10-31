"""One presentation per real, usable output lifecycle; no pixel-driven recovery."""


class Presentation:
    def __init__(self, window, area, record):
        self.window, self.area, self.record = window, area, record
        self.monitor = None
        self.generation = 0
        self.last_observation = None

    def observe(self, monitor, geometry):
        usable = monitor is not None and geometry is not None and geometry[2] > 0 and geometry[3] > 0
        observation = (monitor, geometry, usable)
        if observation != self.last_observation:
            self.record('monitor-observation', geometry=geometry, usable=usable,
                        same_presented_output=monitor is not None and monitor == self.monitor)
            self.last_observation = observation
        if monitor is None:
            if self.monitor is not None:
                if self.window.get_visible():
                    self.window.hide()
                self.record('output-removed', generation=self.generation)
                self.monitor = None
            return False
        if not usable:
            # monitor-added alone is not an output configuration. Do not map
            # before its geometry is usable, nor retry an already mapped window.
            return False
        if monitor != self.monitor:
            if self.monitor is not None and self.window.get_visible():
                self.window.hide()
            self.monitor = monitor
            self.generation += 1
            self.record('output-present', generation=self.generation, geometry=geometry)
            # Request the intended state BEFORE the first map/configure cycle.
            self.window.fullscreen()
            self.record('fullscreen-request', generation=self.generation)
            self.window.show_all()
            self.record('show-request', generation=self.generation)
            self.window.present()
            self.record('present-request', generation=self.generation)
            self.area.grab_focus()
            self.area.queue_draw()
        return True

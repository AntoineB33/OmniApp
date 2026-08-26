#!/usr/bin/env python3
"""
tests_displayer_v2.py
The display for `scheduler_v2`, over the cases in `test_configs_v2.py`.

This is `tests_displayer.py`'s counterpart for the second implementation, and
it keeps the two rules that shaped the original:

* NOTHING THAT COSTS MORE THAN A FRAME RUNS ON THE THREAD THAT DRAWS. Tests 12
  to 14 settle their chain on a worker thread which OWNS the scheduler; the
  window only ever draws snapshots that worker publishes, and asks it for
  things (move the line, change the mode, copy the rules) through a queue. A
  window that settled between frames would not be a window that answers.
* THE LINE IS MOVED HERE, and the schedule is read back for the position it
  lands on. Tests 10 and 11 are cheap enough to answer between frames; test 12's
  three days are not, which is the whole reason the chain exists.

What is on screen, per case: the heading and what the case is about, the rule
state (every task with its percentage, its minimum and the share it actually
bought), the starting timeline's periods, and the schedule itself as a bar --
with the line drawn on it where t_p stands, and, for the progressive cases, a
mark where the definitive part ends.

Buttons, per case: PLAY/PAUSE the sweep, MODE 1/2 (the two t_p modes: mode 1
keeps the line clear of any period that refuses the marked tasks, mode 2 keeps
it inside one), and COPY -- which puts the test configuration and the resulting
set of rules on the clipboard, in the readable form `tests.md` asks for.

    uv run tests_displayer_v2.py              the window
    uv run tests_displayer_v2.py --verify     every check, no window
    uv run tests_displayer_v2.py --rules      the rule list of each case
    uv run tests_displayer_v2.py --no-ui      the terminal report
"""

from __future__ import annotations

import argparse
import queue
import threading
import time
from fractions import Fraction

try:
    import tkinter as tk
except ImportError:                                     # headless box: CLI only
    tk = None

from scheduler_v2 import DAY, HOUR, IDLE, frac, human, resulting_shares
from test_configs_v2 import (
    Case,
    build_cases,
    build_moving_cases,
    build_progressive_cases,
    configuration_text,
    report_text,
    rules_text,
    shares_line,
    verify_all,
)

# --------------------------------------------------------------------------- #
#  layout
# --------------------------------------------------------------------------- #

WIDTH = 1060
MARGIN = 16
BAR_H = 34
ROW_GAP = 12
IDLE_COLOR = "#EFEFEF"
GRID_COLOR = "#DDDDDD"
LINE_COLOR = "#C62828"
FRONT_COLOR = "#2E7D32"
TEXT = "#222222"
DIM = "#777777"
FONT = ("Segoe UI", 9)
FONT_B = ("Segoe UI", 10, "bold")
FONT_M = ("Consolas", 9)


def tick_step(span) -> Fraction:
    """A round number of minutes that leaves eight to sixteen ticks."""
    for step in (Fraction(5), Fraction(10), Fraction(20), Fraction(30), HOUR,
                 2 * HOUR, 4 * HOUR, 6 * HOUR, 12 * HOUR, DAY, 2 * DAY):
        if span / step <= 16:
            return step
    return span / 8


def clock(t) -> str:
    """An instant as a clock reading, for the long cases: day + hh:mm."""
    t = frac(t)
    if t < DAY:
        return human(t)
    d, rest = divmod(t, DAY)
    h, m = divmod(rest, HOUR)
    return f"d{int(d) + 1} {int(h):02d}:{int(m):02d}"


def task_color(case, name) -> str:
    if name == IDLE:
        return IDLE_COLOR
    for spec in case.sched.specs_at(0):
        if spec.name == name:
            return spec.color
    return "#CCCCCC"


def task_rows(case, tl):
    """Every task with what it asked for and what it got."""
    got = resulting_shares(tl)
    specs = case.sched.specs_at(case.sched.t_p)
    total = sum((s.priority for s in specs), Fraction(0)) or Fraction(1)
    rows = []
    for spec in specs:
        rows.append((spec.name, float(spec.priority / total) * 100,
                     spec.min_time, float(got.get(spec.name, 0)) * 100, spec.color))
    return rows


# --------------------------------------------------------------------------- #
#  a snapshot: everything the window needs in order to draw one case
# --------------------------------------------------------------------------- #

class Snapshot:
    __slots__ = ("t_p", "mode", "front", "placements", "periods", "note")

    def __init__(self, t_p, mode, front, placements, periods, note=""):
        self.t_p, self.mode, self.front = t_p, mode, front
        self.placements, self.periods, self.note = placements, periods, note


def snapshot_of(case, t_p=None, mode=1, note="") -> Snapshot:
    sched = case.sched
    t_p = sched.t_p if t_p is None else frac(t_p)
    return Snapshot(t_p, mode, sched.front, sched.timeline(t_p, mode),
                    sched.dynamic_periods(t_p, mode), note)


# --------------------------------------------------------------------------- #
#  the worker that owns a progressive case
# --------------------------------------------------------------------------- #

class Deriver(threading.Thread):
    """Owns one progressive case's scheduler.

    The window never touches that scheduler: it posts commands here and draws
    the snapshots that come back. Settling a link of test 12's chain is a
    fraction of a second against a frame of a twentieth, so a window that did
    it between frames would stutter -- and one that did it while the line moved
    would stop answering the mouse.
    """

    def __init__(self, case: Case):
        super().__init__(daemon=True)
        self.case = case
        self.commands = queue.Queue()
        self.snapshots = queue.Queue()
        self.mode = 1
        self._stop = threading.Event()
        self._active = threading.Event()
        self._active.set()
        self.teleported = False

    # -- the window's side ---------------------------------------------------

    def send(self, kind, value=None):
        self.commands.put((kind, value))

    def latest(self):
        snap = None
        while True:
            try:
                snap = self.snapshots.get_nowait()
            except queue.Empty:
                return snap

    def stop(self):
        self._stop.set()
        self._active.set()

    # -- the worker's side ---------------------------------------------------

    def run(self):
        sched = self.case.sched
        self._publish("waiting at the origin while the chain settles")
        while not self._stop.is_set():
            if not self._active.wait(timeout=0.2):
                continue
            moved = self._drain(sched)
            settled = False
            if sched.front < sched.horizon:
                sched.settle(budget_seconds=0.25)
                settled = True
            # test 12's jump: the line waits at the origin and lands on the
            # first day the moment that day will not move again
            if (not self.teleported and self.case.tp_sweep is not None
                    and sched.front >= self.case.tp_sweep and sched.t_p < self.case.tp_sweep):
                sched.teleport_to(self.case.tp_sweep, self.mode)
                self.teleported = True
                moved = True
            if moved or settled:
                self._publish()
            if not settled and not moved:
                time.sleep(0.05)

    def _drain(self, sched) -> bool:
        moved = False
        while True:
            try:
                kind, value = self.commands.get_nowait()
            except queue.Empty:
                return moved
            if kind == "t_p":
                target = max(frac(value), sched.t_p)
                if target != sched.t_p:
                    sched.advance_to(target, self.mode)
                    moved = True
            elif kind == "mode":
                self.mode = int(value)
                sched.advance_to(sched.t_p, self.mode)
                moved = True
            elif kind == "copy":
                value(report_text(self.case, sched.t_p, self.mode))

    def _publish(self, note=""):
        sched = self.case.sched
        if not note:
            done = sched.front >= sched.horizon
            note = (f"definitive to {clock(sched.front)}"
                    + ("" if done else " and growing")) if not done else "settled whole"
        self.snapshots.put(snapshot_of(self.case, sched.t_p, self.mode, note))


# --------------------------------------------------------------------------- #
#  one case on the canvas
# --------------------------------------------------------------------------- #

class Panel:
    """A case drawn at a fixed y on the shared canvas.

    Everything above the bar (the heading, the tasks, the periods) is drawn
    once; the bar, the line and the status are redrawn from a snapshot.
    """

    def __init__(self, app, case: Case, y: int, width=WIDTH):
        self.app, self.case, self.y0, self.width = app, case, y, width
        self.canvas = app.canvas
        self.items = []                      # what a redraw clears
        self.snapshot = snapshot_of(case, case.tp_start, 1)
        self.x0 = MARGIN + 4
        self.x1 = width - MARGIN - 4
        self.rows = task_rows(case, self.snapshot.placements)
        self.height = self._static_height()
        self.bar_y = self.y0 + self.height - BAR_H - 26
        self._draw_static()
        self.redraw()

    # -- geometry ------------------------------------------------------------

    def _static_height(self):
        lines = 2 + (len(self.rows) + 2) // 3 + min(len(self.case.sched.base.periods), 3)
        return 26 + 14 * lines + BAR_H + 40

    def x_of(self, t):
        span = self.case.span - self.case.sched.t_start
        f = float((frac(t) - self.case.sched.t_start) / span)
        return self.x0 + (self.x1 - self.x0) * min(max(f, 0.0), 1.0)

    def t_of(self, x):
        span = self.case.span - self.case.sched.t_start
        f = (x - self.x0) / max(self.x1 - self.x0, 1)
        return self.case.sched.t_start + span * frac(min(max(f, 0.0), 1.0))

    def hit(self, x, y) -> bool:
        return self.bar_y - 4 <= y <= self.bar_y + BAR_H + 4 and self.x0 - 6 <= x <= self.x1 + 6

    # -- the parts that never move -------------------------------------------

    def _draw_static(self):
        c, y = self.canvas, self.y0 + 6
        c.create_text(MARGIN, y, anchor="nw", text=self.case.heading, fill=TEXT, font=FONT_B)
        y += 16
        c.create_text(MARGIN, y, anchor="nw", text="-> " + self.case.note, fill=DIM,
                      font=FONT, width=self.width - 2 * MARGIN)
        y += 14 * (1 + len(self.case.note) // 130)
        # the tasks, three to a line
        for i in range(0, len(self.rows), 3):
            x = MARGIN
            for name, pct, mn, _got, color in self.rows[i:i + 3]:
                c.create_rectangle(x, y + 2, x + 9, y + 11, fill=color, outline="#999999")
                c.create_text(x + 13, y, anchor="nw", font=FONT_M, fill=TEXT,
                              text=f"{name} {pct:g}% min {human(mn)}")
                x += 250
            y += 14
        for p in self.case.sched.base.periods[:3]:
            c.create_text(MARGIN, y, anchor="nw", font=FONT_M, fill=DIM,
                          text=f"period {p.label or p.kind}: {clock(p.start)} .. {clock(p.end)}")
            y += 14
        if len(self.case.sched.base.periods) > 3:
            c.create_text(MARGIN, y - 14, anchor="nw", font=FONT_M, fill=DIM,
                          text=f"    (+{len(self.case.sched.base.periods) - 3} more periods)")
        self.tasks_bottom = y

    # -- the parts a snapshot redraws ----------------------------------------

    def redraw(self, snap: Snapshot = None):
        if snap is not None:
            self.snapshot = snap
        c = self.canvas
        for item in self.items:
            c.delete(item)
        self.items = []
        snap = self.snapshot
        y, h = self.bar_y, BAR_H
        self.items.append(c.create_rectangle(self.x0, y, self.x1, y + h,
                                             fill="#FFFFFF", outline="#AAAAAA"))
        # the schedule
        px = 0.0
        for pl in snap.placements:
            a, b = self.x_of(pl.start), self.x_of(pl.end)
            if b - a < 0.6 and a < px:
                continue                     # narrower than a pixel and already covered
            px = b
            color = IDLE_COLOR if pl.task == IDLE else task_color(self.case, pl.task)
            self.items.append(c.create_rectangle(a, y + 1, max(b, a + 0.8), y + h - 1,
                                                 fill=color, outline=""))
            if b - a > 26:
                self.items.append(c.create_text((a + b) / 2, y + h / 2, text=pl.task,
                                                font=FONT, fill="#333333"))
        # the ticks
        step = tick_step(self.case.span - self.case.sched.t_start)
        t = self.case.sched.t_start
        while t <= self.case.span:
            x = self.x_of(t)
            self.items.append(c.create_line(x, y + h, x, y + h + 4, fill="#999999"))
            self.items.append(c.create_text(x, y + h + 6, anchor="n", text=clock(t),
                                            font=("Segoe UI", 7), fill=DIM))
            t += step
        # the front of the definitive part
        if self.case.kind == "progressive" and snap.front < self.case.span:
            fx = self.x_of(snap.front)
            self.items.append(c.create_line(fx, y - 3, fx, y + h + 3, fill=FRONT_COLOR, width=2))
            self.items.append(c.create_text(fx + 3, y - 12, anchor="nw", text="definitive",
                                            font=("Segoe UI", 7), fill=FRONT_COLOR))
        # the line
        if self.case.kind != "static":
            lx = self.x_of(snap.t_p)
            self.items.append(c.create_line(lx, y - 8, lx, y + h + 3, fill=LINE_COLOR, width=2))
            self.items.append(c.create_polygon(lx - 4, y - 8, lx + 4, y - 8, lx, y - 2,
                                               fill=LINE_COLOR, outline=""))
        # the status
        alt = self._alternative(snap)
        text = f"shares: {shares_line(snap.placements, 6)}"
        if self.case.kind != "static":
            text = (f"t_p = {clock(snap.t_p)}   mode {snap.mode}   "
                    f"alternative here: {alt}   " + text)
        if snap.note:
            text += f"   [{snap.note}]"
        self.items.append(c.create_text(MARGIN, y + h + 20, anchor="nw", text=text,
                                        font=FONT_M, fill=TEXT))

    def _alternative(self, snap):
        for pl in snap.placements:
            if pl.alt and pl.start <= snap.t_p < pl.end:
                return pl.alt
        for pl in snap.placements:
            if pl.alt and pl.end > snap.t_p:
                return pl.alt + " (next)"
        return "(none)"

    # -- interaction ---------------------------------------------------------

    def set_line(self, t_p):
        pass

    def set_mode(self, mode):
        pass

    def copy(self):
        self.app.to_clipboard(report_text(self.case, self.snapshot.t_p, self.snapshot.mode),
                              self.case.name)

    def tick(self):
        pass

    def stop(self):
        pass


class MovingPanel(Panel):
    """Tests 10 and 11: the line is moved here, and the case is cheap enough
    to answer between frames.

    Answering means advancing the scheduler's own line, which COMMITS what it
    passes -- so the frozen past is a fact of the object being displayed and
    not something the display promises.
    """

    def __init__(self, app, case, y, width=WIDTH):
        self.playing = False
        self.mode = 1
        self.last_frame = None
        super().__init__(app, case, y, width)

    def set_line(self, t_p):
        t_p = max(frac(t_p), self.case.sched.t_p)
        if t_p != self.case.sched.t_p:
            self.case.sched.advance_to(t_p, self.mode)
        self.redraw(snapshot_of(self.case, self.case.sched.t_p, self.mode))

    def set_mode(self, mode):
        self.mode = int(mode)
        self.case.sched.advance_to(self.case.sched.t_p, self.mode)
        self.redraw(snapshot_of(self.case, self.case.sched.t_p, self.mode))

    def play(self, on):
        self.playing = on
        self.last_frame = time.perf_counter() if on else None

    def tick(self):
        if not self.playing:
            return
        now = time.perf_counter()
        dt = now - (self.last_frame or now)
        self.last_frame = now
        span = float(self.case.span - self.case.sched.t_start)
        step = frac(dt * span / max(self.case.sweep, 1e-6)).limit_denominator(10 ** 6)
        t_p = self.case.sched.t_p + step
        if t_p >= self.case.span:
            # the sweep is over: the case starts again from a clean line, which
            # is the only way to sweep twice -- t_p never moves backwards
            self.case = self.case.fresh()
            self.rows = task_rows(self.case, snapshot_of(self.case, self.case.tp_start, self.mode).placements)
            self.set_line(self.case.tp_start)
            return
        self.set_line(t_p)


class ProgressivePanel(Panel):
    """Tests 12 to 14: derived while they are shown, by a worker that owns the
    scheduler. The panel draws snapshots and asks for things through a queue."""

    def __init__(self, app, case, y, width=WIDTH):
        self.playing = False
        self.mode = 1
        self.last_frame = None
        self.deriver = None
        super().__init__(app, case, y, width)
        self.deriver = Deriver(case)
        self.deriver.start()

    def set_line(self, t_p):
        if self.deriver:
            self.deriver.send("t_p", frac(t_p))

    def set_mode(self, mode):
        self.mode = int(mode)
        if self.deriver:
            self.deriver.send("mode", self.mode)

    def play(self, on):
        self.playing = on
        self.last_frame = time.perf_counter() if on else None

    def copy(self):
        if self.deriver and self.deriver.is_alive():
            self.deriver.send("copy", lambda text: self.app.queue_clipboard(text, self.case.name))
        else:
            super().copy()

    def tick(self):
        snap = self.deriver.latest() if self.deriver else None
        if snap is not None:
            self.redraw(snap)
        if not self.playing:
            return
        now = time.perf_counter()
        dt = now - (self.last_frame or now)
        self.last_frame = now
        span = float(self.case.span - self.snapshot.t_p)
        if span <= 0:
            self.playing = False
            self.app.sync_buttons()
            return
        step = frac(dt * float(self.case.span) / max(self.case.sweep, 1e-6)).limit_denominator(10 ** 6)
        self.set_line(min(self.snapshot.t_p + step, self.case.span))

    def stop(self):
        if self.deriver:
            self.deriver.stop()
            self.deriver = None


# --------------------------------------------------------------------------- #
#  the window
# --------------------------------------------------------------------------- #

class Workbench:
    """The whole display: the static cases stacked, the two sliding ones, and
    ONE progressive case at a time -- chosen from the bar at the top, drawn at
    the bottom. Only the case on screen derives; choosing another leaves the
    first holding every link it settled, because the chain lives in the case's
    own scheduler and the selector only swaps the panel.
    """

    def __init__(self, root, width=WIDTH, on_ready=None):
        self.root = root
        self.width = width
        self.panels = []
        self.buttons = {}
        self._clip_queue = queue.Queue()

        bar = tk.Frame(root)
        bar.pack(side="top", fill="x")
        tk.Label(bar, text="scheduler_v2", font=FONT_B).pack(side="left", padx=(8, 12))
        self.status = tk.Label(bar, text="building...", font=FONT, fg=DIM)
        self.status.pack(side="left")
        tk.Button(bar, text="copy all", font=FONT, command=self.copy_all).pack(side="right", padx=6)

        self.prog_cases = build_progressive_cases()
        self.prog_index = len(self.prog_cases) - 1
        self.prog_var = tk.StringVar(value=self.prog_cases[self.prog_index].name)
        picker = tk.Frame(root)
        picker.pack(side="top", fill="x")
        tk.Label(picker, text="derived while shown:", font=FONT, fg=DIM).pack(side="left", padx=(8, 6))
        for i, case in enumerate(self.prog_cases):
            tk.Radiobutton(picker, text=case.name, value=case.name, variable=self.prog_var,
                           font=FONT, command=lambda i=i: self.choose_progressive(i)).pack(side="left")

        frame = tk.Frame(root)
        frame.pack(side="top", fill="both", expand=True)
        self.canvas = tk.Canvas(frame, bg="white", width=width, highlightthickness=0)
        sb = tk.Scrollbar(frame, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.canvas.pack(side="left", fill="both", expand=True)
        self.canvas.bind("<Button-1>", self._press)
        self.canvas.bind("<B1-Motion>", self._drag)
        self.canvas.bind("<MouseWheel>", self._wheel)
        root.protocol("WM_DELETE_WINDOW", self.close)

        self.on_ready = on_ready
        self.y = 0
        self._build_queue = list(build_cases()) + list(build_moving_cases())
        # the window is on screen before anything is built, and fills itself in
        root.after(20, self._build_next)
        root.after(40, self._tick)

    # -- building ------------------------------------------------------------

    def _build_next(self):
        if self._build_queue:
            case = self._build_queue.pop(0)
            self.status.config(text=f"building {case.name}...")
            self._add(case)
            self.root.after(1, self._build_next)
            return
        self.choose_progressive(self.prog_index)
        self.status.config(text="drag or click a bar to move t_p; PLAY sweeps it")
        if self.on_ready:
            self.on_ready(self)

    def _add(self, case):
        cls = {"static": Panel, "moving": MovingPanel, "progressive": ProgressivePanel}[case.kind]
        panel = cls(self, case, self.y)
        self.panels.append(panel)
        self._controls(panel)
        self.y += panel.height + ROW_GAP
        self.canvas.create_line(0, self.y - ROW_GAP / 2, self.width, self.y - ROW_GAP / 2,
                                fill=GRID_COLOR)
        self.canvas.configure(scrollregion=(0, 0, self.width, self.y))
        return panel

    def _controls(self, panel):
        """A case's own buttons, parked on the canvas beside its bar."""
        row = tk.Frame(self.canvas, bg="white")
        made = []
        if panel.case.kind != "static":
            play = tk.Button(row, text="PLAY", font=FONT, width=6,
                             command=lambda p=panel: self.toggle_play(p))
            play.pack(side="left")
            made.append(("play", play))
            mode = tk.Button(row, text="mode 1", font=FONT, width=7,
                             command=lambda p=panel: self.toggle_mode(p))
            mode.pack(side="left", padx=4)
            made.append(("mode", mode))
        copy = tk.Button(row, text="copy", font=FONT, width=6,
                         command=lambda p=panel: p.copy())
        copy.pack(side="left", padx=4)
        made.append(("copy", copy))
        win = self.canvas.create_window(self.width - MARGIN - 4, panel.y0 + 4,
                                        anchor="ne", window=row)
        self.buttons[id(panel)] = dict(made, frame=row, window=win)

    # -- the progressive selector -------------------------------------------

    def choose_progressive(self, index):
        old = next((p for p in self.panels if p.case.kind == "progressive"), None)
        if old is not None:
            if old.case is self.prog_cases[index]:
                return
            old.stop()
            self._forget(old)
        self.prog_index = index
        self.prog_var.set(self.prog_cases[index].name)
        self._add(self.prog_cases[index])

    def _forget(self, panel):
        for item in panel.items:
            self.canvas.delete(item)
        widgets = self.buttons.pop(id(panel), None)
        if widgets:
            self.canvas.delete(widgets["window"])
            widgets["frame"].destroy()
        # the panel's static text is redrawn from scratch by the next case, so
        # the region it occupied is simply cleared
        self.canvas.create_rectangle(0, panel.y0, self.width, panel.y0 + panel.height,
                                     fill="white", outline="")
        self.panels.remove(panel)
        self.y = panel.y0

    # -- interaction ---------------------------------------------------------

    def toggle_play(self, panel):
        panel.play(not panel.playing)
        self.sync_buttons()

    def toggle_mode(self, panel):
        panel.set_mode(2 if panel.mode == 1 else 1)
        self.sync_buttons()

    def sync_buttons(self):
        for panel in self.panels:
            widgets = self.buttons.get(id(panel))
            if not widgets:
                continue
            if "play" in widgets:
                widgets["play"].config(text="PAUSE" if getattr(panel, "playing", False) else "PLAY")
            if "mode" in widgets:
                widgets["mode"].config(text=f"mode {getattr(panel, 'mode', 1)}")

    def _panel_at(self, x, y):
        for panel in self.panels:
            if panel.hit(x, y):
                return panel
        return None

    def _canvas_xy(self, event):
        return self.canvas.canvasx(event.x), self.canvas.canvasy(event.y)

    def _press(self, event):
        x, y = self._canvas_xy(event)
        panel = self._panel_at(x, y)
        if panel is None or panel.case.kind == "static":
            return
        panel.play(False)
        self.sync_buttons()
        panel.set_line(panel.t_of(x))

    def _drag(self, event):
        x, y = self._canvas_xy(event)
        panel = self._panel_at(x, y)
        if panel is not None and panel.case.kind != "static":
            panel.set_line(panel.t_of(x))

    def _wheel(self, event):
        self.canvas.yview_scroll(int(-event.delta / 60), "units")

    # -- clipboard -----------------------------------------------------------

    def to_clipboard(self, text, what=""):
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        self.status.config(text=f"{what} copied: {len(text.splitlines())} lines on the clipboard")

    def queue_clipboard(self, text, what=""):
        """A worker asked for a copy; the clipboard belongs to the UI thread."""
        self._clip_queue.put((text, what))

    def copy_all(self):
        parts = []
        for panel in self.panels:
            parts.append(report_text(panel.case, panel.snapshot.t_p, panel.snapshot.mode))
            parts.append("\n" + "=" * 72 + "\n")
        self.to_clipboard("\n".join(parts), "every case on screen")

    # -- the frame -----------------------------------------------------------

    def _tick(self):
        try:
            while True:
                text, what = self._clip_queue.get_nowait()
                self.to_clipboard(text, what)
        except queue.Empty:
            pass
        for panel in list(self.panels):
            panel.tick()
        self.root.after(50, self._tick)

    def report(self):
        """What the window is showing, in one line per case -- the self-test's
        way of saying that it really drew, swept and settled something."""
        out = []
        for panel in self.panels:
            snap = panel.snapshot
            out.append(f"  {panel.case.name:<9} {len(snap.placements):>5} placements, "
                       f"{len(panel.items):>5} canvas items, t_p={clock(snap.t_p)}"
                       + (f", definitive to {clock(snap.front)}"
                          if panel.case.kind == "progressive" else ""))
        return "\n".join(out)

    def close(self):
        for panel in self.panels:
            panel.stop()
        self.root.destroy()


# --------------------------------------------------------------------------- #
#  the terminal
# --------------------------------------------------------------------------- #

def print_terminal_results(cases=None, settle_seconds=1.0):
    cases = cases or (build_cases() + build_moving_cases() + build_progressive_cases())
    for case in cases:
        print(configuration_text(case))
        if case.kind == "progressive":
            case.sched.settle(budget_seconds=settle_seconds)
            print(f"  settled to {human(case.sched.front)} in {settle_seconds}s")
        tl = case.sched.timeline(case.tp_start, 1)
        print(f"\nresulting shares: {shares_line(tl, 24)}")
        print("=" * 78 + "\n")


def print_rules(cases=None, max_rules=24):
    cases = cases or (build_moving_cases() + build_progressive_cases())
    for case in cases:
        t_p = case.tp_start
        if case.kind == "progressive":
            case.sched.settle(budget_seconds=1.0)
            t_p = case.tp_sweep or case.tp_start
            case.sched.teleport_to(t_p, 1)
            # tests 12 and 13 land at midnight, which is inside the night, so
            # the rules there are one long "nobody may run". The rules worth
            # printing are the ones the line meets once the day opens.
            nxt = next((pl.start for pl in case.sched.timeline(t_p, 1)
                        if pl.task != IDLE and pl.start >= t_p), None)
            if nxt is not None and nxt > t_p:
                case.sched.advance_to(nxt, 1)
                t_p = nxt
        print(case.heading)
        print(rules_text(case, t_p, 1, max_rules=max_rules))
        print()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    ap.add_argument("--verify", action="store_true", help="run every check, no window")
    ap.add_argument("--rules", action="store_true", help="print the rule list of each case")
    ap.add_argument("--no-ui", action="store_true", help="the terminal report only")
    ap.add_argument("--self-test", type=float, default=0.0, metavar="SECONDS",
                    help="open the window, sweep every case, then close it")
    args = ap.parse_args(argv)

    if args.verify:
        return 1 if verify_all() else 0
    if args.rules:
        print_rules()
        return 0
    if args.no_ui or tk is None:
        if tk is None and not args.no_ui:
            print("tkinter is not available: printing the terminal report instead.\n")
        print_terminal_results()
        return 0

    print("--- the window ---")
    print("  it opens now, empty, and fills itself in from the top: the cases that")
    print("  DERIVE while they are shown (tests 12 to 14) are chosen one at a time")
    print("  from the bar at the top and drawn at the bottom, the last of them by")
    print("  default. Only the case on screen derives, and a case chosen again")
    print("  resumes from the link it had reached.")
    print("  Click or drag a bar to move t_p, PLAY sweeps it, mode 1/2 switches the")
    print("  two t_p modes, and copy puts the configuration and the rules on the")
    print("  clipboard. The checks are `uv run tests_displayer_v2.py --verify`.\n",
          flush=True)

    root = tk.Tk()
    root.title("scheduler_v2 -- the tests of tests.md, and the rules they resolve to")
    root.geometry(f"{WIDTH + 20}x760")
    bench = Workbench(root, width=WIDTH)
    if args.self_test:
        def sweep():
            for panel in bench.panels:
                if panel.case.kind != "static":
                    panel.play(True)
            bench.sync_buttons()
        def exercise():
            # the paths a hand would take: switch the case being derived,
            # flip a mode, and take a copy
            bench.choose_progressive(0)
            for panel in bench.panels:
                if panel.case.kind != "static":
                    panel.set_mode(2)
                    panel.copy()
                    break
            bench.sync_buttons()

        def finish():
            print("--- what the window drew ---")
            print(bench.report(), flush=True)
            bench.close()
        root.after(300, sweep)
        root.after(max(int(args.self_test * 500), 800), exercise)
        root.after(int(args.self_test * 1000), finish)
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

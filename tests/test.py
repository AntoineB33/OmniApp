import math
import time

import matplotlib.colors as mcolors
import matplotlib.pyplot as plt


class Task:
    def __init__(self, name, priority, min_time):
        self.name = name
        self.priority = priority / 100.0  # Normalize percentage to 0-1
        self.min_time = min_time


class TimelineResolver:
    """Dynamically resolves repeating and static blocks for infinite timelines."""
    def __init__(self, defs):
        self.defs = defs or []

    def get_blocks_in_range(self, t_start, t_end, filter_task=None):
        blocks = []
        for b in self.defs:
            if filter_task and b.get('task') not in (filter_task, None):
                continue
            rep = b.get('repeat', 0)
            s, e = b['start'], b['end']
            duration = e - s
            
            if rep > 0:
                start_k = math.floor((t_start - s) / rep) if t_start >= s else 0
                k = start_k
                while True:
                    curr_s = s + k * rep
                    curr_e = curr_s + duration
                    if curr_s >= t_end:
                        break
                    if curr_e > t_start:
                        out_b = b.copy()
                        out_b.update({'start': curr_s, 'end': curr_e})
                        blocks.append(out_b)
                    k += 1
            else:
                if e > t_start and s < t_end:
                    out_b = b.copy()
                    out_b.update({'start': s, 'end': e})
                    blocks.append(out_b)
                    
        if not blocks: return []
        blocks.sort(key=lambda x: x['start'])
        
        # Merge overlapping blocks 
        merged = [blocks[0].copy()]
        for b in blocks[1:]:
            if b['start'] <= merged[-1]['end']:
                merged[-1]['end'] = max(merged[-1]['end'], b['end'])
                # Union of accepted tasks for periods
                if 'accepted_tasks' in b and 'accepted_tasks' in merged[-1]:
                    merged[-1]['accepted_tasks'] = list(set(merged[-1]['accepted_tasks'] + b['accepted_tasks']))
            else:
                merged.append(b.copy())
        return merged

    def get_next_event_time(self, t):
        ns = float('inf')
        eps = 1e-6  # Margin to prevent returning the exact current time
        for b in self.defs:
            rep = b.get('repeat', 0)
            s, e = b['start'], b['end']
            if rep > 0:
                if t + eps < s:
                    ns = min(ns, s)
                else:
                    k = math.floor((t + eps - s) / rep)
                    curr_s = s + k * rep
                    curr_e = curr_s + (e - s)
                    if t + eps < curr_e:
                        ns = min(ns, curr_e) # We are inside, next event is end
                    else:
                        ns = min(ns, s + (k + 1) * rep) # Next event is next start
            else:
                if t + eps < s:
                    ns = min(ns, s)
                elif t + eps < e:
                    ns = min(ns, e)
        return ns


class SchedulerFunction:
    """
    A callable scheduler that acts as a function f(time) -> Task | None.
    It generates the timeline dynamically and caches history for fast querying.
    """
    def __init__(self, tasks, starting_timeline=None, periods=None, half_life=30.0):
        self.tasks = tasks
        self.half_life = half_life
        self.k = math.log(2) / self.half_life if self.half_life else 0.0
        self.C = sum(t.min_time for t in self.tasks)
        self.debts = {t.name: 0.0 for t in self.tasks}
        
        self.timeline_res = TimelineResolver(starting_timeline)
        self.periods_res = TimelineResolver(periods)
        
        self.generator = self._run()
        self.history = []

    def __call__(self, t):
        if t < 0: return None
        while not self.history or self.history[-1]['end'] <= t:
            try:
                self.history.append(next(self.generator))
            except StopIteration:
                break
        for b in self.history:
            if b['start'] <= t < b['end']:
                if b['task'] is None: return None
                return next((task for task in self.tasks if task.name == b['task']), None)
        return None

    def _simulate_interval(self, dt, task_running):
        for t in self.tasks:
            name = t.name
            e_val = 1.0 if name == task_running else 0.0
            R = t.priority - e_val
            t_rem = dt
            
            while t_rem > 1e-9:
                d = self.debts[name]
                eps = 1e-7
                
                # Excess debt (above C)
                if d > self.C + eps or (d >= self.C - eps and R > 0):
                    y0 = max(0.0, d - self.C)
                    denominator = R - self.k * y0
                    ratio = R / denominator if abs(denominator) > 1e-12 else -1.0
                    target_t = -math.log(ratio) / self.k if (self.k > 0 and 0 < ratio < (1.0 - eps)) else float('inf')
                    
                    step_t = min(t_rem, target_t)
                    self.debts[name] = self.C + R/self.k + (y0 - R/self.k) * math.exp(-self.k * step_t) if self.k > 0 else d + R * step_t
                    t_rem -= step_t
                    if abs(self.debts[name] - self.C) < eps: self.debts[name] = self.C

                # Deficit debt (below -C)
                elif d < -self.C - eps or (d <= -self.C + eps and R < 0):
                    y0 = min(0.0, d + self.C)
                    denominator = R - self.k * y0
                    ratio = R / denominator if abs(denominator) > 1e-12 else -1.0
                    target_t = -math.log(ratio) / self.k if (self.k > 0 and 0 < ratio < (1.0 - eps)) else float('inf')
                    
                    step_t = min(t_rem, target_t)
                    self.debts[name] = -self.C + R/self.k + (y0 - R/self.k) * math.exp(-self.k * step_t) if self.k > 0 else d + R * step_t
                    t_rem -= step_t
                    if abs(self.debts[name] - (-self.C)) < eps: self.debts[name] = -self.C

                # Inside safe boundaries [-C, C]
                else:
                    target_t = (self.C - d) / R if R > eps else ((-self.C - d) / R if R < -eps else float('inf'))
                    step_t = min(t_rem, target_t)
                    self.debts[name] += R * step_t
                    t_rem -= step_t
                    if abs(self.debts[name] - self.C) < eps: self.debts[name] = self.C
                    elif abs(self.debts[name] - (-self.C)) < eps: self.debts[name] = -self.C

    def _run(self):
        past_blocks = self.timeline_res.get_blocks_in_range(-10000, 0)
        current_sim_time = past_blocks[0]['start'] if past_blocks else 0
        
        for b in past_blocks:
            s_eff = min(0, b['start'])
            e_eff = min(0, b['end'])
            if s_eff > current_sim_time:
                self._simulate_interval(s_eff - current_sim_time, None)
                current_sim_time = s_eff
            if e_eff > current_sim_time:
                dur = e_eff - current_sim_time
                self._simulate_interval(dur, b['task'])
                current_sim_time = e_eff
            
        if current_sim_time < 0:
            self._simulate_interval(0 - current_sim_time, None)
            
        current_time = 0.0
        lookahead_horizon = 5 * self.half_life if self.half_life else 500
        
        while True:
            # 1. Overlapping pre-scheduled block
            active_pre = self.timeline_res.get_blocks_in_range(current_time, current_time + 1e-5)
            overlapping_block = next((b for b in active_pre if b['start'] <= current_time + 1e-7 and b['end'] > current_time + 1e-7), None)
            
            if overlapping_block:
                b = overlapping_block
                dur = b['end'] - current_time
                yield {'task': b['task'], 'start': current_time, 'duration': dur, 'end': b['end'], 'type': 'pre-scheduled'}
                self._simulate_interval(dur, b['task'])
                current_time = b['end']
                continue
                
            next_event_timeline = self.timeline_res.get_next_event_time(current_time)
            next_event_period = self.periods_res.get_next_event_time(current_time)
            next_interrupt = min(next_event_timeline, next_event_period)
            
            # 2. Identify allowed tasks based on periods
            accepted_tasks = [t.name for t in self.tasks]
            active_periods = self.periods_res.get_blocks_in_range(current_time, current_time + 1e-5)
            overlapping_period = next((p for p in active_periods if p['start'] <= current_time + 1e-7 and p['end'] > current_time + 1e-7), None)
            
            if overlapping_period:
                accepted_tasks = overlapping_period['accepted_tasks']
            elif self.periods_res.defs:
                # Outside period bounds entirely
                if next_interrupt == float('inf'): break
                dur = next_interrupt - current_time
                yield {'task': None, 'start': current_time, 'duration': dur, 'end': next_interrupt, 'type': 'gap (outside periods)'}
                self._simulate_interval(dur, None)
                current_time = next_interrupt
                continue

            gap = next_interrupt - current_time
            valid_tasks = [t for t in self.tasks if t.min_time <= gap + 1e-7 and t.name in accepted_tasks]
            
            if not valid_tasks:
                if next_interrupt == float('inf'): break
                dur = next_interrupt - current_time
                yield {'task': None, 'start': current_time, 'duration': dur, 'end': next_interrupt, 'type': 'gap (no valid tasks)'}
                self._simulate_interval(dur, None)
                current_time = next_interrupt
                continue

            # 3. Calculate scores based on dynamic future horizon
            best_task = valid_tasks[0]  
            best_score = float('-inf')
            
            for t in valid_tasks:
                f_eff = 0.0
                future_blocks = self.timeline_res.get_blocks_in_range(current_time, current_time + lookahead_horizon, filter_task=t.name)
                
                for mb in future_blocks:
                    # Clip the block's influence strictly to the lookahead horizon
                    mb_s = max(current_time, mb['start'])
                    mb_e = min(current_time + lookahead_horizon, mb['end'])
                    if mb_e > mb_s:
                        D = mb_e - mb_s
                        dist = mb_s - current_time
                        if D > self.C:
                            f_eff += self.C + (D - self.C) * (math.exp(-self.k * dist) if self.k > 0 else 1.0)
                        else:
                            f_eff += D
                        
                total_debt = self.debts[t.name] - f_eff
                safe_priority = max(t.priority, 1e-9)
                score = total_debt / safe_priority
                
                if score > best_score:
                    best_score = score
                    best_task = t
            
            duration = best_task.min_time
            min_valid = min(t.min_time for t in valid_tasks)
            if gap - duration < min_valid and gap >= duration - 1e-7:
                duration = gap
                
            yield {'task': best_task.name, 'start': current_time, 'duration': duration, 'end': current_time + duration, 'type': 'scheduled'}
            self._simulate_interval(duration, best_task.name)
            current_time += duration


def extract_pattern(tasks, starting_timeline, periods, half_life=30.0, max_sim_time=5000):
    """
    Finds the exact O(1) repeating rules by simulating the timeline until the internal state loops.
    """
    intervals = [x['repeat'] for x in (starting_timeline or []) + (periods or []) if x.get('repeat', 0) > 0]
    period_lcm = intervals[0] if intervals else 1
    for i in intervals[1:]:
        period_lcm = abs(period_lcm * i) // math.gcd(int(period_lcm), int(i))
        
    scheduler = SchedulerFunction(tasks, starting_timeline, periods, half_life)
    seen_states = {}
    history = []
    
    for b in scheduler.generator:
        if b['start'] > max_sim_time:
            break
            
        # Snapshot state (rounding is mandatory due to float math on continuous exponential decay)
        rounded_debts = tuple(round(scheduler.debts[t.name], 2) for t in tasks)
        phase = round(b['start'] % period_lcm, 2) if period_lcm > 1 else 0
        state_key = (rounded_debts, phase)
        
        if state_key in seen_states:
            loop_idx = seen_states[state_key]
            return history[:loop_idx], history[loop_idx:]
            
        seen_states[state_key] = len(history)
        history.append(b)
        
    return history, []


def get_schedule(tasks, time_limit, starting_timeline=None, periods=None):
    hl = HALF_LIFE if 'HALF_LIFE' in globals() else 30.0
    scheduler_func = SchedulerFunction(tasks, starting_timeline, periods, half_life=hl)
    
    scheduler_func(time_limit)
    
    schedule = []
    for b in scheduler_func.history:
        if b['start'] >= time_limit:
            break
        if b['task'] is not None:
            schedule.append(b)
            
    return schedule


def visualize_schedule(schedule, tasks, time_limit, ax, title):
    colors = list(mcolors.TABLEAU_COLORS.values())
    display_tasks = tasks[:8]  # Render a max of 8 tasks to avoid crashing UI when testing performance scale
    task_colors = {task.name: colors[i % len(colors)] for i, task in enumerate(display_tasks)}
    
    task_names = [t.name for t in display_tasks]
    y_ticks = []
    y_labels = []

    for i, name in enumerate(task_names):
        task_blocks = []
        for b in schedule:
            if b['task'] == name:
                start_clamped = max(0, b['start'])
                end_clamped = min(time_limit, b['end'])
                if end_clamped > start_clamped:
                    task_blocks.append((start_clamped, end_clamped - start_clamped))
        
        y_pos = i * 10
        if task_blocks:
            ax.broken_barh(task_blocks, (y_pos + 1, 8), facecolors=task_colors[name], edgecolor='black')
        
        y_ticks.append(y_pos + 5)
        y_labels.append(name)

    stats_text = "Target vs Actual (Shown):\n"
    for task in display_tasks:
        actual_time = 0
        for b in schedule:
            if b['task'] == task.name:
                start_clamped = max(0, b['start'])
                end_clamped = min(time_limit, b['end'])
                if end_clamped > start_clamped:
                    actual_time += end_clamped - start_clamped
        actual_pct = (actual_time / time_limit * 100) if time_limit > 0 else 0
        stats_text += f"\n{task.name}: Target {task.priority*100:.0f}%, Actual {actual_pct:.1f}%"

    ax.set_yticks(y_ticks)
    ax.set_yticklabels(y_labels)
    ax.set_xlabel('Time (minutes)')
    ax.set_title(title)
    ax.set_xlim(0, time_limit)
    ax.grid(True, axis='x', linestyle='--', alpha=0.7)
    
    props = {'boxstyle': 'round', 'facecolor': 'wheat', 'alpha': 0.5}
    ax.text(1.02, 0.95, stats_text, transform=ax.transAxes, fontsize=10, verticalalignment='top', bbox=props)


def show_scrollable(fig, window_title="Scheduler tests"):
    try:
        import tkinter as tk
        from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg
    except ImportError:
        plt.show()
        return

    root = tk.Tk()
    root.title(window_title)
    root.geometry("1250x900")

    scroll_canvas = tk.Canvas(root, borderwidth=0, highlightthickness=0)
    vbar = tk.Scrollbar(root, orient=tk.VERTICAL, command=scroll_canvas.yview)
    scroll_canvas.configure(yscrollcommand=vbar.set)
    vbar.pack(side=tk.RIGHT, fill=tk.Y)
    scroll_canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

    holder = tk.Frame(scroll_canvas)
    window_id = scroll_canvas.create_window((0, 0), window=holder, anchor="nw")

    fig_canvas = FigureCanvasTkAgg(fig, master=holder)
    widget = fig_canvas.get_tk_widget()
    fig_w, fig_h = (int(v * fig.dpi) for v in fig.get_size_inches())
    widget.configure(width=fig_w, height=fig_h)
    widget.pack(fill=tk.BOTH, expand=True)
    fig_canvas.draw()

    def on_holder_configure(_event=None):
        scroll_canvas.configure(scrollregion=scroll_canvas.bbox("all"))
    def on_canvas_configure(event):
        scroll_canvas.itemconfigure(window_id, width=event.width)
    def on_mousewheel(event):
        scroll_canvas.yview_scroll(-event.delta // 120, "units")

    holder.bind("<Configure>", on_holder_configure)
    scroll_canvas.bind("<Configure>", on_canvas_configure)
    root.bind_all("<MouseWheel>", on_mousewheel)            
    root.bind_all("<Button-4>", lambda e: scroll_canvas.yview_scroll(-1, "units"))  
    root.bind_all("<Button-5>", lambda e: scroll_canvas.yview_scroll(1, "units"))

    root.mainloop()


if __name__ == "__main__":
    HALF_LIFE = 30.0
    TEST_COUNT = 13  # Added Test 13
    SUBPLOT_HEIGHT_IN = 3.5
    fig, axes = plt.subplots(TEST_COUNT, 1, figsize=(14, SUBPLOT_HEIGHT_IN * TEST_COUNT))

    # ==========================================
    # TEST 1: Original base behavior
    # ==========================================
    tasks_1 = [Task(name="Task A", priority=50, min_time=10), Task(name="Task B", priority=30, min_time=15), Task(name="Task C", priority=20, min_time=5)]
    time_limit_1 = 100
    schedule_1 = get_schedule(tasks_1, time_limit_1, starting_timeline=[])
    visualize_schedule(schedule_1, tasks_1, time_limit_1, axes[0], "Test 1: Original Scenario (No Starting Timeline)")

    # ==========================================
    # TEST 2: Ignoring Debt but Shifting Start
    # ==========================================
    tasks_2 = [Task(name="Task A", priority=50, min_time=10), Task(name="Task B", priority=50, min_time=10)]
    time_limit_2 = 100
    schedule_2 = get_schedule(tasks_2, time_limit_2, starting_timeline=[{'task': 'Task A', 'start': -1000, 'end': 0}])
    visualize_schedule(schedule_2, tasks_2, time_limit_2, axes[1], "Test 2: Massive Task A past debt decays")

    # ==========================================
    # TEST 3: Infinite Pattern pre-scheduled (Bidirectional Check) - FIXED WITH REPEAT
    # ==========================================
    tasks_3 = [Task(name="Task A", priority=50, min_time=10), Task(name="Task B", priority=50, min_time=10)]
    time_limit_3 = 400
    # True infinite timeline declaration using 'repeat'
    starting_timeline_3 = [{'task': 'Task A', 'start': 0, 'end': 60, 'repeat': 180}]
    schedule_3 = get_schedule(tasks_3, time_limit_3, starting_timeline=starting_timeline_3)
    visualize_schedule(schedule_3, tasks_3, time_limit_3, axes[2], "Test 3: Infinite repeating blocks using 'repeat' key")

    # ==========================================
    # TEST 4: Double massive debt
    # ==========================================
    tasks_4 = [Task(name="Task A", priority=45, min_time=45), Task(name="Task B", priority=45, min_time=45), Task(name="Task C", priority=10, min_time=45)]
    time_limit_4 = 2000
    schedule_4 = get_schedule(tasks_4, time_limit_4, starting_timeline=[{'task': 'Task A', 'start': -1000, 'end': 0}, {'task': 'Task B', 'start': -1000, 'end': 0}])
    visualize_schedule(schedule_4, tasks_4, time_limit_4, axes[3], "Test 4: Massive Task A and B before t_now (C won't drift forever)")

    # ==========================================
    # TEST 5: Exclusive Periods
    # ==========================================
    tasks_5 = [Task(name="Task A", priority=50, min_time=10), Task(name="Task B", priority=50, min_time=10)]
    time_limit_5 = 100
    schedule_5 = get_schedule(tasks_5, time_limit_5, periods=[{'start': 0, 'end': 50, 'accepted_tasks': ['Task A']}, {'start': 50, 'end': 100, 'accepted_tasks': ['Task B']}])
    visualize_schedule(schedule_5, tasks_5, time_limit_5, axes[4], "Test 5: Exclusive Periods")

    # ==========================================
    # TEST 6: Complex Intersecting Periods
    # ==========================================
    tasks_6 = [Task(name="Task A", priority=40, min_time=10), Task(name="Task B", priority=40, min_time=10), Task(name="Task C", priority=20, min_time=10)]
    time_limit_6 = 150
    schedule_6 = get_schedule(tasks_6, time_limit_6, periods=[{'start': 0, 'end': 60, 'accepted_tasks': ['Task A', 'Task C']}, {'start': 60, 'end': 120, 'accepted_tasks': ['Task B', 'Task C']}, {'start': 120, 'end': 150, 'accepted_tasks': ['Task A', 'Task B', 'Task C']}])
    visualize_schedule(schedule_6, tasks_6, time_limit_6, axes[5], "Test 6: Overlapping Allow-lists")

    # ==========================================
    # TEST 7: Looming Future Debt Simulation
    # ==========================================
    tasks_7 = [Task(name="Task A", priority=50, min_time=10), Task(name="Task B", priority=50, min_time=10)]
    time_limit_7 = 100
    starting_timeline_7 = [{'task': 'Task A', 'start': 70, 'end': 130}]
    schedule_7 = get_schedule(tasks_7, time_limit_7, starting_timeline=starting_timeline_7)
    visualize_schedule(schedule_7, tasks_7, time_limit_7, axes[6], "Test 7: Future Debt Growth (B runs pre-emptively because A looms at t=70)")

    # ==========================================
    # TEST 8: Exponential Memory Loss Simulation
    # ==========================================
    tasks_8 = [Task(name="Task A", priority=50, min_time=10), Task(name="Task B", priority=50, min_time=10)]
    time_limit_8 = 100
    schedule_8 = get_schedule(tasks_8, time_limit_8, starting_timeline=[{'task': 'Task A', 'start': -100, 'end': 0}])
    visualize_schedule(schedule_8, tasks_8, time_limit_8, axes[7], "Test 8: Exponential Decay - B catches up quickly")

    # ==========================================
    # TEST 9: Long-term percentages adapt fluidly
    # ==========================================
    tasks_9 = [Task(name="Task A", priority=25, min_time=10), Task(name="Task B", priority=25, min_time=10), Task(name="Task C", priority=50, min_time=10)]
    time_limit_9 = 2000
    schedule_9 = get_schedule(tasks_9, time_limit_9, starting_timeline=[{'task': 'Task C', 'start': -1000, 'end': 0}])
    visualize_schedule(schedule_9, tasks_9, time_limit_9, axes[8], "Test 9: Long-term Fluid Decay Priority Matching")

    # ==========================================
    # TEST 10: Huge Placed Task Mid-Run
    # ==========================================
    tasks_10 = [Task(name="Task A", priority=80, min_time=10), Task(name="Task B", priority=20, min_time=10)]
    time_limit_10 = 500
    schedule_10 = get_schedule(tasks_10, time_limit_10, starting_timeline=[{'task': 'Task B', 'start': 200, 'end': 400}])
    visualize_schedule(schedule_10, tasks_10, time_limit_10, axes[9], "Test 10: Handling large mid-run pre-scheduled blocks smoothly")

    # ==========================================
    # TEST 11: High Performance Scaling
    # ==========================================
    tasks_11 = [Task(name=f"Task {i}", priority=100.0/50, min_time=5) for i in range(50)]
    time_limit_11 = 1000
    periods_11 = [{'start': i*20, 'end': i*20+15, 'accepted_tasks': [f"Task {j}" for j in range(50)]} for i in range(100)]
    starting_timeline_11 = [{'task': f"Task {i%50}", 'start': i*20+15, 'end': i*20+20} for i in range(100)]
    
    t0 = time.time()
    schedule_11 = get_schedule(tasks_11, time_limit_11, starting_timeline=starting_timeline_11, periods=periods_11)
    t1 = time.time()
    
    visualize_schedule(schedule_11, tasks_11, time_limit_11, axes[10], f"Test 11: 50 Tasks, 100 Periods, 100 future chunks [{t1-t0:.3f}s Execution]")

    # ==========================================
    # TEST 12: Zero Priority Edge Case
    # ==========================================
    tasks_12 = [Task(name="Task A", priority=100, min_time=10), Task(name="Task B", priority=0, min_time=10)]
    time_limit_12 = 100
    periods_12 = [
        {'start': 0, 'end': 40, 'accepted_tasks': ['Task A', 'Task B']},
        {'start': 40, 'end': 60, 'accepted_tasks': ['Task B']},
        {'start': 60, 'end': 100, 'accepted_tasks': ['Task A', 'Task B']}
    ]
    schedule_12 = get_schedule(tasks_12, time_limit_12, periods=periods_12)
    visualize_schedule(schedule_12, tasks_12, time_limit_12, axes[11], "Test 12: Priority Zero (B only runs when forced by period)")

    # ==========================================
    # TEST 13: Infinite Periods Test (New)
    # ==========================================
    tasks_13 = [Task(name="Task A", priority=50, min_time=10), Task(name="Task B", priority=50, min_time=10)]
    time_limit_13 = 300
    # Demonstrating the 'repeat' keyword works for periods too
    periods_13 = [{'start': 0, 'end': 30, 'accepted_tasks': ['Task A'], 'repeat': 100}]
    schedule_13 = get_schedule(tasks_13, time_limit_13, periods=periods_13)
    visualize_schedule(schedule_13, tasks_13, time_limit_13, axes[12], "Test 13: Infinite Periodic constraints using 'repeat'")

    # ==========================================
    # Prove the O(1) Rule Extraction for the infinite pattern (Test 3)
    # ==========================================
    print("\n--- O(1) Rule Extraction for Test 3 ---")
    transient, loop = extract_pattern(tasks_3, starting_timeline_3, periods=None)
    print(f"Detected Transient setup sequence: {len(transient)} blocks.")
    print(f"Detected INFINITE Repeating sequence: {len(loop)} blocks.")
    for b in loop:
        print(f"  -> {b['task']} for {b['duration']:.1f} mins (Type: {b['type']})")

    print("\n--- Functional API Test ---")
    my_func = SchedulerFunction(tasks_1, half_life=30.0)
    test_time = 17.5
    current_task = my_func(test_time)
    print(f"At t={test_time} minutes, the executing task is: {current_task.name if current_task else 'None'}")

    fig.subplots_adjust(left=0.08, right=0.80, top=0.97, bottom=0.03, hspace=0.85)
    show_scrollable(fig)
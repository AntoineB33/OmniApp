import math

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
        lookahead_horizon = 25 * self.half_life if self.half_life else 1000
        
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
                    # Do NOT clip the block's end influence. The excess calculation requires the true duration `D`.
                    mb_s = max(current_time, mb['start'])
                    mb_e = mb['end']
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


def extract_pattern(tasks, starting_timeline, periods, half_life=30.0, max_sim_time=5000, max_instructions=1000):
    """
    Finds the exact O(1) repeating rules by simulating the timeline until the internal state loops.
    Returns (transient_history, looping_history). 
    If the sequence is aperiodic or too complex, looping_history will be None.
    """
    intervals = [int(x['repeat']) for x in (starting_timeline or []) + (periods or []) if x.get('repeat', 0) > 0]
    period_lcm = intervals[0] if intervals else 1
    for i in intervals[1:]:
        period_lcm = abs(period_lcm * i) // math.gcd(period_lcm, i)
        
    scheduler = SchedulerFunction(tasks, starting_timeline, periods, half_life)
    seen_states = {}
    history = []
    
    for b in scheduler.generator:
        if b['start'] > max_sim_time or len(history) >= max_instructions:
            # Cap reached: Either aperiodic, infinite without repeating, or simply too complex.
            return history, None
            
        # Snapshot state: Rounding to 1 decimal place stabilizes the asymptotic ODE convergence rapidly 
        # so the loop detector can capture exact state alignment. 
        rounded_debts = tuple(round(scheduler.debts[t.name], 1) for t in tasks)
        phase = round(b['start'] % period_lcm, 2) if period_lcm > 1 else 0
        state_key = (rounded_debts, phase)
        
        if state_key in seen_states:
            loop_idx = seen_states[state_key]
            return history[:loop_idx], history[loop_idx:]
            
        seen_states[state_key] = len(history)
        history.append(b)
        
    # Finite schedule reached end
    return history, []


def print_test_instructions(test_name, tasks, starting_timeline, periods):
    """Prints the rule sequence exactly as requested: first 10 and total instructions."""
    transient, loop = extract_pattern(tasks, starting_timeline, periods)
    
    instructions = []
    for b in transient:
        task_name = b['task'] if b['task'] else "Idle"
        instructions.append(f"Place {task_name} for {b['duration']:.1f} mins")
        
    if loop is None:
        instructions.append("... [CAPPED: Sequence is aperiodic, infinite, or exceeds complexity limit]")
        total = len(transient)
    elif len(loop) > 0:
        loop_strs = []
        for b in loop:
            task_name = b['task'] if b['task'] else "Idle"
            loop_strs.append(f"Place {task_name} for {b['duration']:.1f} mins")
        instructions.append(f"REPEAT LOOP: [{', '.join(loop_strs)}]")
        total = len(transient) + len(loop)
    else:
        total = len(transient)
        
    print(f"\n{'-'*50}\n{test_name}")
    print(f"Total instructions (rules): {total}")
    print("Instructions preview (First 10):")
    for idx, inst in enumerate(instructions[:10]):
        print(f"  {idx+1}. {inst}")


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
    display_tasks = tasks[:8]  
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
    
    # Define standard list of tests to run both visualizations AND instruction extractions seamlessly
    test_configs = [
        {
            "name": "Test 1: Original Scenario (No Starting Timeline)",
            "tasks": [Task("Task A", 50, 10), Task("Task B", 30, 15), Task("Task C", 20, 5)],
            "time_limit": 100, "starting_timeline": [], "periods": []
        },
        {
            "name": "Test 2: Massive Task A past debt decays",
            "tasks": [Task("Task A", 50, 10), Task("Task B", 50, 10)],
            "time_limit": 100, "starting_timeline": [{'task': 'Task A', 'start': -1000, 'end': 0}], "periods": []
        },
        {
            "name": "Test 3: Infinite repeating blocks using 'repeat' key",
            "tasks": [Task("Task A", 50, 10), Task("Task B", 50, 10)],
            "time_limit": 400, "starting_timeline": [{'task': 'Task A', 'start': 0, 'end': 60, 'repeat': 180}], "periods": []
        },
        {
            "name": "Test 4: Massive Task A and B before t_now",
            "tasks": [Task("Task A", 45, 45), Task("Task B", 45, 45), Task("Task C", 10, 45)],
            "time_limit": 2000, "starting_timeline": [{'task': 'Task A', 'start': -1000, 'end': 0}, {'task': 'Task B', 'start': -1000, 'end': 0}], "periods": []
        },
        {
            "name": "Test 5: Exclusive Periods",
            "tasks": [Task("Task A", 50, 10), Task("Task B", 50, 10)],
            "time_limit": 100, "starting_timeline": [], "periods": [{'start': 0, 'end': 50, 'accepted_tasks': ['Task A']}, {'start': 50, 'end': 100, 'accepted_tasks': ['Task B']}]
        },
        {
            "name": "Test 6: Overlapping Allow-lists",
            "tasks": [Task("Task A", 40, 10), Task("Task B", 40, 10), Task("Task C", 20, 10)],
            "time_limit": 150, "starting_timeline": [], "periods": [{'start': 0, 'end': 60, 'accepted_tasks': ['Task A', 'Task C']}, {'start': 60, 'end': 120, 'accepted_tasks': ['Task B', 'Task C']}, {'start': 120, 'end': 150, 'accepted_tasks': ['Task A', 'Task B', 'Task C']}]
        },
        {
            "name": "Test 7: Future Debt Growth (Looming Task A)",
            "tasks": [Task("Task A", 50, 10), Task("Task B", 50, 10)],
            "time_limit": 100, "starting_timeline": [{'task': 'Task A', 'start': 70, 'end': 130}], "periods": []
        },
        {
            "name": "Test 8: Exponential Decay - B catches up quickly",
            "tasks": [Task("Task A", 50, 10), Task("Task B", 50, 10)],
            "time_limit": 100, "starting_timeline": [{'task': 'Task A', 'start': -100, 'end': 0}], "periods": []
        },
        {
            "name": "Test 9: Long-term Fluid Decay Priority Matching",
            "tasks": [Task("Task A", 25, 10), Task("Task B", 25, 10), Task("Task C", 50, 10)],
            "time_limit": 2000, "starting_timeline": [{'task': 'Task C', 'start': -1000, 'end': 0}], "periods": []
        },
        {
            "name": "Test 10: Handling large mid-run pre-scheduled blocks smoothly",
            "tasks": [Task("Task A", 80, 10), Task("Task B", 20, 10)],
            "time_limit": 500, "starting_timeline": [{'task': 'Task B', 'start': 200, 'end': 400}], "periods": []
        },
        {
            "name": "Test 11: High Performance Scaling (50 tasks, 100 periods)",
            "tasks": [Task(f"Task {i}", 100.0/50, 5) for i in range(50)],
            "time_limit": 1000, 
            "starting_timeline": [{'task': f"Task {i%50}", 'start': i*20+15, 'end': i*20+20} for i in range(100)],
            "periods": [{'start': i*20, 'end': i*20+15, 'accepted_tasks': [f"Task {j}" for j in range(50)]} for i in range(100)]
        },
        {
            "name": "Test 12: Priority Zero Edge Case",
            "tasks": [Task("Task A", 100, 10), Task("Task B", 0, 10)],
            "time_limit": 100, "starting_timeline": [],
            "periods": [{'start': 0, 'end': 40, 'accepted_tasks': ['Task A', 'Task B']}, {'start': 40, 'end': 60, 'accepted_tasks': ['Task B']}, {'start': 60, 'end': 100, 'accepted_tasks': ['Task A', 'Task B']}]
        },
        {
            "name": "Test 13: Infinite Periodic constraints using 'repeat'",
            "tasks": [Task("Task A", 50, 10), Task("Task B", 50, 10)],
            "time_limit": 300, "starting_timeline": [],
            "periods": [{'start': 0, 'end': 30, 'accepted_tasks': ['Task A'], 'repeat': 100}]
        },
        {
            "name": "Test 14: Aperiodic Infinite Events (Triggers Capping Limit)",
            "tasks": [Task("Task A", 50, 10), Task("Task B", 50, 10)],
            "time_limit": 400, "starting_timeline": [{'task': 'Task A', 'start': (i*10)**1.5, 'end': (i*10)**1.5 + 10} for i in range(1, 150)],
            "periods": []
        }
    ]

    # Setup visualization layout
    SUBPLOT_HEIGHT_IN = 3.5
    fig, axes = plt.subplots(len(test_configs), 1, figsize=(14, SUBPLOT_HEIGHT_IN * len(test_configs)))

    # Process all tests
    print("Extracting O(1) Instructions for all tests...\n")
    for idx, tc in enumerate(test_configs):
        # 1. Print Instructions as per README requirements
        print_test_instructions(tc["name"], tc["tasks"], tc["starting_timeline"], tc["periods"])
        
        # 2. Get standard continuous schedule for visualization
        schedule = get_schedule(tc["tasks"], tc["time_limit"], tc["starting_timeline"], tc["periods"])
        visualize_schedule(schedule, tc["tasks"], tc["time_limit"], axes[idx], tc["name"])

    fig.subplots_adjust(left=0.08, right=0.80, top=0.97, bottom=0.03, hspace=0.85)
    show_scrollable(fig)
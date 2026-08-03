import math

# ---------------------------------------------------------------------------
# One single decay speed for the whole scheduler, written as a half-life in
# minutes. It sets how far the influence of an anomaly (a lockout, a pre-placed
# block) reaches - identically before it and after it.
# ---------------------------------------------------------------------------
DECAY_HALF_LIFE = 50.0
DECAY_RATE = 0.5 ** (1.0 / DECAY_HALF_LIFE)
LAMBDA = math.log(2.0) / DECAY_HALF_LIFE
EPSILON = 1.0


class Task:
    def __init__(self, name, priority, min_time, color):
        self.name = name
        self.priority = priority
        self.min_time = min_time
        self.color = color


def get_clear_timeline_bounds(tasks):
    time_now = 0
    actual_times = {t.name: 0 for t in tasks}
    P = sum(t.priority for t in tasks)
    seen = set()
    max_v = {t.name: -float('inf') for t in tasks}
    min_v = {t.name: float('inf') for t in tasks}
    while True:
        st = tuple(time_now * t.priority - actual_times[t.name] * P for t in tasks)
        if st in seen:
            break
        seen.add(st)
        best, best_v = None, -float('inf')
        for t in tasks:
            v = t.priority * time_now - actual_times[t.name] * P
            max_v[t.name] = max(max_v[t.name], v)
            min_v[t.name] = min(min_v[t.name], v)
            if v > best_v:
                best_v, best = v, t
        if best is None:
            break
        time_now += best.min_time
        actual_times[best.name] += best.min_time
    for t in tasks:
        min_v[t.name] = min(min_v[t.name], t.priority * time_now - actual_times[t.name] * P)
        max_v[t.name] = max(max_v[t.name], t.priority * t.min_time)
        min_v[t.name] = min(min_v[t.name], -(P - t.priority) * t.min_time)
    return max_v, min_v


def merge(iv):
    out = []
    for s, e in sorted(iv):
        if out and s <= out[-1][1]:
            out[-1][1] = max(out[-1][1], e)
        else:
            out.append([s, e])
    return out


def build_anomalies(tasks, pre_placed, periods):
    lock = {t.name: [] for t in tasks}
    forced = {t.name: [] for t in tasks}
    for p in periods:
        for t in tasks:
            if t.name not in p['allowed']:
                lock[t.name].append([p['start'], p['end']])
    for b in pre_placed:
        iv = [b['start'], b['start'] + b['duration']]
        for t in tasks:
            (forced if b['name'] == t.name else lock)[t.name].append(list(iv))
    return {n: merge(v) for n, v in lock.items()}, {n: merge(v) for n, v in forced.items()}


def kernel(intervals, time_now):
    """Integral of decay**|t - t'| over the intervals, split into the part of
    them that lies in the past and the part that lies in the future.
    One minute of anomaly at t' weighs decay**|now - t'|, so its influence is
    the same at a given distance BEFORE it and AFTER it, and a long anomaly
    saturates at 1/LAMBDA instead of counting for ever."""
    past = future = 0.0
    for s, e in intervals:
        if time_now >= e:
            past += (DECAY_RATE ** (time_now - e) - DECAY_RATE ** (time_now - s)) / LAMBDA
        elif time_now <= s:
            future += (DECAY_RATE ** (s - time_now) - DECAY_RATE ** (e - time_now)) / LAMBDA
        else:
            past += (1.0 - DECAY_RATE ** (time_now - s)) / LAMBDA
            future += (1.0 - DECAY_RATE ** (e - time_now)) / LAMBDA
    return past, future


def bounds_now(t, time_now, lock, forced, cmax, cmin, P):
    """The imbalance the task is allowed to carry right now: the clear-timeline
    bound, widened by the anomalies around it, with everything past that ignored
    (exponentially with the distance, epsilon-rounded).

    Each anomaly widens the bound on the side it pushes, and it does so on both
    sides in TIME, at the same distance:
      lockout behind   -> debt tolerated   (catching up after it)
      lockout ahead    -> credit tolerated (paying in advance for it)
      forced run behind-> credit tolerated (resting after it)
      forced run ahead -> debt tolerated   (making room for it)
    """
    l_past, l_future = kernel(lock, time_now)
    f_past, f_future = kernel(forced, time_now)
    over = t.priority * l_past + (P - t.priority) * f_future
    under = t.priority * l_future + (P - t.priority) * f_past
    return (cmax + (over if over >= EPSILON else 0.0),
            cmin - (under if under >= EPSILON else 0.0))


def allowed_at(tasks, periods, time_now):
    if periods:
        for p in periods:
            if p['start'] <= time_now < p['end']:
                return [t for t in tasks if t.name in p['allowed']]
    return list(tasks)


def reverse_tail(tasks, boundary, lock, forced, cmax, cmin, P, periods, max_blocks=200):
    """Schedules the stretch that ends exactly at `boundary`, walking BACKWARDS
    from it.

    Read backwards, an anomaly that starts at `boundary` is an anomaly that just
    ended, so this is literally the forward catch-up code running in reverse
    time: the task that is about to be locked out carries, at tau = 0, the debt
    the lockout will create (capped by the same decay rule), and pays it down
    block by block going back in time. Reversed again at the end, that produces
    the agglomeration immediately BEFORE the boundary, decaying away from it -
    the mirror image of the agglomeration immediately after one.
    """
    allowed = allowed_at(tasks, periods, boundary - 1) if boundary > 0 else []
    if not allowed:
        return []

    lock_len, forced_len = {}, {}
    for t in allowed:
        lock_len[t.name] = next((e - s for s, e in lock[t.name] if s == boundary), 0)
        forced_len[t.name] = next((e - s for s, e in forced[t.name] if s == boundary), 0)
    affected = [t for t in allowed if lock_len[t.name] or forced_len[t.name]]
    if not affected:
        return []

    # The imbalance the anomaly creates is shared between its two sides: half of
    # it is paid in advance here, the other half is left to the forward pass to
    # catch up afterwards. Without the /2 the run-up would absorb the whole
    # thing and there would be nothing left to see on the other side.
    v_off = {t.name: (t.priority * lock_len[t.name]
                      - (P - t.priority) * forced_len[t.name]) / 2.0
             for t in allowed}
    served = {t.name: 0 for t in allowed}
    forgiven = {t.name: 0.0 for t in allowed}
    tau = 0
    steps = []

    def state(t):
        return v_off[t.name] + t.priority * tau - served[t.name] * P - forgiven[t.name]

    while len(steps) < max_blocks:
        # the part of the imbalance that is beyond the (widened) bound is really
        # ignored, i.e. dropped from the state for good, not just hidden
        for t in allowed:
            hi = cmax[t.name] + (t.priority * kernel([[-lock_len[t.name], 0]], tau)[0]
                                 if lock_len[t.name] else 0.0)
            lo = cmin[t.name] - ((P - t.priority) * kernel([[-forced_len[t.name], 0]], tau)[0]
                                 if forced_len[t.name] else 0.0)
            v = state(t)
            if v > hi:
                forgiven[t.name] += v - hi
            elif v < lo:
                forgiven[t.name] += v - lo

        # stop once every task the anomaly touches is back inside its normal band
        if all(cmin[t.name] <= state(t) <= cmax[t.name] for t in affected):
            break

        best, best_key = None, -float('inf')
        for t in allowed:
            v = state(t)
            if v > best_key:
                best_key, best = v, t
        if best is None:
            break
        steps.append({'name': best.name, 'duration': best.min_time, 'color': best.color})
        served[best.name] += best.min_time
        tau += best.min_time

    steps.reverse()
    return steps


def get_schedule_rules(tasks, pre_placed=None, periods=None, max_rules=50):
    pre_placed = pre_placed or []
    periods = periods or []
    P = sum(t.priority for t in tasks)
    cmax, cmin = get_clear_timeline_bounds(tasks)
    lock, forced = build_anomalies(tasks, pre_placed, periods)

    # every point where an anomaly starts gets a reverse-scheduled tail
    starts = sorted({s for d in (lock, forced) for iv in d.values() for s, _ in iv if s > 0})
    tails = {}
    for s in starts:
        tail = reverse_tail(tasks, s, lock, forced, cmax, cmin, P, periods)
        if tail:
            tails[s] = (tail, sum(b['duration'] for b in tail))

    time_now = 0
    actual = {t.name: 0 for t in tasks}
    forgiven = {t.name: 0.0 for t in tasks}
    raw = []
    seen = {}
    pre_sorted = sorted(pre_placed, key=lambda x: x['start'])

    while len(raw) < max_rules:
        active = None
        for p in pre_sorted:
            if p['start'] <= time_now < p['start'] + p['duration']:
                active = p
                break
        if active:
            jump = (active['start'] + active['duration']) - time_now
            raw.append({'name': active['name'], 'duration': jump,
                        'color': active.get('color', '#DDDDDD')})
            if active['name'] in actual:
                actual[active['name']] += jump         # the block IS that task
            else:
                # a foreign block starves every task equally, so it creates no
                # relative imbalance: share it out in proportion to the priorities
                for t in tasks:
                    actual[t.name] += jump * t.priority / P
            time_now += jump
            continue

        # are we inside the reverse-scheduled run-up to the next anomaly?
        nxt = next((s for s in starts if s > time_now and s in tails), None)
        if nxt is not None:
            tail, tail_len = tails[nxt]
            gap = nxt - time_now
            if gap <= tail_len:
                chosen, acc = [], 0
                for b in reversed(tail):
                    if acc + b['duration'] > gap:
                        break
                    chosen.append(b)
                    acc += b['duration']
                if chosen:
                    chosen.reverse()
                    if acc < gap:  # keep the seam exact by stretching the first block
                        chosen[0] = dict(chosen[0], duration=chosen[0]['duration'] + gap - acc)
                    for b in chosen:
                        raw.append(dict(b))
                        if b['name'] in actual:
                            actual[b['name']] += b['duration']
                        time_now += b['duration']
                    continue

        allowed = allowed_at(tasks, periods, time_now)
        if not allowed:
            break

        future_pre = any(p['start'] >= time_now for p in pre_sorted)
        if not future_pre and not periods:
            st = tuple(time_now * t.priority - actual[t.name] * P - forgiven[t.name] for t in tasks)
            if st in seen:
                i = seen[st]
                return compress(raw[:i]), compress(raw[i:])
            seen[st] = len(raw)

        best, best_key = None, -float('inf')
        for t in tasks:
            v = t.priority * time_now - actual[t.name] * P - forgiven[t.name]
            hi, lo = bounds_now(t, time_now, lock[t.name], forced[t.name], cmax[t.name], cmin[t.name], P)
            if v > hi:
                forgiven[t.name] += v - hi
            elif v < lo:
                forgiven[t.name] += v - lo
        for t in allowed:
            v = t.priority * time_now - actual[t.name] * P - forgiven[t.name]
            if v > best_key:
                best_key, best = v, t
        if best is None:
            break
        raw.append({'name': best.name, 'duration': best.min_time, 'color': best.color})
        actual[best.name] += best.min_time
        time_now += best.min_time

    return compress(raw), []


def compress(steps):
    out = []
    for s in steps:
        if out and out[-1]['name'] == s['name']:
            out[-1]['duration'] += s['duration']
        else:
            out.append({'name': s['name'], 'duration': s['duration'], 'color': s['color']})
    return out


def generate_schedule(prefix_blocks, cycle_blocks, total_duration):
    """Generates the timeline up to 'total_duration' by unrolling the finite rules."""
    schedule = []
    time_now = 0

    def append_block(block_template):
        nonlocal time_now
        if schedule and schedule[-1]['name'] == block_template['name']:
            schedule[-1]['duration'] += block_template['duration']
        else:
            schedule.append({
                'name': block_template['name'],
                'start': time_now,
                'duration': block_template['duration'],
                'color': block_template['color']
            })
        time_now += block_template['duration']

    for block in prefix_blocks:
        if time_now >= total_duration:
            break
        append_block(block)

    if not cycle_blocks:
        return schedule

    while time_now < total_duration:
        for block in cycle_blocks:
            if time_now >= total_duration:
                break
            append_block(block)

    return schedule


def debt_curves(tasks, schedule, pre_placed, periods, total_duration, step=1):
    """Replays the drawn timeline minute by minute and returns, per task, the
    part of its imbalance that sits OUTSIDE the clear-timeline bounds, i.e. the
    part that only exists because of an anomaly and that is being ignored
    exponentially. It is positive on the debt side and on the credit side, and
    it is exactly 0 once it has fallen under EPSILON.
    Returns {task_name: [(time, excess), ...]} and the largest excess seen."""
    P = sum(t.priority for t in tasks)
    cmax, cmin = get_clear_timeline_bounds(tasks)
    lock, forced = build_anomalies(tasks, pre_placed, periods)

    # who is being served during each minute of the drawn timeline
    owner = {}
    for b in schedule:
        for t in range(int(b['start']), int(b['start'] + b['duration'])):
            owner[t] = b['name']

    served = {t.name: 0.0 for t in tasks}
    forgiven = {t.name: 0.0 for t in tasks}
    curves = {t.name: [] for t in tasks}
    envelopes = {t.name: [] for t in tasks}
    peak = 0.0

    for now in range(0, int(total_duration) + 1, step):
        for t in tasks:
            v = t.priority * now - served[t.name] * P - forgiven[t.name]
            hi, lo = bounds_now(t, now, lock[t.name], forced[t.name],
                                cmax[t.name], cmin[t.name], P)
            if v > hi:
                forgiven[t.name] += v - hi
                v = hi
            elif v < lo:
                forgiven[t.name] += v - lo
                v = lo
            excess = max(0.0, v - cmax[t.name], cmin[t.name] - v)
            if excess < EPSILON:
                excess = 0.0          # epsilon rounding: nothing left to draw
            curves[t.name].append((now, excess))
            # the envelope: how much out-of-bounds imbalance the decay is still
            # counting at this distance from the anomaly, whether the task has
            # that much or not. This is the pure exponential.
            env = (hi - cmax[t.name]) + (cmin[t.name] - lo)
            if env < EPSILON:
                env = 0.0
            envelopes[t.name].append((now, env))
            peak = max(peak, excess, env)

        for _ in range(step):
            name = owner.get(now)
            if name in served:
                served[name] += 1
            elif name is not None:
                for t in tasks:          # foreign block: shared by everyone
                    served[t.name] += t.priority / P
            now += 1

    return curves, envelopes, peak
# Scheduler score definition

`docs/scheduler_requirements.md` asks for "the best possible score for the two optimization criteria" without
defining the score, and leaves the definition to the implementation. This document is that definition. It adds no
requirement: it only fixes what "best" means. Every § below names a section of `docs/scheduler_requirements.md`.

## Notation

* All times are in minutes. $x$ is the $now line$ and $m$ its mode.
* $R(x)$ is the rule state applied at $now line$ $x$ (§ *Rule state evolution*). **Every quantity below that
  depends on the rule state is read from $R(x)$, over the whole timeline, past included.** That is the reading
  that makes the two-scenario example hold: up to $t_1 + 5min$, both scenarios have the same $R(x)$ at every
  $now line$, so every score they are optimized against is the same.
* For each task $i$: priority $P_i \ge 0$, minimum execution time $M_i$, resilience $r_{i,k}$ to each kind $k$.
  The nominal share is $\pi_i = P_i / \sum_j P_j$ (uniform over all tasks if every priority is 0).
* The environment at instant $t$ is the set of restrictive periods covering it, the pre-placed tasks, and the
  three dynamic restrictive periods as placed for $(x, m)$. The **multiplier** of task $i$ at $t$ is
  $\mu_i(t) = \prod_k r_{i,k}$ over the kinds $k$ covering $t$. Inside a pre-placed task of task $a$,
  $\mu_a(t) = 1$ and $\mu_i(t) = 0$ for $i \ne a$. Inside a pre-placed block owned by no task, $\mu_i(t) = 0$ for
  every $i$.
* $t$ is **schedulable** when $\mu_i(t) > 0$ for at least one task. The **schedulable clock** $u(t)$ is the
  schedulable time elapsed up to $t$. Every duration and distance below is measured on this clock, so a stretch
  where no task may run (a night, a 20s period) is transparent to the score: it neither discounts, nor forgets,
  nor separates.
* $\sigma(t)$ is the task scheduled at $t$ (none where $t$ is not schedulable).
* $\tau_i = \max(M_i, 1) / \pi_i$ is **the smallest window in which task $i$ can get one slot of its minimum
  execution time at its share** (§ *Priority, Granularity and Compensation*: the window must be as small as
  possible while still allowing the minimum execution time). A task with $\pi_i = 0$ takes $\Theta$.
  $\Theta = \max_{\pi_i > 0} \tau_i$ is the window in which every task can be matched.

## The target share (priority percentage, resilience and compensation)

* Local share: $q_i(t) = P_i \mu_i(t) / \sum_j P_j \mu_j(t)$. If that sum is 0 while $t$ is schedulable,
  $q_i(t)$ is uniform over the tasks with $\mu_i(t) > 0$.
* **Compensation.** A task is deprived at $s$ by as much as its multiplier there is below its multiplier now.
  The influence of a deprivation decays exponentially with the schedulable distance from it, on both sides:
  $$c_i(t) = \frac{\pi_i}{\tau_i} \int e^{-|u(t) - u(s)|/\tau_i}\, \big(\mu_i(t) - \mu_i(s)\big)^+ \, du(s)$$
  With this constant, the compensation around a deprivation much shorter than $\tau_i$ is proportional to its
  length (of the size of the time lost for two equal tasks), and the compensation around a deprivation of any
  length is bounded by $2\pi_i\tau_i$: the decay is what prevents a massive overcompensation. A stretch that
  deprives every task equally (a night) creates no relative compensation, because the shares are renormalized
  below. A task that is deprived at $t$ itself (its multiplier now is the lower one) is not compensated at $t$.
* Target share, with $C(t) = \sum_j c_j(t)$:
  $f_i(t) = q_i(t)\,(1 - C(t)) + c_i(t)$ if $C(t) \le 1$, otherwise $f_i(t) = c_i(t) / C(t)$.
  So $f_i \ge 0$, $\sum_i f_i = 1$, and $f_i = 0$ wherever task $i$ is forbidden.

## Criterion 1: priority percentages over the smallest possible window

* The **lag** of task $i$ is its service minus its target, forgetting exponentially over its own window, on the
  schedulable clock:
  $$\frac{dL_i}{du} = \mathbb{1}[\sigma = i] - f_i - \frac{L_i}{\tau_i}$$
  $L_i(x)$ is determined by the frozen past (the past schedule measured against the targets of $R(x)$).
* Criterion 1 is the discounted squared lag of the continuation:
  $$J_1 = \int_{u(x)}^{\infty} e^{-(u - u(x))/\Theta} \sum_i L_i^2 \, du$$
  A block twice as long produces a lag excursion twice as high, so this criterion strictly prefers the smallest
  windows: two 50% tasks alternating every 10min score 4 times better than every 20min.

## Criterion 2: soft minimum execution time

* A **task panel** is a maximal run of task $i$ on the schedulable clock. A stretch where no task may run does
  not interrupt it (it is not schedulable time); the panel is interrupted only when schedulable time is given to
  another task. Its length $\ell$ is measured on the schedulable clock, part before $x$ included.
* Its **shortfall** is $s = (M_i - \ell)^+$. A panel short by $s$ costs what raising a lag of $M_i$ to
  $M_i + s$ costs over the task's own window in criterion 1, charged when the panel ends, for every panel ending
  after $x$ (the panel still open at the end of the continuation is not charged):
  $$J_2 = \sum_{\text{panels ending at } u_e > u(x)} e^{-(u_e - u(x))/\Theta}\; \tau_i\, s\,(2M_i + s)$$
  This common unit is what lets the two criteria form one score, and two properties of it are deliberate:
  * **It is scaled by $\tau_i$, not by $M_i$.** A lag in criterion 1 is forgotten over $\tau_i = M_i/\pi_i$, so it
    costs in proportion to $\tau_i$. Scaled by $M_i$ alone, a shortfall would grow cheaper against the lag as the
    number of tasks grows (by the factor $\pi_i$), and on an account with thirty tasks nearly every panel would
    be trimmed below its minimum.
  * **Its slope at $s = 0$ is $2M_i\tau_i$, not 0.** A squared shortfall makes the first minutes missed free, so
    the best continuation would shave a minute or two off most panels to follow the percentages marginally
    better. With this slope, a panel is cut short only where the lag it repays is larger than its own minimum,
    which is what "the ideal situation is that each task panel spans at least its minimum" asks for.

  It reproduces the example of § *Priority, Granularity and Compensation*: A 30min, B 15min, C 15min at 33% each
  gives A30 B15 C15 B15 C15, because splitting A into two 15min panels would gain less on criterion 1 than its
  shortfall costs on criterion 2.

## The score

* **The score of a continuation is $J = J_1 + J_2$, lower is better.** Hard constraints are not in the score:
  a continuation that violates any requirement of `docs/scheduler_requirements.md` (no idling, resilience 0, pre-placed tasks, dynamic
  restrictive periods, $now line$ modes, frozen past) is not a candidate at all.
* **The best possible score** at $(x, m)$ is the infimum of $J$ over all candidate continuations of the frozen
  past. Because the discount is exponential, the score is time-consistent: the best continuation from a later
  $now line$ is the rest of the best continuation from an earlier one whenever $R$ and the environment did not
  change in between, so the frozen past and the definitive schedule never contradict the best score.
* **The schedule the rules give** at a $now line$ position is the first instant of the best continuation at that
  position. Where the rule state or the environment depends on the $now line$ (a transition between rule states,
  a dragged period, mode 2 and 3 covering the $now line$), the schedule is the path of those first instants as
  the $now line$ moves.
* **The alternative schedule** at $(x, m)$ is the first task of the best continuation among those that do not
  start with $\sigma(x)$.
* **Ties.** Two continuations whose scores differ by less than $10^{-9}$ relative are tied, and the tie is broken
  by the task order: higher priority first, then title.
* **Degradation.** When the best score is not reached within the compute budget, the scheduler returns the best
  continuation it found. The budget is counted in steps, not in wall time, so the same inputs give the same rules
  on every device. The search has three passes over this one score:
  1. a **rollout policy** builds the continuation one decision at a time: every candidate task with every
     candidate length, looked into two runs deep, each trial completed by a simple base policy over a common
     window and closed with a lower bound of every lag;
  2. a **whole-continuation improvement** then shifts boundaries between neighbouring runs, swaps neighbouring
     runs and reassigns runs to other permitted tasks, keeping a change only when it lowers $J$ of the WHOLE
     continuation. It can therefore only move the result closer to the best score, never away from it. (A search
     that judged each decision over its own window was tried and rejected: it made $J$ worse, because what is
     best for a window can cost more over the continuation.)
  3. the **alternative** of every run is the next-best first run of the decision asked where the run starts,
     and where the answer changes inside the run, the instant it changes.

  An exhaustive branch-and-bound over the candidate lengths exists and certifies the best continuation on small
  instances; it is not reachable within the budget on a real account, so it is used to check the passes above,
  not to produce the rules.

## The rules repeat

§ *Core Constraints*: *"The timeline is infinite forward"*. The set of rules is finite because the continuation
repeats.

* **Why it repeats.** Where the environment ahead is **uniform on the schedulable clock** — no pre-placed task, and
  every restrictive period either refuses every task (a night, a break, a wind-down hour nobody is resilient to) or
  is absent — nothing in the score depends on *where* on that clock a decision is taken: the multipliers, the
  targets and both criteria are the same everywhere, and the lags forget. So once the lags have settled, the best
  continuation from one instant is the best continuation from the same state one repetition later. A night does
  not shift the pattern, because it is not on the clock.
* **What the rules return.** When the task runs of the continuation — the task and how long it runs, the
  alternative-schedule splits inside a run left out — are seen **three times in a row** as the same sequence of at
  most 64 task runs, over a stretch uniform to the end of the continuation, the rules are the continuation up to
  the last copy, then **that copy repeated forever** (a *cycle*). The runs the search laid against the horizon are
  replaced by the repetition: they are shaped by the end of the search, not by the score.
* **Where it stops holding.** A cycle belongs to the rule state it was found under and to the uniform environment it
  repeated over. Unrolling it past a pre-placed task, a period that treats the tasks differently, or under another
  rule state is refused, and the search runs again from there.
* **The limit, and its approximation (§ *Degradation*).** The search never plans more than 168 hours past the
  $now line$: the set of rules may not grow heavier than that. A continuation that reaches the limit without an
  exact repetition (tasks whose minimum times share no small common scale can take longer than that to repeat, or
  never do) returns the **closest approximate repetition** instead: among the windows of its last task runs between
  $\Theta$ and $2\Theta$ of schedulable time long, the one where $\sum_i (\text{share}_i - f_i)^2$ is smallest —
  each task's share of the window against its target at the window's start — with a window that would repeat into
  the task it ends with charged one whole unit. Beyond the limit the rules are that window repeated. It is the
  only approximation of the repetition, and it is marked as one (`ScheduleCycle.exact = false`).

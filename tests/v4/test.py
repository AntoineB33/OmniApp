import tkinter as tk

from scheduler_v3 import Task
from visualization import draw_schedules


def build_test_cases():
    return [
        (
            "Test 1: Normal 50/50 Split (10min each)\n-> Pure Periodic Cycle",
            [Task("A", 50, 10, "#FF9999"), Task("B", 50, 10, "#99CCFF")],
            180, [], []
        ),
        (
            "Test 2: Foreign pre-placed event [40,100)\n-> Locks out A and B equally, so no relative imbalance is created.",
            [Task("A", 50, 10, "#FF9999"), Task("B", 50, 10, "#99CCFF")],
            240,
            [{'name': 'MAINTENANCE', 'start': 40, 'duration': 60, 'color': '#CCCCCC'}],
            []
        ),
        (
            "Test 2b: The pre-placed block IS task A [40,100)\n-> B pre-pays right BEFORE it and catches up right AFTER it.",
            [Task("A", 50, 10, "#FF9999"), Task("B", 50, 10, "#99CCFF")],
            240,
            [{'name': 'A', 'start': 40, 'duration': 60, 'color': '#FF9999'}],
            []
        ),
        (
            "Test 3: C is only allowed in [0,100)\n-> C agglomerates against t=100 and stops far from it.",
            [Task("A", 40, 10, "#FF9999"), Task("B", 40, 10, "#99CCFF"), Task("C", 20, 10, "#99FF99")],
            300,
            [],
            [{'start': 0, 'end': 100, 'allowed': ['A', 'B', 'C']},
             {'start': 100, 'end': 9999, 'allowed': ['A', 'B']}]
        ),
        (
            "Test 3b: C is locked out only in [100,200)\n-> Same pile-up on both edges: the decay is symmetric.",
            [Task("A", 40, 10, "#FF9999"), Task("B", 40, 10, "#99CCFF"), Task("C", 20, 10, "#99FF99")],
            400,
            [],
            [{'start': 0, 'end': 100, 'allowed': ['A', 'B', 'C']},
             {'start': 100, 'end': 200, 'allowed': ['A', 'B']},
             {'start': 200, 'end': 9999, 'allowed': ['A', 'B', 'C']}]
        ),
        (
            "Test 4: Three Tasks (A: 50% 20m, B: 30% 10m, C: 20% 15m)",
            [Task("A", 50, 20, "#FF9999"), Task("B", 30, 10, "#99CCFF"), Task("C", 20, 15, "#99FF99")],
            400, [], []
        )
    ]

def main():
    test_cases = build_test_cases()

    root = tk.Tk()
    root.title("Task Scheduler Timeline with Constraints")
    root.geometry("950x700")

    canvas = tk.Canvas(root, bg="white")
    vbar = tk.Scrollbar(root, orient=tk.VERTICAL, command=canvas.yview)
    canvas.configure(yscrollcommand=vbar.set)

    vbar.pack(side=tk.RIGHT, fill=tk.Y)
    canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

    def _on_mousewheel(event):
        if getattr(event, 'num', 0) == 4 or event.delta > 0:
            canvas.yview_scroll(-1, "units")
        elif getattr(event, 'num', 0) == 5 or event.delta < 0:
            canvas.yview_scroll(1, "units")

    canvas.bind_all("<MouseWheel>", _on_mousewheel)
    canvas.bind_all("<Button-4>", _on_mousewheel)
    canvas.bind_all("<Button-5>", _on_mousewheel)

    draw_schedules(root, canvas, test_cases, window_width=900)
    root.mainloop()


if __name__ == "__main__":
    main()
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches

def plot_timeline(schedule, t1, t2):
    """
    Takes the generated schedule and plots a horizontal Gantt-style timeline.
    """
    fig, ax = plt.subplots(figsize=(12, 5))
    
    # 1. Determine Y-axis rows (Extract unique tasks, put IDLE at the bottom)
    tasks = sorted(list(set(p['name'] for p in schedule if p['name'] != '---')))
    tasks.append('---') # IDLE track
    
    # Assign a Y-coordinate for each task row
    y_coords = {task: i * 10 for i, task in enumerate(reversed(tasks))}
    
    # 2. Define colors for our panel types
    colors = {
        'PINNED': '#d62728',  # Red
        'FILLED': '#1f77b4',  # Blue
        'IDLE': '#cccccc'     # Gray
    }
    
    # 3. Draw the task panels
    for panel in schedule:
        y_pos = y_coords[panel['name']]
        start = panel['start']
        duration = panel['end'] - panel['start']
        
        # broken_barh takes a list of (start, width) tuples, and a (y_bottom, y_height) tuple
        ax.broken_barh(xranges=[(start, duration)], 
                       yrange=(y_pos - 4, 8), 
                       facecolors=colors[panel['type']],
                       edgecolor='black', 
                       linewidth=1)
        
        # Add the duration text inside the block if it's wide enough to fit
        if duration >= 10:
            text_color = 'white' if panel['type'] != 'IDLE' else 'black'
            ax.text(start + (duration / 2), y_pos, f"{duration}m", 
                    ha='center', va='center', color=text_color, fontsize=9, fontweight='bold')

    # 4. Format the Axes and Grid
    ax.set_yticks([y_coords[t] for t in tasks])
    ax.set_yticklabels([f"Task {t}" if t != '---' else "IDLE" for t in tasks])
    ax.set_xlabel("Time (minutes)", fontsize=11)
    ax.set_xlim(t1, t2)
    
    # Add vertical grid lines for readability
    ax.set_xticks(range(t1, t2 + 1, 30)) # Ticks every 30 mins
    ax.grid(True, axis='x', linestyle='--', alpha=0.6)
    
    # 5. Build the Legend
    legend_patches = [mpatches.Patch(color=color, label=label) for label, color in colors.items()]
    ax.legend(handles=legend_patches, loc='upper right', bbox_to_anchor=(1, 1.15), ncol=3)
    
    ax.set_title("Dynamic Task Schedule Timeline", fontsize=14, pad=20)
    plt.tight_layout()
    plt.show()
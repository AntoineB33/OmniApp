
Let's call 
  - after a ≥20-second pause, the next look-away is **20 minutes** later;
  - after a ≥5-minute pause, the next 5-minute pose is **1 hour** later;
  - after a ≥15-minute pause, the next 15-minute pose is **2 hours** later.



  

New requirement: The definitions of periods forming the starting timeline can be relative to real time.
Example 1:
The end edge of a period can go to the right at 1min per real minute and the starting edge of the period right after moves to the right at 1min per real minute.
Example 2:
30 seconds after the run, The whole period set is completely changed.

The result of the scheduler is a O(1) function that takes timeline time and real time as parameters and returns a task or null.
# optimization goals

- Minimize "inactivity" as much as possible. This optimization goal has absolute priority over the next ones.
- Reach the presence ratio targets as much as possible.
- Reach the minimum time targets as much as possible.

constraints:
- a "20s break" lasts 20 seconds
- a "5min break" lasts 5 minutes
- a "15min break" lasts 15 minutes
- a "20s break" appears where there is not ("all computer shut" and "all phone shut") as close as possible to a "20s break" at least 20 minutes after it.
- a "5min break" appears where there is not ("all computer shut" and "all phone shut") as close as possible to a "5min break" or a 15 minute "no screen" at least 1 hour after it.
- a "15min break" appears where there is not ("all computer shut" and "all phone shut") as close as possible to a 15 minute "no screen" at least 2 hour after it.
- "sleep" -> "1h before bed" -1h at l
- constraints of a task tree, for example:
    - task A 100% -> no task B
    - task A 80% -> no task B or task B 20%

All those optimization goals and constraints can be modified easily by the user.
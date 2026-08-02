import unittest

from scheduler import Task, generate_schedule


class TestScheduler(unittest.TestCase):

    def test_example_scenario(self):
        """Test the exact scenario provided in the prompt."""
        tasks = [
            Task("A", priority=50, min_time=10),
            Task("B", priority=50, min_time=10)
        ]
        
        expected = [
            "task A 10min",
            "task B 10min",
            "repeat"
        ]
        
        self.assertEqual(generate_schedule(tasks), expected)

    def test_uneven_priorities(self):
        """Test tasks with the same min_time but different priorities."""
        # A should run 4 times for every 1 time B runs (80/20)
        tasks = [
            Task("A", priority=80, min_time=10),
            Task("B", priority=20, min_time=10)
        ]
        
        # Because A has a smaller stride, it will be prioritized and nicely interleaved
        expected = [
            "task A 10min",
            "task B 10min",
            "task A 10min",
            "task A 10min",
            "task A 10min",
            "repeat"
        ]
        
        self.assertEqual(generate_schedule(tasks), expected)

    def test_different_times(self):
        """Test tasks with same priorities but different minimum times."""
        # A takes 20m, B takes 10m. To maintain 50/50, B must run twice as often.
        tasks = [
            Task("A", priority=50, min_time=20),
            Task("B", priority=50, min_time=10)
        ]
        
        expected = [
            "task B 10min",
            "task A 20min",
            "task B 10min",
            "repeat"
        ]
        
        self.assertEqual(generate_schedule(tasks), expected)

    def test_three_tasks(self):
        """Test scaling up to 3 different tasks."""
        tasks = [
            Task("A", priority=50, min_time=10), # Needs 50%
            Task("B", priority=25, min_time=10), # Needs 25%
            Task("C", priority=25, min_time=10)  # Needs 25%
        ]
        
        # Total cycle is 40 mins. A runs 2x (20m), B runs 1x (10m), C runs 1x (10m)
        expected = [
            "task A 10min",
            "task B 10min",
            "task C 10min",
            "task A 10min",
            "repeat"
        ]
        
        self.assertEqual(generate_schedule(tasks), expected)
        
    def test_empty_tasks(self):
        """Test safety with empty lists."""
        self.assertEqual(generate_schedule([]), [])

if __name__ == '__main__':
    unittest.main()
#!/usr/bin/env python3
"""Offline boundary tests for the guest's natural calendar wait; no Docker."""
import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    'operational_catchup_qa', Path(__file__).with_name('operational-catchup-qa.py'))
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)


class Clock:
    def __init__(self, wall, max_advance=30.0):
        self.wall, self.elapsed, self.sleeps = wall, 0.0, []
        self.max_advance = max_advance

    def time(self):
        return self.wall

    def monotonic(self):
        return self.elapsed

    def sleep(self, requested):
        self.sleeps.append(requested)
        advance = min(requested, self.max_advance)
        self.wall += advance
        self.elapsed += advance


class CalendarWindowTests(unittest.TestCase):
    def wait(self, clock):
        return qa.wait_calendar_window(clock=clock.time, monotonic=clock.monotonic,
                                       sleep=clock.sleep)

    def test_safe_window_and_exact_hour_do_not_wait(self):
        for wall in (0.0, 3600.0, 3600.0 - 240.001):
            clock = Clock(wall)
            with self.subTest(wall=wall):
                self.assertEqual(self.wait(clock), 0.0)
                self.assertEqual(clock.sleeps, [])

    def test_four_minute_boundary_requests_at_most_241_seconds(self):
        clock = Clock(3600.0 - 240.0)
        self.assertEqual(self.wait(clock), 241.0)
        self.assertEqual(sum(clock.sleeps), 241.0)
        self.assertTrue(all(0 < delay <= 30 for delay in clock.sleeps))
        self.assertEqual(clock.wall, 3601.0)

    def test_fractional_boundary_waits_until_one_second_after_hour(self):
        clock = Clock(3600.0 - 239.5)
        self.assertEqual(self.wait(clock), 240.5)
        self.assertEqual(clock.wall, 3601.0)

    def test_last_fractional_second_does_not_fail_the_scenario(self):
        clock = Clock(3599.75)
        self.assertEqual(self.wait(clock), 1.25)
        self.assertEqual(clock.wall, 3601.0)

    def test_interrupted_sleep_reuses_one_deadline(self):
        clock = Clock(3590.0, max_advance=2.0)
        self.assertEqual(self.wait(clock), 11.0)
        self.assertEqual(clock.elapsed, 11.0)
        self.assertEqual(clock.wall, 3601.0)

    def test_nonfinite_wall_clock_fails_before_sleep(self):
        for wall in (float('nan'), float('inf')):
            clock = Clock(wall)
            with self.subTest(wall=wall), self.assertRaisesRegex(RuntimeError, 'CALENDAR_CLOCK_REJECTED'):
                self.wait(clock)
            self.assertEqual(clock.sleeps, [])

    def test_clock_regression_at_boundary_fails_without_unbounded_retry(self):
        clock = Clock(3599.0)
        def backwards_sleep(requested):
            clock.sleep(requested)
            clock.wall -= 2
        with self.assertRaisesRegex(RuntimeError, 'CALENDAR_WINDOW_NOT_READY'):
            qa.wait_calendar_window(clock=clock.time, monotonic=clock.monotonic,
                                    sleep=backwards_sleep)
        self.assertEqual(clock.elapsed, 2.0)
        self.assertEqual(clock.sleeps, [2.0])


if __name__ == '__main__':
    unittest.main()

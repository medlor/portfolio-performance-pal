"""Bounded scheduling shared by qualification modules and independent test cases."""
import concurrent.futures
import os


def worker_count():
    # A runner publishes its resource-clamped limit. Qualification outside a test
    # runner retains the existing conservative two-module maximum.
    return int(os.environ.get('PP_PAL_TEST_WORKERS', '2'))


def parallel_cases(function, cases):
    """Run a bounded rolling queue; drain active work and stop admission on failure.

    Callers own isolation and dependencies. Workers never acquire resource slots:
    the test Docker boundary accounts for expensive child work separately.
    """
    cases = iter(cases)
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=worker_count()) as pool:
        pending = set()
        for _ in range(worker_count()):
            try:
                pending.add(pool.submit(function, next(cases)))
            except StopIteration:
                break
        while pending:
            done, pending = concurrent.futures.wait(
                pending, return_when=concurrent.futures.FIRST_COMPLETED)
            # Resolve the whole completed set before admitting replacements. If
            # any failed, the context manager drains the remaining active work.
            results.extend(future.result() for future in done)
            for _ in done:
                try:
                    pending.add(pool.submit(function, next(cases)))
                except StopIteration:
                    break
    return results

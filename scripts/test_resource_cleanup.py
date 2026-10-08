"""Exercise cleanup of an owned process tree without affecting an unrelated helper."""
import ctypes
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from resource_soak import stop_owned_processes


def is_running(pid):
    if os.name == "nt":
        from ctypes import wintypes
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            return False
        try:
            code = wintypes.DWORD()
            return bool(kernel.GetExitCodeProcess(handle, ctypes.byref(code))) and code.value == 259
        finally:
            kernel.CloseHandle(handle)
    status = Path(f"/proc/{pid}/stat")
    try:
        return status.read_text().split(")", 1)[1].split()[0] != "Z"
    except FileNotFoundError:
        return False


class ResourceCleanupTest(unittest.TestCase):
    def test_failure_before_marker_stops_owned_descendants_and_preserves_unrelated_process(self):
        with tempfile.TemporaryDirectory(prefix="resource cleanup ") as directory:
            root = Path(directory)
            receipt = root / "helper-pids.json"
            helper = root / "owned-helper.py"
            helper.write_text("import json, os, subprocess, sys, time\n"
                "leaf = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'])\n"
                "with open(sys.argv[1] + '.tmp', 'w') as output: output.write(json.dumps([os.getpid(), leaf.pid]))\n"
                "os.replace(sys.argv[1] + '.tmp', sys.argv[1])\n"
                "time.sleep(120)\n", encoding="utf-8")
            if os.name == "nt":
                wrapper = root / "owned-wrapper.cmd"
                wrapper.write_text('@"' + sys.executable + '" "' + str(helper) + '" "' + str(receipt) + '"\r\n')
                command = [str(wrapper)]
            else:
                command = [sys.executable, str(helper), str(receipt)]
            child = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                start_new_session=os.name != "nt", creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(120)"],
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            owned_pids = []
            try:
                deadline = time.monotonic() + 15
                while not receipt.exists() and time.monotonic() < deadline:
                    time.sleep(.02)
                self.assertTrue(receipt.exists(), "Owned helper did not start")
                owned_pids = json.loads(receipt.read_text())
                self.assertTrue(all(is_running(pid) for pid in owned_pids))
                try:
                    stop_owned_processes(child)  # Failure before a Maven test PID was available.
                except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                    # taskkill may report that the wrapper vanished after killing its children.
                    # The owned process checks below, including the wrapper, decide cleanup.
                    if os.name != "nt":
                        raise
                deadline = time.monotonic() + 3
                while any(is_running(pid) for pid in (child.pid, *owned_pids)) and time.monotonic() < deadline:
                    time.sleep(.02)
                self.assertFalse(any(is_running(pid) for pid in (child.pid, *owned_pids)), "Owned process outlived cleanup")
                self.assertIsNone(unrelated.poll(), "Cleanup touched an unrelated process")
            finally:
                for pid in owned_pids:
                    if is_running(pid):
                        try: os.kill(pid, signal.SIGTERM)
                        except OSError: pass
                stop_owned_processes(child)
                child.wait(timeout=5)
                unrelated.terminate(); unrelated.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()

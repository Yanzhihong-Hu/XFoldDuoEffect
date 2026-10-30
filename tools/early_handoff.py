#!/usr/bin/env python3
"""Early cover-screen handoff experiment, driven from a computer over adb.

OriginOS has no device state with both panels on, so the closest iPhone Duo behaviour is to hand
over to the cover screen at about 90 degrees instead of near closed. adb's shell identity is the
same one Shizuku would give the app, so this script tests the idea before any Shizuku work:

  closing: once a fold effect has started and the hinge reaches SWITCH_DEG, force the vivo
           STAY_REAR_SELFIE state (cover on, inner off); return control to the system when the
           phone is closed or opened back up.
  opening: from closed, keep the cover on until SWITCH_DEG, then return control so the inner
           screen takes over.

Usage:  python3 tools/early_handoff.py [--switch 90] [--adb PATH]
Stop with Ctrl-C; the device state is always reset on exit.
"""
import argparse
import os
import re
import subprocess
import sys
import time

COVER_STATE = 102          # STAY_REAR_SELFIE on vivo X Fold3 Pro / OriginOS 6
CLOSED_DEG = 5.0
OPEN_START_DEG = 10.0
REOPENED_DEG = 130.0

HINGE = re.compile(r"XFoldDuoHinge.*deg=([0-9.]+)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--switch", type=float, default=90.0, help="handoff angle in degrees")
    ap.add_argument("--adb", default=os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
    args = ap.parse_args()

    shell = subprocess.Popen([args.adb, "shell"], stdin=subprocess.PIPE, text=True)

    def device_state(cmd):
        shell.stdin.write(f"cmd device_state state {cmd}\n")
        shell.stdin.flush()

    subprocess.run([args.adb, "logcat", "-c"], check=False)
    log = subprocess.Popen([args.adb, "logcat", "-v", "brief", "-s", "XFoldDuoHinge:I", "XFoldDuo:I"],
                           stdout=subprocess.PIPE, text=True, bufsize=1)
    forced = None            # None | "close" | "open"
    folding = False
    closed = False
    t0 = time.monotonic()

    def say(msg):
        print(f"{time.monotonic() - t0:7.2f}s  {msg}", flush=True)

    say(f"watching hinge; handoff at {args.switch:.0f}°  (Ctrl-C to stop)")
    try:
        for line in log.stdout:
            if "fold triggered" in line or "opening reversed" in line:
                folding = True
            m = HINGE.search(line)
            if not m:
                continue
            deg = float(m.group(1))
            if forced is None:
                if folding and deg <= args.switch:
                    device_state(COVER_STATE); forced = "close"
                    say(f"{deg:5.1f}°  closing → cover on early")
                elif closed and OPEN_START_DEG <= deg < args.switch:
                    device_state(COVER_STATE); forced = "open"
                    say(f"{deg:5.1f}°  opening → holding cover until {args.switch:.0f}°")
            elif forced == "close":
                if deg <= CLOSED_DEG or deg >= REOPENED_DEG:
                    device_state("reset"); forced = None; folding = False
                    say(f"{deg:5.1f}°  {'closed' if deg <= CLOSED_DEG else 'reopened'} → system control")
            elif forced == "open":
                if deg >= args.switch or deg <= CLOSED_DEG:
                    device_state("reset"); forced = None
                    say(f"{deg:5.1f}°  → system control (inner takes over)")
            closed = deg <= CLOSED_DEG
            if forced is None and deg >= REOPENED_DEG:
                folding = False
    except KeyboardInterrupt:
        pass
    finally:
        device_state("reset")
        time.sleep(0.3)
        shell.terminate()
        log.terminate()
        say("device state reset; bye")


if __name__ == "__main__":
    sys.exit(main())

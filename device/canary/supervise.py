"""Runs the daemons of one decoy container, and stops them all when one exits.

Usage: supervise.py canary | announce

Each decoy container runs more than one daemon (ADR 0018). When any of them
exits, the others are stopped and this process exits with an error, so systemd
restarts the whole container instead of leaving a decoy half running. A
SIGTERM from Podman stops all of them and ends cleanly. Standard library only.
"""

import os
import shutil
import signal
import sys
from pathlib import Path

# The release and build time of this image (ADR 0013). The canary copies it
# into its log volume, the only part of the container the agent can read.
IMAGE_FILE = Path("/usr/share/dekoi/image")
IMAGE_COPY = Path("/var/log/opencanary/image")

DAEMONS = {
    # OpenCanary started directly: its wrapper needs sudo and a writable /var/run.
    "canary": [
        ["twistd", "--nodaemon", "--pidfile=", "--python=/opt/venv/bin/opencanary.tac"],
        ["smbd", "--foreground", "--no-process-group"],
    ],
    # Without privileges avahi-daemon cannot drop root, chroot or set limits.
    # nmbd takes its log level from the command line, so it leaves the audit
    # log that only smbd writes alone; its own messages go to the journal.
    "announce": [
        ["nmbd", "--foreground", "--no-process-group", "--debug-stdout", "--debuglevel=0"],
        ["avahi-daemon", "--no-drop-root", "--no-chroot", "--no-rlimits"],
        ["wsdd2", "-w"],
    ],
}

# Handled one at a time with sigwait, so a stop request and a daemon exit
# never race each other.
SIGNALS = {signal.SIGCHLD, signal.SIGTERM, signal.SIGINT}


def run(commands: list[list[str]]) -> int:
    """Returns 0 after a requested stop, 1 after a daemon exited by itself."""
    signal.pthread_sigmask(signal.SIG_BLOCK, SIGNALS)
    running = {
        os.posix_spawnp(command[0], command, os.environ, setsigmask=()): command[0]
        for command in commands
    }
    requested = False
    stopping = False
    while running:
        if signal.sigwait(SIGNALS) != signal.SIGCHLD:
            requested = True
        exited = _reap(running)
        if exited and not requested and not stopping:
            print(f"{exited[0]} exited, stopping the other daemons", file=sys.stderr)
        if (requested or exited) and not stopping:
            stopping = True
            for pid in running:
                os.kill(pid, signal.SIGTERM)
    return 0 if requested else 1


def _reap(running: dict[int, str]) -> list[str]:
    """Reaps every exited child and returns the daemons among them.

    Orphans of a daemon are reparented to this process; they are reaped too.
    """
    exited = []
    while True:
        try:
            pid, _ = os.waitpid(-1, os.WNOHANG)
        except ChildProcessError:
            return exited
        if pid == 0:
            return exited
        if pid in running:
            exited.append(running.pop(pid))


def copy_image_file(source: Path = IMAGE_FILE, target: Path = IMAGE_COPY) -> None:
    """Copies the image file for the agent; a failure never keeps the decoys from starting.

    A link left in the target's place is removed, not followed.
    """
    try:
        target.unlink(missing_ok=True)
        shutil.copyfile(source, target)
    except OSError as error:
        print(f"image file not copied: {error}", file=sys.stderr)


if __name__ == "__main__":
    if sys.argv[1] == "canary":
        copy_image_file()
    sys.exit(run(DAEMONS[sys.argv[1]]))

import os
import socket


def notify(message: str) -> None:
    """Send an sd_notify message, such as READY=1, when run under systemd.

    Readiness lets `podman auto-update` detect a failed update and roll back.
    """
    path = os.environ.get("NOTIFY_SOCKET")
    if not path:
        return
    if path.startswith("@"):
        path = "\0" + path[1:]
    with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as sock:
        sock.connect(path)
        sock.sendall(message.encode())

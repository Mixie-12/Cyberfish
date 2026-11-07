"""Client for the device API (docs/architecture.md, "Device API")."""

import json
import urllib.error
import urllib.request
from urllib.parse import urlsplit

from .config import Config

TIMEOUT_SECONDS = 20


class ApiError(Exception):
    """A failed call. status is the HTTP status, or 0 when nothing came back."""

    def __init__(self, status: int, detail: str) -> None:
        super().__init__(f"HTTP {status}: {detail}")
        self.status = status

    @property
    def malformed(self) -> bool:
        """The backend rejected the input; resending it can never succeed."""
        return self.status == 400

    @property
    def refused(self) -> bool:
        return self.status in (401, 403)

    @property
    def missing(self) -> bool:
        """The backend has no such function: it is older than this agent."""
        return self.status == 404


def call(config: Config, function: str, arguments: dict) -> object:
    request = urllib.request.Request(  # noqa: S310 - URL scheme is checked in Config
        f"{config.api_url}/rest/v1/rpc/{function}",
        data=json.dumps(arguments).encode(),
        method="POST",
        headers={"apikey": config.api_key, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:  # noqa: S310 - URL scheme is checked in Config
            body = response.read()
    except urllib.error.HTTPError as error:
        raise ApiError(error.code, error.read(500).decode(errors="replace")) from None
    except OSError as error:
        raise ApiError(0, str(error)) from None
    return json.loads(body) if body else None


def backend_address(config: Config) -> tuple[str, int]:
    """Host and port of the backend, which the internet health probe connects to."""
    parts = urlsplit(config.api_url)
    if not parts.hostname:
        raise ValueError("DEKOI_API_URL has no host")
    return parts.hostname, parts.port or (443 if parts.scheme == "https" else 80)

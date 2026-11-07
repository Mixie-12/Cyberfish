import os
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit


@dataclass(frozen=True)
class Config:
    api_url: str
    api_key: str
    agent_version: str
    state_path: Path
    claim_code_path: Path
    canary_log: Path
    scan_log: Path
    ids_socket: Path
    decoy_config: Path

    @classmethod
    def from_env(cls, env: dict[str, str] = os.environ) -> "Config":
        """Read configuration from the environment.

        The backend URL and publishable key are built into the image, so they
        can be rotated for the whole fleet by an image update. Plain HTTP is
        accepted only when DEKOI_ALLOW_HTTP=1, which only the end-to-end test
        harness sets.
        """
        api_url = env["DEKOI_API_URL"].rstrip("/")
        scheme = urlsplit(api_url).scheme
        if scheme != "https" and not (scheme == "http" and env.get("DEKOI_ALLOW_HTTP") == "1"):
            raise ValueError("DEKOI_API_URL must use https")
        if not env["DEKOI_API_KEY"]:
            raise ValueError("DEKOI_API_KEY is empty")
        return cls(
            api_url=api_url,
            api_key=env["DEKOI_API_KEY"],
            agent_version=env.get("DEKOI_AGENT_VERSION", "dev"),
            state_path=Path(env.get("DEKOI_STATE", "/var/lib/dekoi-agent/state.json")),
            claim_code_path=Path(env.get("DEKOI_CLAIM_CODE", "/etc/dekoi/claim-code")),
            canary_log=Path(env.get("DEKOI_CANARY_LOG", "/var/log/opencanary/opencanary.log")),
            scan_log=Path(env.get("DEKOI_SCAN_LOG", "/var/log/ulog/dekoi-scan.json")),
            ids_socket=Path(env.get("DEKOI_IDS_SOCKET", "/run/dekoi-ids/eve.sock")),
            decoy_config=Path(env.get("DEKOI_DECOY_CONFIG", "/usr/share/dekoi/opencanary.conf")),
        )

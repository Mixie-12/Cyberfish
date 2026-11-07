import json
from pathlib import Path

import pytest

from dekoi_agent import agent as agent_module
from dekoi_agent import api
from dekoi_agent.agent import Agent
from dekoi_agent.config import Config
from dekoi_agent.state import State

CANARY_CONFIG = Path(__file__).parents[2] / "canary" / "opencanary.conf"
CLAIM_CODE = "C" + "0" * 24 + "1"


class FakeBackend:
    """Records calls and answers them from a per-function list of outcomes."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []
        self.outcomes: dict[str, list[object]] = {}

    def __call__(self, config: Config, function: str, arguments: dict) -> object:
        self.calls.append((function, arguments))
        outcomes = self.outcomes.get(function, [])
        outcome = outcomes.pop(0) if outcomes else None
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    def called(self, function: str) -> list[dict]:
        return [arguments for name, arguments in self.calls if name == function]


class Clock:
    def __init__(self) -> None:
        self.now = 1_800_000_000.0

    def __call__(self) -> float:
        return self.now


@pytest.fixture
def config(tmp_path: Path) -> Config:
    (tmp_path / "claim-code").write_text(CLAIM_CODE + "\n")
    (tmp_path / "canary.log").write_text("")
    return Config(
        api_url="https://example.invalid",
        api_key="sb_publishable_test",
        agent_version="0.1.0",
        state_path=tmp_path / "state.json",
        claim_code_path=tmp_path / "claim-code",
        canary_log=tmp_path / "canary.log",
        scan_log=tmp_path / "scan.json",
        decoy_config=CANARY_CONFIG,
    )


@pytest.fixture
def backend(monkeypatch: pytest.MonkeyPatch) -> FakeBackend:
    fake = FakeBackend()
    monkeypatch.setattr(api, "call", fake)
    monkeypatch.setattr(agent_module, "listening_ports", lambda: {22, 80})
    monkeypatch.setattr(agent_module.inventory, "local_ip", lambda: "192.168.1.20")
    monkeypatch.setattr(agent_module.inventory, "mac_for", lambda ip: None)
    monkeypatch.setattr(agent_module.inventory, "scan_hosts", lambda: [])
    return fake


def ssh_attempt() -> str:
    return json.dumps(
        {
            "logtype": 4002,
            "src_host": "192.168.1.50",
            "dst_port": 22,
            "utc_time": "2026-11-07 10:00:00.000000",
            "logdata": {"USERNAME": "root", "PASSWORD": "toor"},
        }
    )


def test_registration_waits_for_the_claim_and_keeps_its_secret(
    config: Config, backend: FakeBackend
) -> None:
    clock = Clock()
    backend.outcomes["device_register_v1"] = [api.ApiError(403, "refused"), "device-1"]
    agent = Agent(config, State(), clock)

    agent.tick()
    assert agent.state.device_id is None
    agent.tick()
    assert len(backend.called("device_register_v1")) == 1, "backs off after a refusal"

    clock.now += 60
    agent.tick()
    first, second = backend.called("device_register_v1")
    assert first == second
    assert first["claim_code"] == CLAIM_CODE
    assert len(first["secret"]) == 64
    saved = State.load(config.state_path)
    assert saved.device_id == "device-1"
    assert saved.secret == first["secret"]


def test_events_are_delivered_without_passwords(config: Config, backend: FakeBackend) -> None:
    agent = Agent(config, State(device_id="device-1", secret="a" * 64), Clock())
    agent.tick()
    config.canary_log.write_text(ssh_attempt() + "\n")
    agent.tick()

    (call,) = backend.called("device_events_v1")
    (event,) = call["events"]
    assert event["kind"] == "login_attempt"
    assert event["service"] == "ssh"
    assert event["agent_event_id"] == 1
    assert "toor" not in json.dumps(call)
    assert agent.state.queue == []


def test_backend_outage_keeps_events_and_malformed_batches_are_dropped(
    config: Config, backend: FakeBackend
) -> None:
    clock = Clock()
    backend.outcomes["device_events_v1"] = [api.ApiError(503, "down"), api.ApiError(400, "bad")]
    agent = Agent(config, State(device_id="device-1", secret="a" * 64), clock)
    agent.tick()
    config.canary_log.write_text(ssh_attempt() + "\n")

    agent.tick()
    assert len(agent.state.queue) == 1
    assert State.load(config.state_path).queue == agent.state.queue

    clock.now += 2
    agent.tick()
    assert agent.state.queue == []
    assert len(backend.called("device_events_v1")) == 2


def test_heartbeat_reports_running_decoys(config: Config, backend: FakeBackend) -> None:
    agent = Agent(config, State(device_id="device-1", secret="a" * 64), Clock())
    agent.tick()
    (heartbeat,) = backend.called("device_heartbeat_v1")
    assert heartbeat == {
        "device_id": "device-1",
        "secret": "a" * 64,
        "agent_version": "0.1.0",
        "services": ["http", "ssh"],
        "local_ip": "192.168.1.20",
    }

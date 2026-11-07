"""Consistency between the images and what the agent reads from them for its
monitoring report (ADR 0013)."""

import re
from pathlib import Path

from dekoi_agent import monitors
from dekoi_agent.config import Config

DEVICE = Path(__file__).parents[1]


def test_every_image_carries_its_version_file() -> None:
    for image in ("agent", "canary", "ids"):
        containerfile = (DEVICE / image / "Containerfile").read_text()
        assert f"> {monitors.IMAGE_FILE}\n" in containerfile, image


def test_the_canary_copies_its_version_file_next_to_the_log_the_agent_reads() -> None:
    config = Config.from_env({"DEKOI_API_URL": "https://x.invalid", "DEKOI_API_KEY": "k"})
    supervise = (DEVICE / "canary" / "supervise.py").read_text()
    assert f'IMAGE_FILE = Path("{monitors.IMAGE_FILE}")' in supervise
    assert f'IMAGE_COPY = Path("{config.canary_log.parent / "image"}")' in supervise


def test_the_ids_sends_statistics_often_enough() -> None:
    suricata = (DEVICE / "ids" / "suricata.yaml").read_text()
    stats = re.search(r"^stats:\n  enabled: yes\n  interval: (\d+)$", suricata, re.MULTILINE)
    assert stats is not None
    assert int(stats.group(1)) * 3 <= monitors.IDS_SECONDS
    assert "        - stats:" in suricata


def test_the_ids_names_itself_as_the_agent_expects() -> None:
    start = (DEVICE / "ids" / "start.sh").read_text()
    assert f'"sensor-name={monitors.IDS_SENSOR} $version $updated_at $ruleset_date"' in start

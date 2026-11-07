import logging
import sys
import time

from .agent import Agent
from .config import Config
from .state import State
from .systemd import notify

TICK_SECONDS = 1


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s", stream=sys.stderr)
    config = Config.from_env()
    agent = Agent(config, State.load(config.state_path))
    notify("READY=1")
    while True:
        agent.tick()
        notify("WATCHDOG=1")
        time.sleep(TICK_SECONDS)


if __name__ == "__main__":
    main()

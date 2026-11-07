"""Fills the persona into the decoy's configuration files at image build time.

Usage: render.py PERSONA_JSON OUTPUT_DIR TEMPLATE...

Each template (a path relative to the working directory) is written to the
same relative path under OUTPUT_DIR, with $name, $model and $firmware
replaced. An unknown or misspelt placeholder fails the build.
"""

import json
import string
import sys
from pathlib import Path


def render(persona: dict[str, str], output: Path, templates: list[str]) -> None:
    for template in templates:
        text = string.Template(Path(template).read_text()).substitute(persona)
        target = output / template
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)


if __name__ == "__main__":
    render(json.loads(Path(sys.argv[1]).read_text()), Path(sys.argv[2]), sys.argv[3:])

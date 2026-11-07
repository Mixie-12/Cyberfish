"""Fills the persona into the decoy's configuration and bait files at image build time.

Usage: render.py PERSONA_JSON OUTPUT_DIR TEMPLATE...

Each template, a file or a directory of them relative to the working
directory, is written to the same relative path under OUTPUT_DIR, with the
persona's $name, $model, $firmware and bait user names ($admin_user and so
on) replaced. An unknown or misspelt placeholder fails the build.

A template named like "Skattemelding 2025.pdf.txt" is written without its
.txt, in the format the name says: a one-page PDF or a document with its
lines, a spreadsheet with a row per line and a cell per tab, or a picture
fading through the colours on its lines. So the bait stays small text in
git, and the share serves files that open. Standard library only.
"""

import io
import json
import os
import string
import struct
import sys
import zipfile
import zlib
from datetime import UTC, datetime
from pathlib import Path
from xml.sax.saxutils import escape

# Every build writes the same bytes. The files carry this time, after the
# newest thing they mention, instead of the build's.
MODIFIED = (2026, 5, 2, 20, 41, 0)
PICTURE_SIZE = (160, 120)
XML = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
OPENXML = "http://schemas.openxmlformats.org"
RELATIONSHIPS = f"{OPENXML}/officeDocument/2006/relationships"
OFFICE = "application/vnd.openxmlformats-officedocument"
PACKAGE = "application/vnd.openxmlformats-package"


def render(persona: dict, output: Path, templates: list[str]) -> None:
    values = persona | persona["bait_users"]
    for path in (p for template in templates for p in _files(Path(template))):
        text = string.Template(path.read_text(encoding="utf-8")).substitute(values)
        _write(output / path, text)
    stamp = datetime(*MODIFIED, tzinfo=UTC).timestamp()
    for path in output.rglob("*"):
        os.utime(path, (stamp, stamp))


def _files(path: Path) -> list[Path]:
    return sorted(p for p in path.rglob("*") if p.is_file()) if path.is_dir() else [path]


def _write(target: Path, text: str) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    made = target.with_suffix("")
    convert = FORMATS.get(made.suffix) if target.suffix == ".txt" else None
    if convert is None:
        target.write_text(text, encoding="utf-8")
    else:
        made.write_bytes(convert(text.splitlines()))


def pdf(lines: list[str]) -> bytes:
    """One A4 page in Helvetica, the first line as its heading."""
    heading, *body = [_pdf_string(line) for line in lines]
    text = b"BT /F2 16 Tf 72 770 Td " + heading + b" Tj /F1 11 Tf 16 TL 0 -8 Td\n"
    text += b"".join(line + b" '\n" for line in body) + b"ET"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842]"
        b" /Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>",
        b"<< /Length %d >>\nstream\n%s\nendstream" % (len(text), text),
    ]
    document = bytearray(b"%PDF-1.4\n")
    offsets = []
    for number, body_object in enumerate(objects, 1):
        offsets.append(len(document))
        document += b"%d 0 obj\n%s\nendobj\n" % (number, body_object)
    xref = len(document)
    document += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    document += b"".join(b"%010d 00000 n \n" % offset for offset in offsets)
    document += b"trailer\n<< /Size %d /Root 1 0 R >>\n" % (len(objects) + 1)
    document += b"startxref\n%d\n%%%%EOF\n" % xref
    return bytes(document)


def _pdf_string(line: str) -> bytes:
    """A PDF literal string; a character outside Windows-1252 fails the build."""
    raw = line.encode("cp1252")
    return b"(" + raw.replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)") + b")"


def xlsx(lines: list[str]) -> bytes:
    """A spreadsheet with a row per line and a cell per tab."""
    rows = [line.split("\t") for line in lines]
    cells = (
        "".join(_cell(f"{'ABCDEFGHIJ'[column]}{number}", value) for column, value in enumerate(row))
        for number, row in enumerate(rows, 1)
    )
    sheet = "".join(f'<row r="{number}">{row}</row>' for number, row in enumerate(cells, 1))
    main = f"{OPENXML}/spreadsheetml/2006/main"
    width = f'<col min="1" max="{max(map(len, rows))}" width="28" customWidth="1"/>'
    return _package(
        {
            "[Content_Types].xml": _content_types(
                {
                    "/xl/workbook.xml": f"{OFFICE}.spreadsheetml.sheet.main+xml",
                    "/xl/worksheets/sheet1.xml": f"{OFFICE}.spreadsheetml.worksheet+xml",
                }
            ),
            "_rels/.rels": _relationships("officeDocument", "xl/workbook.xml"),
            "xl/workbook.xml": f'<workbook xmlns="{main}" xmlns:r="{RELATIONSHIPS}"><sheets>'
            '<sheet name="Ark1" sheetId="1" r:id="rId1"/></sheets></workbook>',
            "xl/_rels/workbook.xml.rels": _relationships("worksheet", "worksheets/sheet1.xml"),
            "xl/worksheets/sheet1.xml": f'<worksheet xmlns="{main}"><cols>{width}</cols>'
            f"<sheetData>{sheet}</sheetData></worksheet>",
        }
    )


def _cell(reference: str, value: str) -> str:
    if not value:
        return ""
    return f'<c r="{reference}" t="inlineStr"><is><t>{escape(value)}</t></is></c>'


def docx(lines: list[str]) -> bytes:
    """A document with a paragraph per line, the first line as its heading."""
    heading, *body = [f'<w:t xml:space="preserve">{escape(line)}</w:t>' for line in lines]
    paragraphs = f'<w:p><w:r><w:rPr><w:b/><w:sz w:val="32"/></w:rPr>{heading}</w:r></w:p>'
    paragraphs += "".join(f"<w:p><w:r>{line}</w:r></w:p>" for line in body)
    main = f"{OPENXML}/wordprocessingml/2006/main"
    return _package(
        {
            "[Content_Types].xml": _content_types(
                {"/word/document.xml": f"{OFFICE}.wordprocessingml.document.main+xml"}
            ),
            "_rels/.rels": _relationships("officeDocument", "word/document.xml"),
            "word/document.xml": f'<w:document xmlns:w="{main}">'
            f"<w:body>{paragraphs}</w:body></w:document>",
        }
    )


def _content_types(overrides: dict[str, str]) -> str:
    parts = "".join(f'<Override PartName="{p}" ContentType="{t}"/>' for p, t in overrides.items())
    return (
        f'<Types xmlns="{OPENXML}/package/2006/content-types">'
        f'<Default Extension="rels" ContentType="{PACKAGE}.relationships+xml"/>'
        f'<Default Extension="xml" ContentType="application/xml"/>{parts}</Types>'
    )


def _relationships(kind: str, target: str) -> str:
    return (
        f'<Relationships xmlns="{OPENXML}/package/2006/relationships">'
        f'<Relationship Id="rId1" Type="{RELATIONSHIPS}/{kind}" Target="{target}"/>'
        "</Relationships>"
    )


def _package(parts: dict[str, str]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, xml in parts.items():
            info = zipfile.ZipInfo(name, MODIFIED)
            archive.writestr(info, XML + xml, compress_type=zipfile.ZIP_DEFLATED)
    return buffer.getvalue()


def png(lines: list[str]) -> bytes:
    """A picture fading through the colours on its lines (#rrggbb), top to bottom."""
    stops = [bytes.fromhex(line.strip().removeprefix("#")) for line in lines if line.strip()]
    width, height = PICTURE_SIZE
    pixels = b"".join(b"\0" + _blend(stops, y / (height - 1)) * width for y in range(height))
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + _png_chunk(b"IHDR", header)
        + _png_chunk(b"IDAT", zlib.compress(pixels, 9))
        + _png_chunk(b"IEND", b"")
    )


def _blend(stops: list[bytes], position: float) -> bytes:
    scaled = position * (len(stops) - 1)
    index = min(int(scaled), len(stops) - 2)
    weight = scaled - index
    pair = zip(stops[index], stops[index + 1], strict=True)
    return bytes(round(a + (b - a) * weight) for a, b in pair)


def _png_chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


FORMATS = {".pdf": pdf, ".xlsx": xlsx, ".docx": docx, ".png": png}


if __name__ == "__main__":
    persona = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    render(persona, Path(sys.argv[2]), sys.argv[3:])

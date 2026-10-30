#!/usr/bin/env python3
"""Regenerate the Tifinagh resources from the Latin Tamazight ones.

Usage:
    python3 tools/transliterate_tifinagh.py

Reads app/src/main/res/values-b+zgh+Latn/{strings,plurals}.xml and writes the
matching values-b+zgh+Tfng files. The transliteration follows the IRCAM
correspondence (Latin -> Tifinagh), drops the neutral vowel "e" as is usual in
Tifinagh writing, and leaves printf-style placeholders untouched.
"""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import escape, quoteattr

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"
SRC = RES / "values-b+zgh+Latn"
DST = RES / "values-b+zgh+Tfng"

MAP = {
    "a": "ⴰ", "b": "ⴱ", "c": "ⵛ", "d": "ⴷ", "ḍ": "ⴹ", "f": "ⴼ",
    "g": "ⴳ", "h": "ⵀ", "ḥ": "ⵃ", "i": "ⵉ", "j": "ⵊ", "k": "ⴽ", "l": "ⵍ",
    "m": "ⵎ", "n": "ⵏ", "o": "ⵓ", "q": "ⵇ", "r": "ⵔ", "ṛ": "ⵕ",
    "s": "ⵙ", "ṣ": "ⵚ", "t": "ⵜ", "ṭ": "ⵟ", "u": "ⵓ", "w": "ⵡ",
    "x": "ⵅ", "y": "ⵢ", "z": "ⵣ", "ẓ": "ⵥ", "ɣ": "ⵖ", "ɛ": "ⵄ",
    "ǧ": "ⴳⵢ", "ʷ": "ⵯ",
}

PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[bcdosxXeEfgGaA%]")


def transliterate(text: str) -> str:
    out: list[str] = []
    pos = 0
    for match in PLACEHOLDER.finditer(text):
        out.append(_plain(text[pos:match.start()]))
        out.append(match.group(0))
        pos = match.end()
    out.append(_plain(text[pos:]))
    return "".join(out)


def _plain(text: str) -> str:
    return "".join(MAP.get(ch.lower(), ch) for ch in text if ch.lower() != "e")


def _write(element: ET.Element, out: list[str], depth: int) -> None:
    indent = "\t" * depth
    attrs = "".join(
        f" {key}={quoteattr(value)}"
        for key, value in element.attrib.items()
        if not key.startswith("{")
    )
    text = (element.text or "").strip()
    children = list(element)

    if not children:
        out.append(f"{indent}<{element.tag}{attrs}>{escape(transliterate(text))}</{element.tag}>")
        return

    out.append(f"{indent}<{element.tag}{attrs}>")
    for child in children:
        _write(child, out, depth + 1)
    out.append(f"{indent}</{element.tag}>")


def process(name: str) -> None:
    tree = ET.parse(SRC / f"{name}.xml")
    root = tree.getroot()
    out = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Tamazight (Standard Moroccan) in Tifinagh. "
        "Generated from values-b+zgh+Latn by tools/transliterate_tifinagh.py; "
        "edit the Latin file instead. -->",
        "<resources>",
    ]
    for element in root:
        _write(element, out, 1)
    out.append("</resources>")
    out.append("")
    (DST / f"{name}.xml").write_text("\n".join(out), encoding="utf-8")


def main() -> None:
    DST.mkdir(parents=True, exist_ok=True)
    process("strings")
    process("plurals")


if __name__ == "__main__":
    main()

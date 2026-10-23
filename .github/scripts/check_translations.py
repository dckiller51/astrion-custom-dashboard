#!/usr/bin/env python3
"""Checks every translation against English, for CI (and for translators, locally).

    python3 .github/scripts/check_translations.py

Files checked, each language against its English file:
  - app/src/main/assets/docs/i18n/<lang>.json   web page and builder (+ its nested "ha" labels)
  - app/src/main/assets/ha_labels/<lang>.json   Home Assistant states shown on the remote
  - app/src/*/res/values-<lang>/strings.xml     the app (main and debug)

Errors (the job fails), because they break something on screen:
  - a file that isn't valid JSON / XML;
  - a key that English doesn't have (typo, or a key renamed in English);
  - placeholders (%1$s, %2$d, %%), HTML tags or line breaks that differ from English;
  - in strings.xml, an apostrophe Android needs escaped (\\').

Warnings only: keys not translated yet (English shows instead, and a partial
translation is welcome, see the wiki page "Translations"), and in strings.xml a
bare " that Android silently drops.
"""
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app" / "src"

PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sd]|%%")
TAG = re.compile(r"</?([a-zA-Z][a-zA-Z0-9]*)[^>]*>")

errors = []
warnings = []


def rel(path):
    return path.relative_to(ROOT).as_posix()


def error(path, msg):
    errors.append(f"{rel(path)}: {msg}")
    print(f"::error file={rel(path)}::{msg}")


def warning(path, msg):
    warnings.append(f"{rel(path)}: {msg}")
    print(f"::warning file={rel(path)}::{msg}")


def signature(text):
    """What must be identical in every language: placeholders, HTML tags, line breaks."""
    return (
        sorted(PLACEHOLDER.findall(text)),
        sorted(TAG.findall(text)),
        text.count("\n") + text.count("\\n"),
    )


def compare(path, english, local, where=""):
    """english/local: {key: str}. Reports extra keys, differing signatures, missing keys."""
    extra = sorted(set(local) - set(english))
    for key in extra:
        error(path, f"{where}\"{key}\" doesn't exist in English (typo, or renamed in English?)")
    for key, value in local.items():
        if key in english and signature(value) != signature(english[key]):
            en_ph, en_tags, en_nl = signature(english[key])
            ph, tags, nl = signature(value)
            details = []
            if ph != en_ph:
                details.append(f"placeholders {ph} instead of {en_ph}")
            if tags != en_tags:
                details.append(f"HTML tags {tags} instead of {en_tags}")
            if nl != en_nl:
                details.append(f"{nl} line break(s) instead of {en_nl}")
            error(path, f"{where}\"{key}\": " + ", ".join(details))
    missing = [k for k in english if k not in local]
    if missing:
        shown = ", ".join(missing[:5]) + (" …" if len(missing) > 5 else "")
        warning(path, f"{where}{len(missing)} key(s) not translated yet (English shows instead): {shown}")


def load_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, OSError) as e:
        error(path, f"not valid JSON: {e}")
        return None


def check_json_folder(folder, nested_key=None):
    """One <lang>.json per language; [nested_key] holds {category: {key: str}} (the HA labels)."""
    en_path = folder / "en.json"
    english = load_json(en_path)
    if english is None:
        return
    for path in sorted(folder.glob("*.json")):
        if path.name == "en.json":
            continue
        local = load_json(path)
        if local is None:
            continue
        flat_en = {k: v for k, v in english.items() if isinstance(v, str)}
        flat_local = {k: v for k, v in local.items() if isinstance(v, str)}
        compare(path, flat_en, flat_local)
        nested_en = english.get(nested_key, {}) if nested_key else english
        nested_local = local.get(nested_key, {}) if nested_key else local
        if nested_key or all(isinstance(v, dict) for v in english.values()):
            for category, labels in nested_en.items():
                if isinstance(labels, dict):
                    compare(path, labels, nested_local.get(category, {}), where=f"{category}.")
            for category in set(nested_local) - set(nested_en):
                error(path, f"category \"{category}\" doesn't exist in English")


BARE_APOSTROPHE = re.compile(r"(?<!\\)'")
BARE_QUOTE = re.compile(r'(?<!\\)"')


STRING_ELEMENT = re.compile(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', re.S)


def load_strings(path):
    """{name: raw text as written in the file} (so &lt;filename&gt; stays text, not a tag)."""
    try:
        ET.parse(path)
    except (ET.ParseError, OSError) as e:
        error(path, f"not valid XML: {e}")
        return None
    return dict(STRING_ELEMENT.findall(path.read_text(encoding="utf-8")))


def check_strings_xml():
    english = load_strings(APP / "main" / "res" / "values" / "strings.xml") or {}
    for path in sorted(APP.glob("*/res/values-*/strings.xml")):
        local = load_strings(path)
        if local is None:
            continue
        # A build-type folder (src/debug) only overrides a few strings: no "missing" warnings there.
        reference = english if path.parts[-4] == "main" else {k: english.get(k, "") for k in local}
        compare(path, reference, local)
        for key, value in local.items():
            if BARE_APOSTROPHE.search(value):
                error(path, f"\"{key}\": write \\' in strings.xml, Android rejects a bare apostrophe")
            elif BARE_QUOTE.search(value):
                warning(path, f"\"{key}\": a bare \" doesn't show on screen, write \\\" instead")


def main():
    check_json_folder(APP / "main" / "assets" / "docs" / "i18n", nested_key="ha")
    check_json_folder(APP / "main" / "assets" / "ha_labels")
    check_strings_xml()
    print(f"\nTranslations: {len(errors)} error(s), {len(warnings)} warning(s).")
    for line in errors:
        print("  ✗", line)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())

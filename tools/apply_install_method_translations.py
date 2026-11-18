#!/usr/bin/env python3
"""Appends the translations of the install-method settings to app/src/main/res/values-*/strings.xml.

Input: tools/i18n/install_method_translations.txt, blocks of the form
    === <locale>
    <32 lines, in the order of LINE_KEYS below>
Run from anywhere:  python3 tools/apply_install_method_translations.py
Existing keys are never overwritten, so it is safe to run it again.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
DATA = os.path.join(ROOT, "tools", "i18n", "install_method_translations.txt")

# The 32 translatable strings, in the order of the lines in every block.
LINE_KEYS = [
    "pref_install_method_title",
    "pref_install_method_standard",
    "pref_install_method_standard_summary",
    "pref_install_method_privileged_summary",
    "pref_install_method_root_summary",
    "pref_install_method_root_ok",
    "pref_install_method_root_failed",
    "pref_install_method_privileged_ok",
    "pref_install_method_privileged_not_installed",
    "pref_install_method_privileged_not_allowed",
    "pref_install_method_shizuku_summary",
    "pref_install_method_shizuku_ok",
    "pref_install_method_shizuku_not_running",
    "pref_install_method_shizuku_denied",
    "pref_install_method_shizuku_old",
    "pref_install_method_dhizuku_summary",
    "pref_install_method_dhizuku_ok",
    "pref_install_method_dhizuku_not_available",
    "pref_install_method_dhizuku_denied",
    "pref_install_method_microg_summary",
    "pref_install_method_microg_ok",
    "pref_install_method_microg_not_available",
    "pref_install_method_external",
    "pref_install_method_external_summary",
    "pref_install_method_external_ok",
    "pref_install_method_external_no_package",
    "pref_install_method_external_not_installed",
    "pref_install_method_external_cannot_install",
    "pref_external_installer_title",
    "pref_external_installer_empty",
    "pref_external_installer_hint",
    "pref_external_installer_error",
]

# Names that are not translated (product names).
CONSTANTS = {
    "pref_install_method_privileged": "F-Droid Privileged Extension",
    "pref_install_method_root": "Root (Magisk)",
    "pref_install_method_shizuku": "Shizuku",
    "pref_install_method_dhizuku": "Dhizuku",
    "pref_install_method_microg": "microG",
}

# locale in the data file -> res folder suffixes that get this text
TARGETS = {
    "pt-PT": ["pt", "pt-rPT"],
    "pt-BR": ["pt-rBR"],
    "es": ["es", "es-rAR", "es-rMX"],
    "nl": ["nl", "nl-rBE"],
    "bn": ["bn", "bn-rBD"],
    "id": ["id", "in"],
    "he": ["he", "iw"],
    "zh-CN": ["zh-rCN"],
    "zh-TW": ["zh-rTW", "zh-rHK", "yue"],
}


def escape(text: str) -> str:
    text = text.replace("&", "&amp;").replace("<", "&lt;")
    text = text.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    if text.startswith(("@", "?")):
        text = "\\" + text
    return text


def parse(path):
    blocks, current = {}, None
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.rstrip("\n")
            if line.startswith("=== "):
                current = line[4:].strip()
                blocks[current] = []
            elif current is not None and line.strip():
                blocks[current].append(line.strip())
    return blocks


def main():
    blocks = parse(DATA)
    bad = {k: len(v) for k, v in blocks.items() if len(v) != len(LINE_KEYS)}
    if bad:
        sys.exit("Wrong number of lines (expected %d): %s" % (len(LINE_KEYS), bad))
    done = []
    for locale, lines in blocks.items():
        for suffix in TARGETS.get(locale, [locale]):
            path = os.path.join(RES, "values-" + suffix, "strings.xml")
            if not os.path.isfile(path):
                print("skip (no strings.xml):", suffix)
                continue
            content = open(path, encoding="utf-8").read()
            if "pref_install_method_title" in content:
                continue
            entries = dict(zip(LINE_KEYS, lines))
            entries.update(CONSTANTS)
            order = ["pref_install_method_title", "pref_install_method_standard",
                     "pref_install_method_standard_summary", "pref_install_method_privileged",
                     "pref_install_method_privileged_summary", "pref_install_method_root",
                     "pref_install_method_root_summary", "pref_install_method_root_ok",
                     "pref_install_method_root_failed", "pref_install_method_privileged_ok",
                     "pref_install_method_privileged_not_installed",
                     "pref_install_method_privileged_not_allowed", "pref_install_method_shizuku",
                     "pref_install_method_shizuku_summary", "pref_install_method_shizuku_ok",
                     "pref_install_method_shizuku_not_running", "pref_install_method_shizuku_denied",
                     "pref_install_method_shizuku_old", "pref_install_method_dhizuku",
                     "pref_install_method_dhizuku_summary", "pref_install_method_dhizuku_ok",
                     "pref_install_method_dhizuku_not_available", "pref_install_method_dhizuku_denied",
                     "pref_install_method_microg", "pref_install_method_microg_summary",
                     "pref_install_method_microg_ok", "pref_install_method_microg_not_available",
                     "pref_install_method_external", "pref_install_method_external_summary",
                     "pref_install_method_external_ok", "pref_install_method_external_no_package",
                     "pref_install_method_external_not_installed",
                     "pref_install_method_external_cannot_install", "pref_external_installer_title",
                     "pref_external_installer_empty", "pref_external_installer_hint",
                     "pref_external_installer_error"]
            xml = "".join('    <string name="%s">%s</string>\n' % (k, escape(entries[k])) for k in order)
            i = content.rindex("</resources>")
            open(path, "w", encoding="utf-8").write(content[:i] + xml + content[i:])
            done.append(suffix)
    print("Updated %d folders: %s" % (len(done), " ".join(done)))


if __name__ == "__main__":
    main()

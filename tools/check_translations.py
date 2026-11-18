#!/usr/bin/env python3
"""Prueft die Uebersetzungen der Installationsmethoden in app/src/main/res/values*/strings.xml.

Geprueft wird fuer JEDEN Sprachordner, der diese Texte enthaelt:
  * die XML-Datei ist wohlgeformt
  * alle erwarteten Strings sind vorhanden, keiner doppelt, keiner leer
  * Android-Escapes stimmen (Apostroph, Anfuehrungszeichen, @ ? am Anfang, rohes & oder <)
  * Produktnamen (Shizuku, Dhizuku, microG, Root (Magisk), F-Droid Privileged Extension) wurden nicht uebersetzt
  * Platzhalter wie %s/%d kommen nicht vor (die Texte haben keine)
Exit-Code 1 bei Fehlern, damit CI/Skripte das erkennen.
"""
import glob
import os
import re
import sys
import xml.dom.minidom

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

sys.path.insert(0, os.path.join(ROOT, "tools"))
from apply_install_method_translations import CONSTANTS, LINE_KEYS  # noqa: E402

ALL_KEYS = set(LINE_KEYS) | set(CONSTANTS)
KEY_RE = re.compile(r'<string name="((?:pref_install_method|pref_external_installer)[^"]*)">(.*?)</string>', re.S)


def check_folder(path):
    problems = []
    try:
        xml.dom.minidom.parse(path)
    except Exception as e:  # noqa: BLE001
        return ["XML nicht wohlgeformt: %s" % e]
    content = open(path, encoding="utf-8").read()
    found = KEY_RE.findall(content)
    names = [k for k, _ in found]
    for k in sorted(ALL_KEYS - set(names)):
        problems.append("fehlt: %s" % k)
    for k in sorted({n for n in names if names.count(n) > 1}):
        problems.append("doppelt: %s" % k)
    for k in sorted(set(names) - ALL_KEYS):
        problems.append("unbekannter Schluessel: %s" % k)
    for k, v in found:
        if not v.strip():
            problems.append("leer: %s" % k)
        if re.search(r"(?<!\\)'", v):
            problems.append("Apostroph ohne Escape: %s" % k)
        if '"' in v.replace('\\"', ""):
            problems.append("Anfuehrungszeichen ohne Escape: %s" % k)
        if v.startswith(("@", "?")):
            problems.append("beginnt mit @ oder ?: %s" % k)
        if "&" in v.replace("&amp;", "") or "<" in v:
            problems.append("rohes & oder <: %s" % k)
        if re.search(r"%(\d+\$)?[sd]", v):
            problems.append("unerwarteter Platzhalter: %s" % k)
        if k in CONSTANTS and v != CONSTANTS[k]:
            problems.append("Produktname veraendert: %s = %r" % (k, v))
    return problems


def main():
    failed = 0
    checked = 0
    for path in sorted(glob.glob(os.path.join(RES, "values*", "strings.xml"))):
        folder = os.path.basename(os.path.dirname(path))
        if "pref_install_method_title" not in open(path, encoding="utf-8").read():
            continue  # Sprache ohne diese Texte, faellt auf Englisch zurueck
        checked += 1
        problems = check_folder(path)
        if problems:
            failed += 1
            print("FEHLER in %s:" % folder)
            for p in problems:
                print("  -", p)
    print("%d Sprachordner geprueft, %d mit Fehlern" % (checked, failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

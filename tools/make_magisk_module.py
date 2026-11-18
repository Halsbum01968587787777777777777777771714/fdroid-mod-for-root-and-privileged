#!/usr/bin/env python3
"""Packs the built Privileged Extension APK into a flashable Magisk/KernelSU module zip.

Usage: python tools/make_magisk_module.py [path-to-extension.apk] [output.zip]
Defaults: privileged-extension/build/outputs/apk/release/privileged-extension-release.apk
          -> fdroid-privileged-extension-own.zip
"""
import os
import sys
import zipfile

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
template = os.path.join(root, "tools", "magisk-module-template")
apk = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    root, "privileged-extension", "build", "outputs", "apk", "release",
    "privileged-extension-release.apk")
out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(root, "fdroid-privileged-extension-own.zip")

if not os.path.isfile(apk):
    sys.exit("APK not found: %s\nBuild it first: gradlew :privileged-extension:assembleRelease" % apk)

apk_in_module = "system/priv-app/F-DroidPrivilegedExtension/F-DroidPrivilegedExtension.apk"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for folder, _, files in os.walk(template):
        for name in files:
            full = os.path.join(folder, name)
            arc = os.path.relpath(full, template).replace(os.sep, "/")
            info = zipfile.ZipInfo(arc)
            info.external_attr = 0o755 << 16 if name == "update-binary" else 0o644 << 16
            with open(full, "rb") as f:
                z.writestr(info, f.read(), zipfile.ZIP_DEFLATED)
    info = zipfile.ZipInfo(apk_in_module)
    info.external_attr = 0o644 << 16
    with open(apk, "rb") as f:
        z.writestr(info, f.read(), zipfile.ZIP_DEFLATED)
print("Created", out)

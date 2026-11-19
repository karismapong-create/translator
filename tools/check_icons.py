#!/usr/bin/env python3
"""Ground rule: every icon in the app looks like the app's own icons (Material Symbols style).

An emoji in a text shown by the app is drawn as one of the app's vector icons (Ui.kt map), at the
start of a label or inside it. This check fails the build when a string uses an emoji that has no
icon in the map. Allowed as text: flags (language names) and plain arrows used in sentences.
Toasts and notifications can't show icons; the app strips emoji there (Ui.plain).
"""
import glob, re, sys

SRC = "app/src/main/java/com/example/interpreter"
ui = open(f"{SRC}/Ui.kt", encoding="utf-8").read()
keys = sorted({k.replace("️", "") for k in re.findall(r'"([^"A-Za-z0-9\s]{1,8})" to R\.drawable\.', ui)}, key=len, reverse=True)

ALLOWED = set("→←↔↑↓·•…▸")

def is_emoji(cp):
    return (0x1F000 <= cp <= 0x1FAFF or 0x2600 <= cp <= 0x27BF or 0x2B00 <= cp <= 0x2BFF
            or 0x2190 <= cp <= 0x21FF or 0x2300 <= cp <= 0x23FF or cp in (0x2139, 0x25B6, 0x25C0, 0x3030, 0x303D))

def is_flag(cp):
    return 0x1F1E6 <= cp <= 0x1F1FF

errors = []
for path in sorted(glob.glob(f"{SRC}/*.kt")):
    if path.endswith("/Ui.kt"):
        continue
    for ln, line in enumerate(open(path, encoding="utf-8"), 1):
        if line.lstrip().startswith("//"):
            continue
        for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', line):
            t = lit.replace("️", "")
            i = 0
            while i < len(t):
                k = next((k for k in keys if t.startswith(k, i)), None)
                if k:
                    i += len(k)
                    continue
                c = t[i]
                if is_emoji(ord(c)) and c not in ALLOWED and not is_flag(ord(c)) and c != "‍":
                    errors.append(f"{path.split('/')[-1]}:{ln}: '{c}' (U+{ord(c):04X}) has no app icon")
                i += 1

if errors:
    print("Icons must match the app's own icon style. Add a vector icon for these in Ui.kt (or use an existing one):")
    print("\n".join(sorted(set(errors))))
    sys.exit(1)
print(f"icons ok ({len(keys)} icons)")

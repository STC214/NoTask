"""Verify that the post-R8 Xposed entry is a real DEX class, not just a string."""
import struct
import sys
import zipfile


def dex_classes(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Invalid DEX header")
    count, offset = struct.unpack_from("<II", data, 56)
    strings = []
    for i in range(count):
        pos = struct.unpack_from("<I", data, offset + 4 * i)[0]
        while data[pos] & 0x80:
            pos += 1
        pos += 1
        end = data.index(b"\0", pos)
        strings.append(data[pos:end].decode("utf-8", errors="replace"))
    count, offset = struct.unpack_from("<II", data, 64)
    types = [strings[struct.unpack_from("<I", data, offset + 4 * i)[0]] for i in range(count)]
    count, offset = struct.unpack_from("<II", data, 96)
    return {types[struct.unpack_from("<I", data, offset + 32 * i)[0]] for i in range(count)}


with zipfile.ZipFile(sys.argv[1]) as apk:
    if apk.testzip() is not None:
        raise ValueError("APK ZIP checksum failure")
    entries = apk.read("META-INF/xposed/java_init.list").decode().splitlines()
    checked_entries = 0
    classes = set()
    for name in apk.namelist():
        if name.startswith("classes") and name.endswith(".dex"):
            classes.update(dex_classes(apk.read(name)))
    for entry in entries:
        entry = entry.strip()
        if entry and not entry.startswith("#"):
            descriptor = "L" + entry.replace(".", "/") + ";"
            if descriptor not in classes:
                raise ValueError("Missing module entry class: " + entry)
            print("MODULE_ENTRY_OK " + entry)
            checked_entries += 1
    if not checked_entries:
        raise ValueError("Empty module entry list")
    if "Lio/github/libxposed/api/XposedModule;" in classes:
        raise ValueError("Framework API must not be bundled")
    properties = dict(line.split("=", 1) for line in
                      apk.read("META-INF/xposed/module.prop").decode().splitlines() if "=" in line)
    if properties.get("staticScope") != "false" or properties.get("targetApiVersion") != "102":
        raise ValueError("Unexpected Xposed module metadata")
    print("MODULE_METADATA_OK api=102 dynamic_scope=true framework_api_not_bundled=true")
    print("APK_STRUCTURE_OK")

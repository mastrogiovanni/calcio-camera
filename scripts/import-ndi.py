#!/usr/bin/env python3
"""Import an official Android ARM64 shared runtime without downloading or accepting SDK terms."""
import argparse
import hashlib
import shutil
import struct
from pathlib import Path


def android_arm64(path):
    try:
        data = path.read_bytes()
        # ELF64, little endian, ET_DYN, EM_AARCH64. Reject macOS/iOS, x86 and ARM32.
        if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
            return False
        if struct.unpack_from("<HH", data, 16) != (3, 183):
            return False
        # Bionic dependency distinguishes the Android runtime from a Linux ARM64 build.
        return b"libc.so\x00" in data and b"NDIlib_send_send_video_v2\x00" in data
    except OSError:
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sdk", type=Path, help="Cartella SDK estratta oppure file Android libndi.so")
    args = parser.parse_args()
    root = args.sdk.expanduser().resolve()
    candidates = [root] if root.is_file() else sorted(root.rglob("libndi.so"))
    candidates = [p for p in candidates if android_arm64(p)]
    if len(candidates) != 1:
        parser.error("Serve un solo libndi.so Android ARM64 valido. Trovati: " + str(len(candidates))
                     + ". Se ci sono più versioni, indica il file esatto. Le librerie statiche .a non sono supportate.")
    source = candidates[0]
    target = Path(__file__).resolve().parents[1] / "app/src/main/jniLibs/arm64-v8a/libndi.so"
    target.parent.mkdir(parents=True, exist_ok=True)
    if source != target:
        shutil.copy2(source, target)
    print("Importato:", target)
    print("SHA-256:", hashlib.sha256(target.read_bytes()).hexdigest())
    print("Conserva licenza e avvisi del tuo SDK; eventuali dipendenze native Android vanno nella stessa cartella.")
    print("Compila: ./gradlew assembleNdiDebug")


if __name__ == "__main__":
    main()

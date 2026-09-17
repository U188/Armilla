#!/usr/bin/env python3
"""Pinned upstream Android JNI binaries. No model weights in the APK."""
import hashlib
import io
import os
from pathlib import Path
import sys
import tarfile
import urllib.request

URL = 'https://github.com/k2-fsa/sherpa-ncnn/releases/download/v2.1.15/sherpa-ncnn-v2.1.15-android.tar.bz2'
SHA256 = '7fbdb6f8bb1f8ec092467ed61d9ec85c01f060d125ccf632ef9d7ec5816d4a65'
ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')
LIBS = ('libsherpa-ncnn-jni.so', 'libncnn.so')


def prepare(output):
    with urllib.request.urlopen(URL, timeout=120) as response:
        data = response.read(12 * 1024 * 1024)
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise RuntimeError('Speech runtime SHA-256 mismatch')
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:bz2') as archive:
        for abi in ABIS:
            for name in LIBS:
                member = archive.getmember(f'./jniLibs/{abi}/{name}')
                if not member.isfile() or member.size > 30 * 1024 * 1024:
                    raise RuntimeError('Invalid runtime member')
                target = output / abi / name
                target.parent.mkdir(parents=True, exist_ok=True)
                tmp = target.with_suffix('.tmp')
                tmp.write_bytes(archive.extractfile(member).read())
                os.replace(tmp, target)


if __name__ == '__main__':
    prepare(Path(sys.argv[1]))

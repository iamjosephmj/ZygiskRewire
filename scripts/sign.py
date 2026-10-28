#!/usr/bin/env python3
"""
Signing tool for the module.

Implements module signing according to the following scheme:
  - machikado: per-architecture runtime file signatures
  - misaki: whole-module signature
  - sha256: per-file SHA-256 hashes
"""

import sys
import os
import struct
import hashlib
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

def file_sign_data(name: str, filepath: str) -> bytes:
  """
    Build the sign data block for a single file:
    name_bytes + 0x00 + 8-byte-LE-filesize + file_contents
  """

  data = bytearray()
  data.extend(name.encode('utf-8'))
  data.append(0)
  size = os.path.getsize(filepath)
  data.extend(struct.pack('<q', size))

  with open(filepath, 'rb') as f:
    while True:
      chunk = f.read(8192)
      if not chunk: break

      data.extend(chunk)

  return bytes(data)


def sign_machikado(module_dir: str, sig_name: str, abi: str, is_64bit: bool, private_key_bytes: bytes, public_key_bytes: bytes):
  """Sign a machikado file for a specific architecture.

  Per-build identity names come from the environment (exported by the Makefile
  from build/identity.mk) so the signed file names match the staged binaries.
  """

  root = Path(module_dir)
  arch_suffix = "64" if is_64bit else "32"
  path_suffix = "lib64" if is_64bit else "lib"

  mod_id = os.environ.get("ID_MOD_ID")
  lib_name = os.environ.get("ID_LIB_NAME")
  daemon_bin = os.environ.get("ID_DAEMON_BIN")
  ptracer_bin = os.environ.get("ID_PTRACER_BIN")
  if not (mod_id and lib_name and daemon_bin and ptracer_bin):
    print("error: ID_MOD_ID / ID_LIB_NAME / ID_DAEMON_BIN / ID_PTRACER_BIN must be exported (see build/identity.mk)")
    sys.exit(1)

  # INFO: Create the list by sorting them based on their virtual/relative path,
  #         and use their virtual/relative path as the name in the signature.
  entries = []

  # INFO: The files where virtual = real
  for fname in ["module.prop", f"{mod_id}.sh", "sepolicy.rule", "post-fs-data.sh", "service.sh", "uninstall.sh"]:
    vpath = root / fname
    entries.append((str(vpath), fname, str(vpath)))

  # INFO: lib(64)/{lib_name} -> lib/{abi}/{lib_name}
  vpath = root / path_suffix / lib_name
  rpath = root / "lib" / abi / lib_name
  entries.append((str(vpath), lib_name, str(rpath)))

  # INFO: bin/{ptracer_bin}{32|64} -> bin/{abi}/{ptracer_bin}{32|64}
  vpath = root / "bin" / f"{ptracer_bin}{arch_suffix}"
  rpath = root / "bin" / abi / f"{ptracer_bin}{arch_suffix}"
  entries.append((str(vpath), f"{ptracer_bin}{arch_suffix}", str(rpath)))

  # INFO: bin/{daemon_bin}{32|64} -> bin/{abi}/{daemon_bin}{32|64}
  vpath = root / "bin" / f"{daemon_bin}{arch_suffix}"
  rpath = root / "bin" / abi / f"{daemon_bin}{arch_suffix}"
  entries.append((str(vpath), f"{daemon_bin}{arch_suffix}", str(rpath)))

  # INFO: Sort by virtual path
  entries.sort(key=lambda e: e[0].replace("\\", "/"))

  # INFO: Accumulate all sign data
  sign_data = bytearray()
  for _, vname, rpath in entries:
    sign_data.extend(file_sign_data(vname, rpath))

  # INFO: Sign with Ed25519
  priv_key = Ed25519PrivateKey.from_private_bytes(private_key_bytes)
  signature = priv_key.sign(bytes(sign_data))

  # INFO: Write signature + public key
  sig_file = root / sig_name
  with open(sig_file, 'wb') as f:
    f.write(signature)
    f.write(public_key_bytes)

  print(f"  Signed {sig_name}")


def compute_sha256_hashes(module_dir: str):
  """Compute SHA-256 hash for every file in the module directory."""

  root = Path(module_dir)
  for fpath in sorted(root.rglob('*')):
    if not fpath.is_file(): continue

    # INFO: Don't hash the sha256 files themselves
    if fpath.suffix == '.sha256': continue

    md = hashlib.sha256()
    with open(fpath, 'rb') as f:
      while True:
        chunk = f.read(4096)
        if not chunk: break

        md.update(chunk)

    hash_file = Path(str(fpath) + ".sha256")
    hash_file.write_text(md.hexdigest())

def sign_misaki(module_dir: str, private_key_bytes: bytes, public_key_bytes: bytes):
  """Sign misaki.sig for the entire module."""

  root = Path(module_dir)

  # INFO: Collect all files, sorted by path
  all_files = []
  for fpath in root.rglob('*'):
    if not fpath.is_file() or fpath.name == "misaki.sig": continue

    all_files.append(fpath)

  all_files.sort(key=lambda f: str(f).replace("\\", "/"))

  # INFO: Accumulate sign data
  sign_data = bytearray()
  for fpath in all_files:
    sign_data.extend(file_sign_data(fpath.name, str(fpath)))

  priv_key = Ed25519PrivateKey.from_private_bytes(private_key_bytes)
  signature = priv_key.sign(bytes(sign_data))

  sig_file = root / "misaki.sig"
  with open(sig_file, 'wb') as f:
    f.write(signature)
    f.write(public_key_bytes)

  print("  Signed misaki.sig")

def main():
  if len(sys.argv) < 3:
    print("Usage: sign.py <module_dir> <private_key> <public_key>")
    print("       sign.py --no-sign <module_dir>")

    sys.exit(1)

  if sys.argv[1] == "--no-sign":
    module_dir = sys.argv[2]
    root = Path(module_dir)

    print("No private_key and public_key found, this build will not be signed")

    # INFO: Create empty machikado files
    for name in ["machikado.arm64", "machikado.arm", "machikado.x86_64", "machikado.x86"]:
      (root / name).touch()

    # INFO: Compute SHA256 hashes
    compute_sha256_hashes(module_dir)

    (root / "misaki.sig").touch()

    return

  module_dir = sys.argv[1]
  private_key_path = sys.argv[2]
  public_key_path = sys.argv[3]

  with open(private_key_path, 'rb') as f:
    private_key_bytes = f.read()
  with open(public_key_path, 'rb') as f:
    public_key_bytes = f.read()

  print("=== Guards the peace of Machikado ===")

  # INFO: Sign machikado for each architecture
  sign_machikado(module_dir, "machikado.arm64", "arm64-v8a", True, private_key_bytes, public_key_bytes)
  sign_machikado(module_dir, "machikado.arm", "armeabi-v7a", False, private_key_bytes, public_key_bytes)
  sign_machikado(module_dir, "machikado.x86_64", "x86_64", True, private_key_bytes, public_key_bytes)
  sign_machikado(module_dir, "machikado.x86", "x86", False, private_key_bytes, public_key_bytes)

  # INFO: Compute SHA256 hashes for all files (including machikado)
  compute_sha256_hashes(module_dir)

  print("===   At the kitsune's wedding   ===")

  # INFO: Sign misaki, that is excluded from sha256
  sign_misaki(module_dir, private_key_bytes, public_key_bytes)

if __name__ == '__main__':
  main()

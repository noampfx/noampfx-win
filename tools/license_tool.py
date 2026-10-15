#!/usr/bin/env python3
"""Offline license tool for NoAmp FX (run on the DEVELOPER's computer only).

  python tools/license_tool.py init                                   # once: create key pair + put public key into License.kt
  python tools/license_tool.py sign ABCD-EFGH-JKLM-NPQR --note "Budi" # issue a license key (CLI)
  (or double-click run_keygen.bat for the window version)

Requires:  pip install cryptography
KEEP tools/license_private.pem SECRET and BACK IT UP. Never commit it or ship it in the APK.
"""
import argparse, base64, csv, datetime, re, sys
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

HERE = Path(__file__).resolve().parent
PRIV = HERE / "license_private.pem"
LOG = HERE / "licenses.csv"
KT = HERE.parent / "src/main/kotlin/com/noamp/fx/License.kt"
PREFIX = "NOAMPFX1|"
CODE_RE = re.compile(r"^[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){3}$")


last_warning = None  # set when the key was made but could not be written to the log file


def log_license(code, note):
    """Append to licenses.csv. Never blocks key creation: on failure try a backup file, else return a warning."""
    row = [datetime.date.today().isoformat(), code, note]
    for path in (LOG, LOG.with_name("licenses_backup.csv")):
        try:
            new = not path.exists()
            with path.open("a", newline="", encoding="utf-8") as f:
                w = csv.writer(f)
                if new:
                    w.writerow(["date", "device_id", "note"])
                w.writerow(row)
            return None if path is LOG else f"Could not write {LOG.name} (is it open in Excel?). Logged to {path.name} instead."
        except OSError:
            continue
    return f"Could not write the log ({LOG.name}). Close it if it is open in Excel. The key is still valid."


def normalize_code(code):
    return "".join(code.split()).upper()


def make_key(code, note=""):
    """Returns the license key for a Device ID and logs it. Raises ValueError / FileNotFoundError."""
    code = normalize_code(code)
    if not CODE_RE.match(code):
        raise ValueError("Invalid Device ID format.\nExpected XXXX-XXXX-XXXX-XXXX (letters/digits, no 0, 1, I, O).")
    if not PRIV.exists():
        raise FileNotFoundError(f"Private key not found:\n{PRIV}\n\nRun once:  python tools/license_tool.py init")
    key = serialization.load_pem_private_key(PRIV.read_bytes(), password=None)
    sig = key.sign((PREFIX + code).encode(), ec.ECDSA(hashes.SHA256()))  # DER, as Java expects
    global last_warning
    last_warning = log_license(code, note)
    return base64.b64encode(sig).decode()


def init(force):
    if PRIV.exists() and not force:
        sys.exit(f"{PRIV} already exists. Use --force only if you really want a NEW key pair "
                 "(old licenses and old APKs will stop matching).")
    key = ec.generate_private_key(ec.SECP256R1())
    PRIV.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    pub = base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
    src = KT.read_text(encoding="utf-8")
    new, n = re.subn(r'(PUBLIC_KEY_B64\s*=\s*")[^"]*(")', lambda m: m.group(1) + pub + m.group(2), src)
    if n != 1:
        sys.exit("PUBLIC_KEY_B64 not found in License.kt")
    KT.write_text(new, encoding="utf-8")
    print(f"Private key : {PRIV}  (SECRET - back it up, do not commit)")
    print(f"Public key written to {KT}\nRebuild the Windows app now.")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("init"); a.add_argument("--force", action="store_true")
    b = sub.add_parser("sign"); b.add_argument("device_id"); b.add_argument("--note", default="")
    args = ap.parse_args()
    if args.cmd == "init":
        init(args.force)
    else:
        try:
            key = make_key(args.device_id, args.note)
            print(key)
            if last_warning:
                print("WARNING: " + last_warning, file=sys.stderr)
        except (ValueError, FileNotFoundError) as e:
            sys.exit(str(e))


if __name__ == "__main__":
    main()

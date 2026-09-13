#!/usr/bin/env python3
"""
release.py - PaperRescue production release automation.

Windows-first. Locates the React Native project, configures JAVA_HOME /
ANDROID_HOME from a local Android Studio install, writes android/local.properties,
generates a release signing key on first run (and never touches it again),
builds a signed APK and AAB, and packages everything (builds, docs, store
assets, screenshots, signing notes) into an external `releases/` folder next
to the project.

Usage:
    python release.py                    Full pipeline: env setup, build, package
    python release.py --check-env        Verify JDK/SDK/project detection only
    python release.py --generate-key-only    Create the release keystore, then exit
    python release.py --skip-build       Package whatever was already built
    python release.py --skip-screenshots Skip the emulator screenshot step
    python release.py --screenshots-only Only capture screenshots (needs a running emulator)
    python release.py --clean            Run `gradlew clean` before building
    python release.py --no-clean         Explicitly skip clean (default)
"""

import argparse
import os
import platform
import re
import secrets
import shutil
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

APP_NAME = "PaperRescue"
PACKAGE_NAME = "com.oldalexhub.paperrescue"
KEY_ALIAS = "paperrescue"
KEYSTORE_FILENAME = "paperrescue-release.keystore"
SCREENSHOT_COUNT_DEFAULT = 6
SCREENSHOT_INTERVAL_DEFAULT = 8  # seconds between captures, giving time to navigate manually


class ReleaseError(Exception):
    """Raised for unrecoverable setup problems; caught once in main() for a clean exit."""


# --------------------------------------------------------------------------
# Small console helpers
# --------------------------------------------------------------------------

def _supports_color() -> bool:
    return sys.stdout.isatty() and platform.system() != "Windows" or "ANSICON" in os.environ or "WT_SESSION" in os.environ


_USE_COLOR = _supports_color()


def _c(code: str, text: str) -> str:
    return f"\033[{code}m{text}\033[0m" if _USE_COLOR else text


def log(msg: str) -> None:
    print(f"{_c('36', '==>')} {msg}")


def ok(msg: str) -> None:
    print(f"{_c('32', ' ok ')} {msg}")


def warn(msg: str) -> None:
    print(f"{_c('33', 'warn')} {msg}")


def fail(msg: str) -> None:
    print(f"{_c('31', 'FAIL')} {msg}")


def section(title: str) -> None:
    print()
    print(_c("1;36", f"── {title} " + "─" * max(1, 60 - len(title))))


# --------------------------------------------------------------------------
# Process execution
# --------------------------------------------------------------------------

def run(cmd, cwd=None, env=None, check=True, capture=False):
    """Runs a command, streaming output live unless capture=True."""
    printable = " ".join(f'"{c}"' if " " in str(c) else str(c) for c in cmd)
    log(f"$ {printable}")
    if capture:
        result = subprocess.run(
            cmd, cwd=cwd, env=env, shell=False,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        )
        if result.returncode != 0 and check:
            print(result.stdout)
            raise ReleaseError(f"Command failed ({result.returncode}): {printable}")
        return result.stdout
    result = subprocess.run(cmd, cwd=cwd, env=env, shell=False)
    if result.returncode != 0 and check:
        raise ReleaseError(f"Command failed ({result.returncode}): {printable}")
    return None


# --------------------------------------------------------------------------
# Discovery: React Native project root
# --------------------------------------------------------------------------

def find_project_root() -> Path:
    """
    Locates the PaperRescue React Native app by searching outward from this
    script's own directory (the common case: release.py lives at the project
    root) and, failing that, scanning nearby directories for the tell-tale
    combination of package.json + android/app/build.gradle.
    """
    script_dir = Path(__file__).resolve().parent

    def looks_like_project(p: Path) -> bool:
        return (p / "package.json").exists() and (p / "android" / "app" / "build.gradle").exists()

    if looks_like_project(script_dir):
        return script_dir

    # Walk up a few levels, then scan immediate subdirectories at each level.
    current = script_dir
    for _ in range(4):
        if looks_like_project(current):
            return current
        try:
            for child in current.iterdir():
                if child.is_dir() and looks_like_project(child):
                    return child
        except PermissionError:
            pass
        if current.parent == current:
            break
        current = current.parent

    raise ReleaseError(
        "Could not locate the React Native project (expected a folder containing "
        "package.json and android/app/build.gradle). Run this script from inside "
        "the PaperRescue project, or place it at the project root."
    )


# --------------------------------------------------------------------------
# Discovery: JDK (Android Studio's bundled JBR preferred)
# --------------------------------------------------------------------------

def find_jdk() -> Path:
    candidates = []
    program_files = os.environ.get("PROGRAMFILES", r"C:\Program Files")
    program_files_x86 = os.environ.get("PROGRAMFILES(X86)", r"C:\Program Files (x86)")
    local_app_data = os.environ.get("LOCALAPPDATA", "")

    candidates += [
        Path(program_files) / "Android" / "Android Studio" / "jbr",
        Path(program_files_x86) / "Android" / "Android Studio" / "jbr",
        Path(local_app_data) / "Programs" / "Android Studio" / "jbr",
        # JetBrains Toolbox installs Android Studio under a versioned path.
        Path(local_app_data) / "JetBrains" / "Toolbox" / "apps" / "AndroidStudio" / "ch-0",
    ]

    for candidate in candidates:
        if candidate.name == "ch-0" and candidate.exists():
            # Toolbox nests an extra version directory; search one level deep.
            for sub in candidate.glob("*/Android Studio/jbr"):
                if (sub / "bin" / "java.exe").exists():
                    return sub
            continue
        if (candidate / "bin" / "java.exe").exists():
            return candidate

    env_home = os.environ.get("JAVA_HOME")
    if env_home and (Path(env_home) / "bin" / "java.exe").exists():
        return Path(env_home)

    where_java = shutil.which("java")
    if where_java:
        # java.exe -> .../bin/java.exe -> JDK root is two levels up.
        return Path(where_java).resolve().parent.parent

    raise ReleaseError(
        "Could not find a JDK. Install Android Studio (which bundles one), or "
        "set JAVA_HOME to a JDK 17+ installation."
    )


# --------------------------------------------------------------------------
# Discovery: Android SDK
# --------------------------------------------------------------------------

def find_android_sdk() -> Path:
    user_profile = os.environ.get("USERPROFILE", "")
    local_app_data = os.environ.get("LOCALAPPDATA", "")

    candidates = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        str(Path(local_app_data) / "Android" / "Sdk"),
        str(Path(user_profile) / "AppData" / "Local" / "Android" / "Sdk"),
    ]

    for candidate in candidates:
        if not candidate:
            continue
        p = Path(candidate)
        if (p / "platform-tools").exists() or (p / "platforms").exists():
            return p

    raise ReleaseError(
        "Could not find the Android SDK. Install Android Studio and complete its "
        "SDK setup, or set ANDROID_HOME to your SDK path."
    )


def find_adb(sdk_dir: Path) -> Path | None:
    adb = sdk_dir / "platform-tools" / "adb.exe"
    return adb if adb.exists() else None


def find_emulator_binary(sdk_dir: Path) -> Path | None:
    emu = sdk_dir / "emulator" / "emulator.exe"
    return emu if emu.exists() else None


# --------------------------------------------------------------------------
# Environment configuration
# --------------------------------------------------------------------------

def build_env(jdk_dir: Path, sdk_dir: Path) -> dict:
    env = os.environ.copy()
    env["JAVA_HOME"] = str(jdk_dir)
    env["ANDROID_HOME"] = str(sdk_dir)
    env["ANDROID_SDK_ROOT"] = str(sdk_dir)
    env["PATH"] = str(jdk_dir / "bin") + os.pathsep + str(sdk_dir / "platform-tools") + os.pathsep + env.get("PATH", "")
    return env


def write_local_properties(project_root: Path, sdk_dir: Path) -> None:
    """
    Uses forward slashes deliberately: Java's Properties format treats a bare
    backslash as an escape character (a literal "\\Users" is parsed as a
    broken \\u unicode escape), so a raw Windows path with single backslashes
    corrupts the SDK path silently. Forward slashes are accepted by Gradle/AGP
    on Windows and sidestep the whole issue.
    """
    local_properties = project_root / "android" / "local.properties"
    sdk_path = str(sdk_dir).replace("\\", "/")
    local_properties.write_text(f"sdk.dir={sdk_path}\n", encoding="utf-8")
    ok(f"Wrote {local_properties} (sdk.dir={sdk_path})")


# --------------------------------------------------------------------------
# Signing
# --------------------------------------------------------------------------

def ensure_signing(project_root: Path, jdk_dir: Path) -> dict:
    """
    Ensures android/keystore.properties + a release keystore exist. Never
    overwrites either if already present — this is the one file in the whole
    pipeline that must survive forever, since losing it means you can never
    publish an update to the same Play Store listing again.
    """
    android_dir = project_root / "android"
    keystore_properties_path = android_dir / "keystore.properties"
    keystore_dir = android_dir / "keystore"
    keystore_path = keystore_dir / KEYSTORE_FILENAME

    if keystore_properties_path.exists():
        ok(f"Release signing already configured ({keystore_properties_path}) — leaving it untouched.")
        return {"created": False, "keystore_path": keystore_path, "properties_path": keystore_properties_path}

    if keystore_path.exists():
        raise ReleaseError(
            f"A keystore already exists at {keystore_path} but android/keystore.properties "
            "is missing. Refusing to generate a new keystore (that would make the existing "
            "one, and any Play Store release already signed with it, permanently useless). "
            "Restore keystore.properties with the correct storePassword/keyPassword instead."
        )

    section("Generating release signing key (first run only)")
    keystore_dir.mkdir(parents=True, exist_ok=True)

    # PKCS12 keystores require the store password and key password to be
    # identical — keytool silently ignores a distinct -keypass and reuses the
    # store password instead, so we generate one password and use it for both
    # to avoid keystore.properties recording a key password Gradle can't use.
    store_password = secrets.token_urlsafe(24)
    key_password = store_password
    keytool = jdk_dir / "bin" / "keytool.exe"
    if not keytool.exists():
        raise ReleaseError(f"keytool not found at {keytool} — is JAVA_HOME pointing at a full JDK?")

    dname = "CN=Old Alex Hub, OU=PaperRescue, O=Old Alex Hub, L=Unknown, ST=Unknown, C=US"
    run([
        str(keytool), "-genkeypair", "-v",
        "-storetype", "PKCS12",
        "-keystore", str(keystore_path),
        "-alias", KEY_ALIAS,
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-storepass", store_password,
        "-keypass", key_password,
        "-dname", dname,
    ])

    # storeFile is relative to android/ (the Gradle rootProject), matching
    # how android/app/build.gradle resolves it via rootProject.file(...).
    keystore_properties_path.write_text(
        "\n".join([
            f"storeFile=keystore/{KEYSTORE_FILENAME}",
            f"storePassword={store_password}",
            f"keyAlias={KEY_ALIAS}",
            f"keyPassword={key_password}",
            "",
        ]),
        encoding="utf-8",
    )
    ok(f"Created {keystore_path}")
    ok(f"Created {keystore_properties_path}")
    warn("This keystore is the ONLY way to publish future updates to this app on Google Play.")
    warn("Back up android/keystore.properties AND android/keystore/ somewhere safe (outside this")
    warn("repo's normal git history if the repo is ever made public) — losing them permanently")
    warn("locks you out of updating this Play Store listing.")

    return {
        "created": True,
        "keystore_path": keystore_path,
        "properties_path": keystore_properties_path,
        "store_password": store_password,
        "key_password": key_password,
    }


# --------------------------------------------------------------------------
# Gradle build
# --------------------------------------------------------------------------

def gradlew_path(project_root: Path) -> Path:
    gradlew = project_root / "android" / "gradlew.bat"
    if not gradlew.exists():
        raise ReleaseError(f"gradlew.bat not found at {gradlew}")
    return gradlew


def run_gradle_task(project_root: Path, env: dict, task: str, extra_args=None) -> None:
    gradlew = gradlew_path(project_root)
    cmd = [str(gradlew), task, "--console=plain"] + (extra_args or [])
    run(cmd, cwd=str(project_root / "android"), env=env)


def read_version_info(project_root: Path) -> dict:
    build_gradle = (project_root / "android" / "app" / "build.gradle").read_text(encoding="utf-8")
    name_match = re.search(r'versionName\s+"([^"]+)"', build_gradle)
    code_match = re.search(r"versionCode\s+(\d+)", build_gradle)
    return {
        "versionName": name_match.group(1) if name_match else "0.0.0",
        "versionCode": code_match.group(1) if code_match else "0",
    }


# --------------------------------------------------------------------------
# Icon generation (best effort — requires Pillow)
# --------------------------------------------------------------------------

def maybe_generate_icons(project_root: Path) -> None:
    logo = project_root / "assets" / "logo.png"
    script = project_root / "scripts" / "generate_icons.py"
    if not logo.exists() or not script.exists():
        return
    try:
        import importlib
        importlib.import_module("PIL")
    except ImportError:
        warn("Pillow isn't installed, so launcher icons weren't regenerated from assets/logo.png.")
        warn("Run: pip install Pillow, then: python scripts/generate_icons.py")
        return
    section("Regenerating app icon from assets/logo.png")
    run([sys.executable, str(script)], cwd=str(project_root))


# --------------------------------------------------------------------------
# Packaging
# --------------------------------------------------------------------------

def releases_dir_for(project_root: Path) -> Path:
    # External to Gradle's own build output tree (so `gradlew clean` never
    # touches it) but kept inside the project root so it's visible in the
    # editor/workspace rather than one level up and easy to lose track of.
    return project_root / "releases"


def package_release(project_root: Path, version_info: dict, apk_path: Path | None,
                     aab_path: Path | None, signing_info: dict, screenshots_dir: Path | None) -> Path:
    section("Packaging release")
    releases_root = releases_dir_for(project_root)
    releases_root.mkdir(parents=True, exist_ok=True)

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir = releases_root / f"{APP_NAME}-v{version_info['versionName']}-{stamp}"
    out_dir.mkdir(parents=True, exist_ok=True)

    if apk_path and apk_path.exists():
        dest = out_dir / f"{APP_NAME}-v{version_info['versionName']}.apk"
        shutil.copy2(apk_path, dest)
        ok(f"Copied APK -> {dest}")
    else:
        warn("No APK found to package.")

    if aab_path and aab_path.exists():
        dest = out_dir / f"{APP_NAME}-v{version_info['versionName']}.aab"
        shutil.copy2(aab_path, dest)
        ok(f"Copied AAB (ready for Play Console upload) -> {dest}")
    else:
        warn("No AAB found to package.")

    docs_dir = out_dir / "docs"
    docs_dir.mkdir(exist_ok=True)
    for name in ("README.md", "PRIVACYPOLICY.md"):
        src = project_root / name
        if src.exists():
            shutil.copy2(src, docs_dir / name)

    store_assets_src = project_root / "store_assets"
    if store_assets_src.exists():
        shutil.copytree(store_assets_src, out_dir / "store_assets", dirs_exist_ok=True)

    branding_dir = out_dir / "branding"
    branding_dir.mkdir(exist_ok=True)
    logo = project_root / "assets" / "logo.png"
    if logo.exists():
        shutil.copy2(logo, branding_dir / "logo.png")

    if screenshots_dir and screenshots_dir.exists() and any(screenshots_dir.iterdir()):
        shutil.copytree(screenshots_dir, out_dir / "screenshots", dirs_exist_ok=True)

    signing_notes = out_dir / "SIGNING_NOTES.txt"
    lines = [
        f"{APP_NAME} signing notes",
        "=" * 40,
        f"Package: {PACKAGE_NAME}",
        f"Version: {version_info['versionName']} (code {version_info['versionCode']})",
        f"Key alias: {KEY_ALIAS}",
        f"Keystore file (kept OUTSIDE this package): {signing_info.get('keystore_path')}",
        f"Keystore config (kept OUTSIDE this package): {signing_info.get('properties_path')}",
        "",
    ]
    if signing_info.get("created"):
        lines += [
            "This run GENERATED a brand-new release keystore. The store/key passwords",
            "were written to android/keystore.properties (not copied into this folder",
            "on purpose). Back that file up somewhere safe right now — if it's lost,",
            "you can never publish another update to this Play Store listing under the",
            "same app.",
        ]
    else:
        lines += [
            "This run reused the existing release keystore. No new keys were generated.",
        ]
    signing_notes.write_text("\n".join(lines) + "\n", encoding="utf-8")
    ok(f"Wrote {signing_notes}")

    ok(f"Release package ready: {out_dir}")
    return out_dir


# --------------------------------------------------------------------------
# Screenshots
# --------------------------------------------------------------------------

def capture_screenshots(project_root: Path, sdk_dir: Path, count: int, interval: int) -> Path | None:
    section("Capturing screenshots from the connected emulator/device")
    adb = find_adb(sdk_dir)
    if not adb:
        warn("adb not found under the Android SDK — skipping screenshots.")
        return None

    devices_output = run([str(adb), "devices"], capture=True)
    device_lines = [
        line for line in devices_output.splitlines()[1:]
        if line.strip() and line.split()[-1] == "device"
    ]
    if not device_lines:
        warn("No connected/running emulator or device found — skipping screenshots.")
        warn("Start an emulator (or connect a device) and re-run with --screenshots-only.")
        return None

    device_serial = device_lines[0].split()[0]
    ok(f"Using device: {device_serial}")

    out_dir = releases_dir_for(project_root) / "_screenshots_latest"
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    print()
    print(_c("33", f"About to capture {count} screenshots, {interval}s apart."))
    print(_c("33", "Navigate the app on the device/emulator between captures to get a"))
    print(_c("33", "good variety (Home, Scanner, Page Review, Document Editor, Export, Library)."))
    print()

    for i in range(1, count + 1):
        time.sleep(interval)
        dest = out_dir / f"screenshot_{i:02d}.png"
        raw = subprocess.run(
            [str(adb), "-s", device_serial, "exec-out", "screencap", "-p"],
            stdout=subprocess.PIPE, check=False,
        )
        if raw.returncode == 0 and raw.stdout:
            dest.write_bytes(raw.stdout)
            ok(f"Captured {dest.name}")
        else:
            warn(f"Failed to capture screenshot {i}")

    return out_dir


# --------------------------------------------------------------------------
# Environment report
# --------------------------------------------------------------------------

def check_env(project_root: Path) -> None:
    section("Environment check")
    ok(f"Project root: {project_root}")

    try:
        jdk = find_jdk()
        ok(f"JDK: {jdk}")
    except ReleaseError as e:
        fail(str(e))

    try:
        sdk = find_android_sdk()
        ok(f"Android SDK: {sdk}")
        adb = find_adb(sdk)
        ok(f"adb: {adb}") if adb else warn("adb not found under the SDK's platform-tools.")
        emulator = find_emulator_binary(sdk)
        ok(f"emulator: {emulator}") if emulator else warn("emulator binary not found under the SDK.")
    except ReleaseError as e:
        fail(str(e))

    gradlew = project_root / "android" / "gradlew.bat"
    ok(f"gradlew.bat: {gradlew}") if gradlew.exists() else fail(f"Missing {gradlew}")

    keystore_properties = project_root / "android" / "keystore.properties"
    if keystore_properties.exists():
        ok("Release signing: configured (keystore.properties present)")
    else:
        warn("Release signing: not yet configured (will be generated on next full run)")

    version = read_version_info(project_root)
    ok(f"App version: {version['versionName']} (code {version['versionCode']})")


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------

def parse_args():
    parser = argparse.ArgumentParser(description=f"{APP_NAME} release automation")
    parser.add_argument("--check-env", action="store_true", help="Only verify JDK/SDK/project detection")
    parser.add_argument("--generate-key-only", action="store_true", help="Only generate the release keystore, then exit")
    parser.add_argument("--skip-build", action="store_true", help="Skip the Gradle build; package existing outputs")
    parser.add_argument("--skip-screenshots", action="store_true", help="Skip capturing emulator screenshots")
    parser.add_argument("--screenshots-only", action="store_true", help="Only capture screenshots, then exit")
    parser.add_argument("--clean", action="store_true", help="Run `gradlew clean` before building")
    parser.add_argument("--no-clean", action="store_true", help="Explicitly skip clean (default behavior)")
    parser.add_argument("--screenshot-count", type=int, default=SCREENSHOT_COUNT_DEFAULT)
    parser.add_argument("--screenshot-interval", type=int, default=SCREENSHOT_INTERVAL_DEFAULT)
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    if platform.system() != "Windows":
        warn("This script is designed and tested for Windows. Continuing anyway, but paths/tools "
             "(gradlew.bat, keytool.exe, adb.exe) assume a Windows layout.")

    try:
        project_root = find_project_root()
    except ReleaseError as e:
        fail(str(e))
        return 1

    if args.check_env:
        check_env(project_root)
        return 0

    try:
        jdk_dir = find_jdk()
        sdk_dir = find_android_sdk()
    except ReleaseError as e:
        fail(str(e))
        return 1

    section("Environment")
    ok(f"Project root: {project_root}")
    ok(f"JAVA_HOME:    {jdk_dir}")
    ok(f"ANDROID_HOME: {sdk_dir}")
    env = build_env(jdk_dir, sdk_dir)
    write_local_properties(project_root, sdk_dir)

    if args.screenshots_only:
        if not args.skip_screenshots:
            capture_screenshots(project_root, sdk_dir, args.screenshot_count, args.screenshot_interval)
        return 0

    signing_info = ensure_signing(project_root, jdk_dir)
    if args.generate_key_only:
        return 0

    maybe_generate_icons(project_root)

    apk_path = None
    aab_path = None

    if not args.skip_build:
        section("Building")
        if args.clean and not args.no_clean:
            run_gradle_task(project_root, env, "clean")

        run_gradle_task(project_root, env, "assembleRelease")
        run_gradle_task(project_root, env, "bundleRelease")

        apk_candidates = list((project_root / "android" / "app" / "build" / "outputs" / "apk" / "release").glob("*.apk"))
        aab_candidates = list((project_root / "android" / "app" / "build" / "outputs" / "bundle" / "release").glob("*.aab"))
        apk_path = apk_candidates[0] if apk_candidates else None
        aab_path = aab_candidates[0] if aab_candidates else None

        if apk_path:
            ok(f"Built APK: {apk_path}")
        else:
            warn("assembleRelease finished but no .apk was found in the expected output folder.")
        if aab_path:
            ok(f"Built AAB: {aab_path}")
        else:
            warn("bundleRelease finished but no .aab was found in the expected output folder.")
    else:
        warn("--skip-build set: reusing any previously built outputs.")
        apk_candidates = list((project_root / "android" / "app" / "build" / "outputs" / "apk" / "release").glob("*.apk"))
        aab_candidates = list((project_root / "android" / "app" / "build" / "outputs" / "bundle" / "release").glob("*.aab"))
        apk_path = apk_candidates[0] if apk_candidates else None
        aab_path = aab_candidates[0] if aab_candidates else None

    screenshots_dir = None
    if not args.skip_screenshots:
        screenshots_dir = capture_screenshots(project_root, sdk_dir, args.screenshot_count, args.screenshot_interval)

    version_info = read_version_info(project_root)
    out_dir = package_release(project_root, version_info, apk_path, aab_path, signing_info, screenshots_dir)

    section("Done")
    ok(f"Signed APK and AAB (if built) are packaged in: {out_dir}")
    ok("The AAB in that folder is ready to upload to Google Play Console.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ReleaseError as exc:
        fail(str(exc))
        sys.exit(1)
    except KeyboardInterrupt:
        warn("Interrupted.")
        sys.exit(130)

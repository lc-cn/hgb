"""What the phone's recognizer sees on the game screen right now (debug build only, see DebugProbe.kt).

    python3 tools/qa/probe.py [name] [--regions "x,y,w,h;x,y,w,h"] [--templates "id,id"] [--find 文字]

Uses the bundle already staged in debug-task/ for models and templates. Prints every text read on the whole frame
(index, confidence, box on the 2400x1080 reference frame), the text inside each asked region and each template's best
score, then copies frame.png / annotated.png / probe.json to .local/game-validation/probe/<folder>/ (private: the
frame may show other players' names). --find prints only texts containing that string.
"""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "io.github.yysassist"
REMOTE = f"/sdcard/Android/data/{PACKAGE}/files/debug-task/probe"


def adb():
    sdk = next(line.split("=", 1)[1].strip() for line in (ROOT / "local.properties").read_text().splitlines() if line.startswith("sdk.dir"))
    return str(Path(sdk) / "platform-tools/adb")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("name", nargs="?", default="")
    parser.add_argument("--regions", default="")
    parser.add_argument("--templates", default="")
    parser.add_argument("--find", default="")
    args = parser.parse_args()
    a = adb()
    subprocess.run([a, "shell", "rm", "-f", f"{REMOTE}/latest.json"], check=True)
    command = [a, "shell", "am", "broadcast", "-n", f"{PACKAGE}/.debug.DebugProbe", "-a", "io.github.yysassist.debug.PROBE"]
    for key in ("name", "regions", "templates"):
        if getattr(args, key):
            command += ["--es", key, f"'{getattr(args, key)}'"]
    subprocess.run(command, check=True, capture_output=True)
    report = None
    for _ in range(60):
        time.sleep(1)
        out = subprocess.run([a, "shell", "cat", f"{REMOTE}/latest.json"], capture_output=True, text=True).stdout
        try:
            report = json.loads(out)
            break
        except json.JSONDecodeError:
            continue
    if report is None:
        sys.exit("60 秒内没有拿到探测结果")
    if report.get("phase") != "finished":
        sys.exit(f"探测失败：{report.get('error')}")
    local = ROOT / ".local/game-validation/probe" / report["folder"]
    local.mkdir(parents=True, exist_ok=True)
    for name in ("frame.png", "annotated.png", "probe.json"):
        subprocess.run([a, "pull", "-q", f"{REMOTE}/{report['folder']}/{name}", str(local / name)], check=True)
    print(f"画面 {report['frame'][0]}x{report['frame'][1]}，任务包 {report['version']}，结果在 {local}")
    print("整屏文字（序号 置信度 [x,y,w,h] 文字）：")
    for i, t in enumerate(report["texts"]):
        if args.find in t["text"]:
            print(f"  {i:3d} {t['confidence']:.3f} {t['box']} {t['text']}")
    for region in report["regions"]:
        print(f"区域 {region['region']}：" + ("、".join(f"「{t['text']}」{t['confidence']:.2f}" for t in region["texts"]) or "没读到文字"))
    for template in report["templates"]:
        top = "；".join(f"{m['score']:.3f} {m['box']}" for m in template["matches"])
        print(f"模板 {template['template']}：最高 {template['best']:.3f}　{top}")


if __name__ == "__main__":
    main()

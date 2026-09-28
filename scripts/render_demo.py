#!/usr/bin/env python3
"""Join recorded browser clips into a silent MP4. Optional dependency: imageio-ffmpeg."""
import argparse
import json
import subprocess
from pathlib import Path
import imageio_ffmpeg


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recordings", default="target/demo-video-final")
    parser.add_argument("--output", default="docs/assets/agent-triage-demo.mp4")
    args = parser.parse_args()
    recordings = Path(args.recordings)
    manifest = json.loads((recordings / "clips.json").read_text(encoding="utf-8"))
    clips = [recordings / item["file"] for item in manifest["clips"]]
    if not clips or any(not clip.is_file() for clip in clips):
        parser.error("Record all clips before rendering.")
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    command = [imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-hide_banner", "-loglevel", "error"]
    for clip in clips:
        command += ["-i", str(clip)]
    inputs = "".join(f"[{index}:v]" for index in range(len(clips)))
    command += ["-filter_complex", f"{inputs}concat=n={len(clips)}:v=1:a=0,fps=25,format=yuv420p[v]",
                "-map", "[v]", "-c:v", "libx264", "-preset", "medium", "-crf", "21", "-movflags", "+faststart", str(output)]
    subprocess.run(command, check=True)
    print(f"Rendered {output}: {output.stat().st_size} bytes")


if __name__ == "__main__":
    main()

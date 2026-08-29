#!/usr/bin/env python3
"""Generate pink-noise test WAVs (Paul Kellett 6-pole pink noise, -3dB/oct).

One parameterized script replacing the former gen_sample_wav.py /
gen_96k_2ch_32bit_wav.py:

  python tools/gen_pink_noise_wav.py            # 48k/2ch/16bit built-in source (20s)
  python tools/gen_pink_noise_wav.py 96k32bit   # 96k/2ch/32bit test file (2min)

  # Custom format/duration
  python tools/gen_pink_noise_wav.py --out out.wav --rate 48000 --bits 24 --duration 30

Fixed SEED → regenerating produces identical content (-3dB/oct, broadband and gentle, no harsh highs).
The 96k hi-res file must be pushed to /data/ for the Hi-Res config (requires root/system permission).
"""
import argparse
import array
import os
import random
import struct
import wave

DEFAULT_SEED = 20260827
REPO_ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

# Presets keep the old scripts' one-shot behavior: 48k16bit is the bundled built-in source,
# 96k32bit is the hi-res test file
PRESETS = {
    "48k16bit": dict(
        out=os.path.join(REPO_ROOT, "app", "src", "main", "assets", "sample", "48k_2ch_16bit.wav"),
        rate=48000, bits=16, duration=20.0,
    ),
    "96k32bit": dict(
        out=os.path.join(REPO_ROOT, "96k_2ch_32bit.wav"),
        rate=96000, bits=32, duration=120.0,
    ),
}


def generate(out, sample_rate, bits, duration, channels=2, peak=0.25, fade=0.02, seed=DEFAULT_SEED):
    """Generate a normalized pink-noise WAV: first pass computes floating-point pink noise and its
    peak; second pass normalizes, applies fade-in/out and writes integer PCM."""
    if bits not in (16, 24, 32):
        raise ValueError(f"Unsupported bit depth: {bits} (supported: 16/24/32)")
    rng = random.Random(seed)
    n = int(sample_rate * duration)

    # First pass: generate floating-point pink noise and record the peak (array('f') for compact storage)
    frames = array.array('f')
    b0 = b1 = b2 = b3 = b4 = b5 = b6 = 0.0
    peak_val = 0.0
    for _ in range(n):
        w = rng.uniform(-1.0, 1.0)
        b0 = 0.99886 * b0 + w * 0.0555179
        b1 = 0.99332 * b1 + w * 0.0750759
        b2 = 0.96900 * b2 + w * 0.1538520
        b3 = 0.86650 * b3 + w * 0.3104856
        b4 = 0.55000 * b4 + w * 0.5329522
        b5 = -0.7616 * b5 - w * 0.0168980
        v = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
        b6 = w * 0.115926
        a = abs(v)
        if a > peak_val:
            peak_val = a
        frames.append(v)

    # Second pass: normalize to peak, add fade-in/out, write integer PCM (16/24/32-bit
    # little-endian, same value on all channels)
    # 24-bit is packed 3-byte little-endian; struct has no matching code, so per-sample to_bytes
    # is needed; 16/32-bit use struct for speed
    gain = peak / peak_val
    max_int = (1 << (bits - 1)) - 1
    fade_samples = int(fade * sample_rate)
    frame_bytes = channels * (bits // 8)
    fmt = None if bits == 24 else struct.Struct("<" + ("h" if bits == 16 else "i") * channels)
    buf = bytearray(n * frame_bytes)
    for i in range(n):
        env = 1.0
        if i < fade_samples:
            env = i / fade_samples
        elif i > n - fade_samples:
            env = (n - i) / fade_samples
        sample = int(frames[i] * gain * env * max_int)
        off = i * frame_bytes
        if fmt is not None:
            fmt.pack_into(buf, off, *([sample] * channels))
        else:
            raw = sample.to_bytes(3, "little", signed=True)
            for c in range(channels):
                buf[off + c * 3: off + c * 3 + 3] = raw

    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with wave.open(out, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(bits // 8)
        w.setframerate(sample_rate)
        w.writeframes(bytes(buf))
    print(f"Generated {out}: {os.path.getsize(out)} bytes, peak={peak_val:.3f}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("preset", nargs="?", default="48k16bit", choices=sorted(PRESETS),
                        help="preset (default 48k16bit = bundled built-in source); explicit parameters override preset values")
    parser.add_argument("--out", help="output path (defaults to the preset path)")
    parser.add_argument("--rate", type=int, default=None)
    parser.add_argument("--bits", type=int, default=None)
    parser.add_argument("--duration", type=float, default=None)
    args = parser.parse_args()

    preset = PRESETS[args.preset]
    generate(args.out or preset["out"],
             sample_rate=args.rate if args.rate is not None else preset["rate"],
             bits=args.bits if args.bits is not None else preset["bits"],
             duration=args.duration if args.duration is not None else preset["duration"])


if __name__ == "__main__":
    main()

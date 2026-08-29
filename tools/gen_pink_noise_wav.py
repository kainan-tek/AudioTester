#!/usr/bin/env python3
"""Generate pink-noise test WAVs (Paul Kellett 6-pole pink noise, -3dB/oct).

One parameterized script replacing the former gen_sample_wav.py /
gen_96k_2ch_32bit_wav.py:

  python tools/gen_pink_noise_wav.py            # 48k/2ch/16bit 内置音源 (20s)
  python tools/gen_pink_noise_wav.py 96k32bit   # 96k/2ch/32bit 测试文件 (2min)

  # 自定义格式/时长
  python tools/gen_pink_noise_wav.py --out out.wav --rate 48000 --bits 24 --duration 30

SEED 固定 → 重新生成内容一致（-3dB/oct，宽带柔和，无刺耳高频）。
96k hi-res 文件需推送到 /data/ 供 Hi-Res 配置使用（需 root/系统权限）。
"""
import argparse
import array
import os
import random
import struct
import wave

DEFAULT_SEED = 20260827
REPO_ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

# 预设保持旧脚本的一键行为：48k16bit 为打包内置音源，96k32bit 为 hi-res 测试文件
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
    """生成归一化粉噪 WAV：第一遍浮点粉噪取峰值，第二遍归一化+淡入淡出写 PCM int。"""
    rng = random.Random(seed)
    n = int(sample_rate * duration)

    # 第一遍：生成浮点粉噪并记录峰值（array('f') 紧凑存储）
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

    # 第二遍：归一化到 peak，加淡入淡出，写整数 PCM（16bit h / 其余按 i，多声道同值）
    gain = peak / peak_val
    max_int = (1 << (bits - 1)) - 1
    fade_samples = int(fade * sample_rate)
    frame_bytes = channels * (bits // 8)
    fmt = struct.Struct("<" + ("h" if bits == 16 else "i") * channels)
    buf = bytearray(n * frame_bytes)
    for i in range(n):
        env = 1.0
        if i < fade_samples:
            env = i / fade_samples
        elif i > n - fade_samples:
            env = (n - i) / fade_samples
        sample = int(frames[i] * gain * env * max_int)
        fmt.pack_into(buf, i * frame_bytes, *([sample] * channels))

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
                        help="预设（默认 48k16bit = 打包内置音源）；显式参数可覆盖预设值")
    parser.add_argument("--out", help="输出路径（默认取预设路径）")
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

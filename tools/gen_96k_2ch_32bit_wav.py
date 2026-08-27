#!/usr/bin/env python3
"""Generate a 2-min 96k/2ch/32bit hi-res test WAV (gentle pink noise).

Used with the Hi-Res Media Playback config (audioFilePath=/data/96k_2ch_32bit.wav):
push the output to /data/ on the device (requires root/system permission).

Pink noise: -3dB/oct, broadband and soft (waterfall-like), audible from the
first second, no harsh treble. Paul Kellett 6-pole approximation.
"""
import array
import math
import os
import random
import struct
import wave

OUT = os.path.join(os.path.dirname(__file__), "..", "96k_2ch_32bit.wav")

SAMPLE_RATE = 96000
CHANNELS = 2
BITS = 32
DURATION = 120.0
PEAK = 0.25  # 归一化峰值振幅，压低保证不刺耳
FADE = 0.02  # 20ms 淡入淡出，避免噪声起始/结束爆音
SEED = 20260827  # 固定种子 → 重新生成内容一致

rng = random.Random(SEED)
n = int(SAMPLE_RATE * DURATION)

# 第一遍：生成浮点粉噪并记录峰值（array('f') 紧凑存储，~46MB）
frames = array.array('f')
b0 = b1 = b2 = b3 = b4 = b5 = b6 = 0.0
peak = 0.0
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
    if a > peak:
        peak = a
    frames.append(v)

# 第二遍：归一化到 PEAK，加淡入淡出，写 32bit PCM int
gain = PEAK / peak
MAX_I32 = 2147483647
fade_samples = int(FADE * SAMPLE_RATE)
buf = bytearray(n * CHANNELS * (BITS // 8))
fmt = struct.Struct("<ii")  # 立体声，双声道同值
for i in range(n):
    env = 1.0
    if i < fade_samples:
        env = i / fade_samples
    elif i > n - fade_samples:
        env = (n - i) / fade_samples
    sample = int(frames[i] * gain * env * MAX_I32)
    fmt.pack_into(buf, i * 8, sample, sample)

os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
with wave.open(OUT, "wb") as w:
    w.setnchannels(CHANNELS)
    w.setsampwidth(BITS // 8)
    w.setframerate(SAMPLE_RATE)
    w.writeframes(bytes(buf))
print(f"Generated {OUT}: {os.path.getsize(OUT)} bytes, peak={peak:.3f}")

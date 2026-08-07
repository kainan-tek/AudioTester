#!/usr/bin/env python3
"""Generate the bundled 48k/2ch/16bit sample WAV (10s exponential log sweep 20Hz-20kHz)."""
import math
import os
import struct
import wave

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "sample", "48k_2ch_16bit.wav")

SAMPLE_RATE = 48000
CHANNELS = 2
DURATION = 10.0
F0, F1 = 20.0, 20000.0
AMPLITUDE = 0.4
FADE = 0.01  # 10ms 淡入淡出，避免起始/结束爆音

n = int(SAMPLE_RATE * DURATION)
frames = bytearray()
k = DURATION / math.log(F1 / F0)
for i in range(n):
    t = i / SAMPLE_RATE
    phase = 2.0 * math.pi * F0 * k * ((F1 / F0) ** (t / DURATION) - 1.0)
    env = 1.0
    if t < FADE:
        env = t / FADE
    elif t > DURATION - FADE:
        env = (DURATION - t) / FADE
    sample = int(AMPLITUDE * env * math.sin(phase) * 32767)
    frames += struct.pack("<hh", sample, sample)  # 立体声，双声道同值

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with wave.open(OUT, "wb") as w:
    w.setnchannels(CHANNELS)
    w.setsampwidth(2)
    w.setframerate(SAMPLE_RATE)
    w.writeframes(bytes(frames))
print(f"Generated {OUT}: {os.path.getsize(OUT)} bytes")

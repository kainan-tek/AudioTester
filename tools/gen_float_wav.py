# Generate a 96kHz/2ch/32-bit IEEE float WAV (WAVE_FORMAT_IEEE_FLOAT = 3).
# 440Hz sine at 0.25 amplitude, 30 seconds. Pure stdlib, no numpy needed.
import array, math, struct, sys

SR = 96000
CH = 2
SECS = 30
N = SR * SECS

# Interleaved stereo samples as IEEE 754 float32 (array 'f', little-endian on x86)
arr = array.array('f')
for i in range(N):
    v = 0.25 * math.sin(2 * math.pi * 440 * i / SR)
    arr.append(v)
    arr.append(v)
data = arr.tobytes()

# fmt chunk: audioFormat=3 (IEEE float), ch, sampleRate, byteRate, blockAlign, bits
fmt = struct.pack('<HHIIHH', 3, CH, SR, SR * CH * 4, CH * 4, 32)

def chunk(cid: bytes, payload: bytes) -> bytes:
    return cid + struct.pack('<I', len(payload)) + payload

riff = b'WAVE' + chunk(b'fmt ', fmt) + chunk(b'data', data)
out = b'RIFF' + struct.pack('<I', len(riff)) + riff

path = sys.argv[1]
with open(path, 'wb') as f:
    f.write(out)
print(f"wrote {path}: {len(out)} bytes, {SECS}s, {SR}Hz/{CH}ch/float32")

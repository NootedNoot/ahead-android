#!/usr/bin/env python3
"""
Ahead Alert Tone Synthesizer
Generates psychoacoustically distinct, melodic 16-bit 44.1kHz WAV alert tones
for Android notification and alarm channels:
  - Low alerts: Descending melodic chime motif (G5 -> B4, or A5->E5->A4).
  - High alerts: Ascending melodic chime motif (C5 -> A5, or C5->F5->C6).
  - Calm alerts: Gentle marimba bell chimes with soft attack and decay.
  - Urgent alerts: Double-burst cascades commanding immediate attention.
  - Signal lost: Alternating sonar disconnect pulse (622Hz / 440Hz).
"""
import os
import wave
import struct
import math

SAMPLE_RATE = 44100

def note(freq, dur, peak=0.85, attack=0.015, decay=12.0, harmonics=((1.0, 0.65), (2.0, 0.25), (3.0, 0.10))):
    n = int(SAMPLE_RATE * dur)
    res = []
    for i in range(n):
        t = i / SAMPLE_RATE
        env = (t / attack) if t < attack else math.exp(-(t - attack) * decay)
        val = sum(h_amp * math.sin(2 * math.pi * (freq * mult) * t) for mult, h_amp in harmonics)
        res.append(val * env * peak)
    return res

def gap(dur):
    return [0.0] * int(SAMPLE_RATE * dur)

def save_wav(name, samples, output_dir):
    os.makedirs(output_dir, exist_ok=True)
    out_path = os.path.join(output_dir, f"{name}.wav")
    max_val = max(max(abs(s) for s in samples), 0.001)
    scale = 32000.0 / max(max_val, 1.0)
    int_samples = [int(s * scale) for s in samples]
    raw = struct.pack(f"<{len(int_samples)}h", *int_samples)
    with wave.open(out_path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(raw)
    print(f"Generated {out_path} ({len(int_samples)/SAMPLE_RATE:.3f}s)")

def main():
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    output_dir = os.path.join(base_dir, "app", "src", "main", "res", "raw")

    # 1. WARN_LOW: Distinct 2-note descending chime (G5 -> B4) - unmistakable "falling / low"
    save_wav("tone_warn_low", note(784.0, 0.20, peak=0.9, decay=10.0) + gap(0.03) + note(493.88, 0.36, peak=0.85, decay=8.0), output_dir)

    # 2. WARN_HIGH: Distinct 2-note ascending chime (C5 -> A5) - unmistakable "rising / high"
    save_wav("tone_warn_high", note(523.25, 0.20, peak=0.85, decay=10.0) + gap(0.03) + note(880.0, 0.36, peak=0.9, decay=8.0), output_dir)

    # 3. CALM_LOW: Soft gentle descending bell (E5 -> C5)
    save_wav("tone_calm_low", note(659.25, 0.18, peak=0.6, decay=9.0) + gap(0.02) + note(523.25, 0.32, peak=0.55, decay=7.0), output_dir)

    # 4. CALM_HIGH: Soft gentle ascending bell (C5 -> E5)
    save_wav("tone_calm_high", note(523.25, 0.18, peak=0.55, decay=9.0) + gap(0.02) + note(659.25, 0.32, peak=0.6, decay=7.0), output_dir)

    # 5. URGENT_LOW: Rapid 3-note drop, repeated twice (A5->E5->A4 x2)
    burst_low = note(880.0, 0.09, peak=0.95, decay=15.0) + note(659.25, 0.09, peak=0.95, decay=15.0) + note(440.0, 0.18, peak=0.9, decay=10.0)
    save_wav("tone_urgent_low", burst_low + gap(0.08) + burst_low + note(440.0, 0.15, peak=0.8, decay=8.0), output_dir)

    # 6. URGENT_HIGH: Rapid 3-note climb, repeated twice (C5->F5->C6 x2)
    burst_high = note(523.25, 0.09, peak=0.9, decay=15.0) + note(698.46, 0.09, peak=0.95, decay=15.0) + note(1046.5, 0.18, peak=0.95, decay=10.0)
    save_wav("tone_urgent_high", burst_high + gap(0.08) + burst_high + note(1046.5, 0.15, peak=0.85, decay=8.0), output_dir)

    # 7. SIGNAL_LOST: Alternating sonar disconnect ping (622Hz / 440Hz)
    save_wav("tone_signal_lost", note(622.25, 0.13, peak=0.8, decay=14.0) + gap(0.05) + note(440.0, 0.13, peak=0.75, decay=14.0) + gap(0.07) + note(622.25, 0.13, peak=0.8, decay=14.0) + gap(0.05) + note(440.0, 0.24, peak=0.75, decay=8.0), output_dir)

if __name__ == "__main__":
    main()

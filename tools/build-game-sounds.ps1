param([string]$Voice = 'Microsoft Kangkang')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$soundRoot = Join-Path $projectRoot 'app\src\main\res\raw'
New-Item -ItemType Directory -Path $soundRoot -Force | Out-Null

# Deterministic wooden taps, 22.05 kHz / mono / PCM16. No downloaded recordings.
Add-Type -TypeDefinition @'
using System;
using System.IO;
public static class XiangqiSoundAssets {
    const int Rate = 22050;
    public static void Write(string path, short[] samples) {
        using (var w = new BinaryWriter(File.Create(path))) {
            w.Write(System.Text.Encoding.ASCII.GetBytes("RIFF")); w.Write(36 + samples.Length * 2);
            w.Write(System.Text.Encoding.ASCII.GetBytes("WAVEfmt ")); w.Write(16);
            w.Write((short)1); w.Write((short)1); w.Write(Rate); w.Write(Rate * 2);
            w.Write((short)2); w.Write((short)16);
            w.Write(System.Text.Encoding.ASCII.GetBytes("data")); w.Write(samples.Length * 2);
            foreach (short s in samples) w.Write(s);
        }
    }
    static double Tap(double t, double pitch, Random random) {
        if (t < 0) return 0;
        double attack = Math.Min(1, t / .0015);
        return attack * (.48 * Math.Sin(2 * Math.PI * pitch * t) * Math.Exp(-t * 42)
            + .22 * Math.Sin(2 * Math.PI * pitch * 2.73 * t) * Math.Exp(-t * 65)
            + .16 * Math.Sin(2 * Math.PI * pitch * 4.17 * t) * Math.Exp(-t * 85)
            + .22 * (random.NextDouble() * 2 - 1) * Math.Exp(-t * 120));
    }
    public static void TapFile(string path, bool capture) {
        var random = new Random(capture ? 732 : 731);
        var pcm = new short[(int)(Rate * (capture ? .23 : .14))];
        for (int i = 0; i < pcm.Length; i++) {
            double t = (double)i / Rate;
            double value = capture ? .65 * Tap(t, 560, random) + .78 * Tap(t - .075, 820, random)
                                   : .85 * Tap(t, 720, random);
            pcm[i] = (short)(Math.Max(-1, Math.Min(1, value)) * 26000);
        }
        Write(path, pcm);
    }
    public static void TrimVoice(string path) {
        byte[] wav = File.ReadAllBytes(path); int offset = 12, start = -1, size = 0;
        while (offset + 8 <= wav.Length) {
            int n = BitConverter.ToInt32(wav, offset + 4);
            if (System.Text.Encoding.ASCII.GetString(wav, offset, 4) == "data") { start = offset + 8; size = n; break; }
            offset += 8 + n + (n % 2);
        }
        if (start < 0 || size < 2 || start + size > wav.Length) throw new Exception("Invalid voice WAV");
        var samples = new short[size / 2]; int first = -1, last = -1, peak = 1;
        for (int i = 0; i < samples.Length; i++) {
            samples[i] = BitConverter.ToInt16(wav, start + i * 2);
            int level = Math.Abs((int)samples[i]); peak = Math.Max(peak, level);
            if (level > 180) { if (first < 0) first = i; last = i; }
        }
        if (first < 0) throw new Exception("Voice recording is silent");
        first = Math.Max(0, first - Rate / 50); last = Math.Min(samples.Length - 1, last + Rate / 10);
        var trimmed = new short[last - first + 1];
        for (int i = 0; i < trimmed.Length; i++) trimmed[i] = (short)(samples[first + i] * (26000.0 / peak));
        Write(path, trimmed);
    }
}
'@
[XiangqiSoundAssets]::TapFile((Join-Path $soundRoot 'move.wav'), $false)
[XiangqiSoundAssets]::TapFile((Join-Path $soundRoot 'capture.wav'), $true)

# This build helper needs an installed Chinese Windows voice; the Android game does not.
Add-Type -AssemblyName System.Speech
$speaker = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $speaker.SelectVoice($Voice)
    $speaker.Rate = 1
    $speaker.Volume = 100
    $format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(22050,
        [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
        [System.Speech.AudioFormat.AudioChannel]::Mono)
    $voicePath = Join-Path $soundRoot 'check.wav'
    $speaker.SetOutputToWaveFile($voicePath, $format)
    $speaker.Speak('将军！')
    $speaker.SetOutputToNull()
} finally { $speaker.Dispose() }
[XiangqiSoundAssets]::TrimVoice($voicePath)
Get-ChildItem -LiteralPath $soundRoot -Filter '*.wav' | Select-Object Name,Length

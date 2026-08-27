# -*- coding: utf-8 -*-
"""Bridges the main audiblez process to a persistent CosyVoice3 worker
running inside the isolated audiblez/.venv-cosyvoice venv.

CosyVoice3's own dependencies pin torch==2.3.1, incompatible with the
cu126/2.13 torch the rest of audiblez runs (see cosyvoice-src/reedd_service.py
for the full reasoning) -- it cannot be imported into this process the way
Kokoro is. This spawns that other venv's python once, running
reedd_service.py, and talks to it over stdin/stdout JSON lines for the
lifetime of one book conversion; the model itself loads once in that
subprocess, not per sentence.
"""
import json
import os
import subprocess
from pathlib import Path

import numpy as np
import soundfile

_AUDIBLEZ_ROOT = Path(__file__).resolve().parent.parent
COSYVOICE_PYTHON = str(_AUDIBLEZ_ROOT / '.venv-cosyvoice' / 'bin' / 'python')
COSYVOICE_SRC = _AUDIBLEZ_ROOT / 'cosyvoice-src'
COSYVOICE_SERVICE = str(COSYVOICE_SRC / 'reedd_service.py')


class CosyVoiceBridge:
    """One subprocess, one loaded model, reused across every flagged
    sentence in the book -- same lifetime contract as a TTSEngine (see
    engines.py), even though this itself isn't one (it's wrapped by
    ExpressiveEngine, which is).
    """

    def __init__(self, reference_wav_path):
        if not Path(COSYVOICE_PYTHON).exists():
            raise RuntimeError(
                f'CosyVoice venv not found at {COSYVOICE_PYTHON}. Expressive delivery needs '
                'a separate venv (its own requirements.txt pins an incompatible torch): create '
                'audiblez/.venv-cosyvoice, pip install torch+torchaudio matching its '
                'requirements.txt, then the rest of cosyvoice-src/requirements.txt (skip '
                'deepspeed/tensorrt/vllm/grpcio -- training/serving-only), plus onnxruntime-gpu '
                'and `pip install --no-build-isolation openai-whisper==20231117`. See '
                'server/README.md\'s "Expressive delivery via CosyVoice3" section.')
        # PYTHONPATH here (not just reedd_service.py's own sys.path.append)
        # is what makes cosyvoice-src/sitecustomize.py auto-load -- Python's
        # site module processes sitecustomize before the script itself runs
        # a single line, so appending to sys.path from inside the script is
        # too late to trigger it. Without this, wetext hits ModelScope's
        # network on every book instead of using the cached resource.
        env = dict(os.environ, PYTHONPATH=str(COSYVOICE_SRC))
        self._proc = subprocess.Popen(
            [COSYVOICE_PYTHON, '-u', COSYVOICE_SERVICE],
            cwd=str(COSYVOICE_SRC), env=env,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=None,
            text=True, bufsize=1,
        )
        response = self._call({'cmd': 'init', 'reference_wav': reference_wav_path})
        if response.get('status') != 'ready':
            raise RuntimeError(f'CosyVoice3 worker failed to initialize: {response}')
        self.sample_rate = response['sample_rate']

    def _call(self, obj):
        self._proc.stdin.write(json.dumps(obj) + '\n')
        self._proc.stdin.flush()
        line = self._proc.stdout.readline()
        if not line:
            raise RuntimeError('CosyVoice3 worker process exited unexpectedly')
        return json.loads(line)

    def synthesize(self, text, instruct):
        """Returns one numpy audio array at self.sample_rate."""
        response = self._call({'cmd': 'synthesize', 'text': text, 'instruct': instruct})
        if response.get('status') != 'ok':
            raise RuntimeError(f'CosyVoice3 synthesis failed: {response.get("message")}')
        audio, sr = soundfile.read(response['wav_path'], dtype='float32')
        assert sr == self.sample_rate, (sr, self.sample_rate)
        if audio.ndim > 1:
            audio = audio.mean(axis=1)
        return audio.astype(np.float32)

    def close(self):
        try:
            self._call({'cmd': 'shutdown'})
        except Exception:
            pass
        self._proc.terminate()

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass

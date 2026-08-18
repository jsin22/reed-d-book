# -*- coding: utf-8 -*-
"""Kokoro's own phonemizer needs espeak-ng found and pointed at explicitly.

Split out of core.py so it can be called from audiblez.engines.KokoroEngine
without engines.py needing to import core.py (and core.py importing engines.py
the other way already) -- not every engine needs this at all: Pocket TTS uses
its own tokenizer, not espeak/phonemizer, so it never calls this.
"""

import os
import platform
import subprocess
import traceback
from glob import glob
from pathlib import Path


def set_espeak_library():
    """Find the espeak library path"""
    try:

        if os.environ.get('ESPEAK_LIBRARY'):
            library = os.environ['ESPEAK_LIBRARY']
        elif platform.system() == 'Darwin':
            from subprocess import check_output
            try:
                cellar = Path(check_output(["brew", "--cellar"], text=True).strip())
                pattern = cellar / "espeak-ng" / "*" / "lib" / "*.dylib"
                if not (library := next(iter(glob(str(pattern))), None)):
                    raise RuntimeError("No espeak-ng library found; please set the path manually")
            except (subprocess.CalledProcessError, FileNotFoundError) as e:
                raise RuntimeError("Cannot locate Homebrew Cellar. Is 'brew' installed and in PATH?") from e
        elif platform.system() == 'Linux':
            # Debian/Ubuntu use /usr/lib/<triplet>/, Fedora/RHEL use /usr/lib64/.
            candidates = (glob('/usr/lib/*/libespeak-ng*')
                          + glob('/usr/lib64/libespeak-ng*')
                          + glob('/usr/lib/libespeak-ng*')
                          + glob('/usr/local/lib*/libespeak-ng*'))
            if not candidates:
                raise RuntimeError('No espeak-ng library found; set ESPEAK_LIBRARY to its path')
            library = candidates[0]
        elif platform.system() == 'Windows':
            library = 'C:\\Program Files*\\eSpeak NG\\libespeak-ng.dll'
        else:
            print('Unsupported OS, please set the espeak library path manually')
            return
        print('Using espeak library:', library)
        from phonemizer.backend.espeak.wrapper import EspeakWrapper
        EspeakWrapper.set_library(library)
    except Exception:
        traceback.print_exc()
        print("Error finding espeak-ng library:")
        print("Probably you haven't installed espeak-ng.")
        print("On Mac: brew install espeak-ng")
        print("On Linux: sudo apt install espeak-ng")

# -*- coding: utf-8 -*-
"""The language model behind "ask about the book": the per-chapter book
guide (book_guide.py) and the reader's own questions.

One small interface, `generate`, so a different backend (a local model via
Ollama is the planned one) can replace Gemini by configuration alone --
REEDD_BOOK_AI_BACKEND / REEDD_BOOK_AI_MODEL. Gemini is called the same way
llm_metadata.py already does: stdlib urllib, plain REST, no client library.
"""

import json
import time
import urllib.error
import urllib.request

from .config import get_settings

#: A whole chapter goes in per guide call; generous, since none of this runs
#: on a path a reader is waiting on except a question, which is far smaller.
_TIMEOUT_SECONDS = 120
#: Free-tier Gemini rate-limits bursts (429) and is occasionally overloaded
#: (503 -- seen live on 2026-09-28); both are worth a short wait and retry.
_RETRYABLE = {429, 500, 503}
_RETRIES = 4


class BookAIError(Exception):
    """The model could not be reached or gave nothing usable."""


def generate(system: str, prompt: str, json_output: bool = False) -> str:
    """The model's reply text. [json_output] asks for a single JSON object
    (still the caller's job to parse and validate it)."""
    settings = get_settings()
    if settings.book_ai_backend != 'gemini':
        raise BookAIError(f'unknown book AI backend {settings.book_ai_backend!r}')
    return _gemini(settings, system, prompt, json_output)


def _gemini(settings, system: str, prompt: str, json_output: bool) -> str:
    if not settings.gemini_api_key:
        raise BookAIError('no Gemini API key configured (REEDD_GEMINI_API_KEY)')
    config = {'responseMimeType': 'application/json'} if json_output else {}
    body = json.dumps({
        'systemInstruction': {'parts': [{'text': system}]},
        'contents': [{'role': 'user', 'parts': [{'text': prompt}]}],
        'generationConfig': config,
    }).encode('utf-8')
    url = (
        'https://generativelanguage.googleapis.com/v1beta/models/'
        f'{settings.book_ai_model}:generateContent?key={settings.gemini_api_key}'
    )
    delay = 5.0
    for attempt in range(_RETRIES):
        request = urllib.request.Request(url, data=body, headers={'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=_TIMEOUT_SECONDS) as response:
                payload = json.loads(response.read().decode('utf-8'))
            break
        except urllib.error.HTTPError as e:
            if e.code in _RETRYABLE and attempt < _RETRIES - 1:
                time.sleep(delay)
                delay *= 2
                continue
            raise BookAIError(f'Gemini HTTP {e.code}') from e
        except (urllib.error.URLError, TimeoutError) as e:
            if attempt < _RETRIES - 1:
                time.sleep(delay)
                delay *= 2
                continue
            raise BookAIError(f'could not reach Gemini: {e}') from e
    try:
        return payload['candidates'][0]['content']['parts'][0]['text']
    except (KeyError, IndexError, TypeError) as e:
        # e.g. a safety block on a violent passage: no candidate text at all.
        reason = (payload.get('promptFeedback') or {}).get('blockReason') or 'no text in the reply'
        raise BookAIError(f'Gemini gave no answer ({reason})') from e

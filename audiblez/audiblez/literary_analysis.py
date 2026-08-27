# -*- coding: utf-8 -*-
"""Phase 2 of the expressive-narration pipeline (see newplan.md): one local
LLM call per chapter (via Ollama) that flags a subset of *dialogue* lines for
CosyVoice3 delivery instead of plain Kokoro narration.

Local, not the Claude API: an Anthropic API key bills separately from a
Claude Pro/Claude Code subscription, which is not what the user wants to pay
for here. Runs against Ollama (http://localhost:11434), default model
`qwen2.5:7b-instruct` -- chosen for the 2060's 6GB VRAM (Ollama's default
pull is Q4_K_M-quantized, ~4.7GB) and Ollama's JSON-schema-constrained
output, which keeps structured-output reliability solid even on a smaller
model.

Narration never reaches this module at all -- only "quote"-kind sentences
from quote_split.split_into_spans() are candidates. Two reasons this is
narrower than the first version of this design (which considered every
sentence and asked for "the most dramatic, capped at N"):

1. Volume: in a dialogue-heavy chapter, over half the sentences can be
   inside quotes (measured directly: 53% on a real chapter). Routing all of
   that through CosyVoice3 (~35 chars/sec vs Kokoro's ~950 on GPU) would
   make CosyVoice3's speed dominate the whole book's conversion time,
   defeating the entire point of splitting engines. Narrowing the candidate
   pool to dialogue, and the bar within it to "significantly different from
   neutral delivery" (not "the most dramatic N"), keeps this genuinely
   sparse without a hard cap doing all the work.
2. Reliability: asked for "at most N dramatic" with no scoping, a smaller
   local model does not self-enforce well -- measured directly:
   qwen2.5:7b-instruct returned 7 when asked for at most 5, five of them
   consecutive sentences from one scene. A hard cap is still enforced in
   code below (see _RESPONSE_SCHEMA's comment), but the real fix is a
   narrower, more selective question to begin with.

Two independent reasons a dialogue line gets flagged:

1. SIGNIFICANT TONE SHIFT -- delivery meaningfully different from neutral
   conversational speech (screaming, whispering, and similarly marked
   shifts) -- not subtle emotional coloring, which plain Kokoro narration
   already handles fine from the text itself.
2. NON-WORD content -- interjections, drawn-out sounds, filler words, a
   meaningful "..." -- flagged regardless of tone, purely because a standard
   phonemizer mangles them (confirmed directly: StyleTTS2 flattened
   "Mmmmmm-hmmmmmm" down to a bare "mmm mmm" before synthesis ever saw it;
   CosyVoice3 handled the same line correctly).

The model only ever returns an index into the dialogue-only candidate list
plus an instruction string, never the sentence text itself -- an LLM asked
to also echo text back is an LLM that can subtly rewrite it, and this
pipeline's transcript has to stay exact.
"""
import json

import requests

OLLAMA_URL = 'http://localhost:11434/api/chat'
DEFAULT_MODEL = 'qwen2.5:7b-instruct'

# Backstop, not the primary sparsity control -- see module docstring. The
# "significant tone shift, not subtle" bar is what's actually supposed to
# keep this sparse; this just guards against total prompt-following failure.
MAX_TONE_FLAGGED_PER_CHAPTER = 5

# Ollama's structured-output mode wants a JSON Schema for the *whole*
# response object, not a bare top-level array -- more reliably supported
# across Ollama versions than an array-rooted schema. analyze_chapter()
# unwraps the "flagged" key after parsing.
_RESPONSE_SCHEMA = {
    'type': 'object',
    'properties': {
        'flagged': {
            'type': 'array',
            'items': {
                'type': 'object',
                'properties': {
                    'index': {'type': 'integer'},
                    'instruction': {'type': 'string'},
                    'reason': {'type': 'string', 'enum': ['tone_shift', 'non_word']},
                },
                'required': ['index', 'instruction', 'reason'],
            },
        },
    },
    'required': ['flagged'],
}

_SYSTEM_PROMPT = """\
You are directing audiobook narration. You will be given a chapter's \
dialogue lines -- text already confirmed to be spoken by a character, each \
on its own numbered line. Most lines should be left alone; only flag a line \
for either of two reasons:

1. TONE_SHIFT: the line needs delivery that is SIGNIFICANTLY different from \
neutral conversational speech -- screaming, whispering, or a similarly \
extreme, marked shift. Do NOT flag ordinary emotional coloring (mildly \
happy, a bit annoyed, casually sarcastic) -- normal narration-quality \
delivery already handles that fine from the text itself. Only the lines \
where the delivery is dramatically different from how the line would \
normally be read.

2. NON_WORD: the line is, or contains, an interjection, a drawn-out sound, \
a filler word, or punctuation standing in for a sound -- things a normal \
text-to-speech phonemizer mispronounces because they are not standard \
words. Examples: "Mmmmmm-hmmmmmm", "uh huh", "grrrr", a trailing "...", \
laughter spelled out like "hahaha". Flag every one of these you find.

For each flagged line, give a short, direct delivery instruction in the \
style of "Whisper this, hushed and afraid" or "Scream this in a panic" or \
"Say this as a drawn-out, skeptical hmm sound" -- describe HOW it should \
sound, in one clause, not a paragraph.

Respond with a JSON object of this exact shape:
{{"flagged": [{{"index": 3, "instruction": "...", "reason": "tone_shift"}}, \
{{"index": 17, "instruction": "...", "reason": "non_word"}}]}}

"reason" must be exactly "tone_shift" or "non_word", matching which of the \
two categories above got this line flagged.

Use "flagged": [] if nothing in these lines warrants either flag -- that is \
the expected, normal result for most chapters. Do not include the line's \
text itself anywhere in your response -- only its index, the instruction, \
and the reason.\
"""


def analyze_chapter(dialogue_sentences, model=DEFAULT_MODEL):
    """One local Ollama call for one chapter's dialogue lines (already
    filtered to quote-kind sentences by the caller -- see analyze_book()).

    Returns {sentence_text: instruction} for the flagged subset -- everything
    absent from the dict means "plain Kokoro narration, unchanged" (true for
    every narration sentence unconditionally, and for any dialogue line this
    call didn't flag). Never raises: a malformed response, a failed call, or
    Ollama not running are all treated the same as "nothing flagged" (see
    ExpressiveEngine), since this is an optional enhancement, not something
    synthesis should ever block on.
    """
    if not dialogue_sentences:
        return {}

    numbered = '\n'.join(f'{i}: {s}' for i, s in enumerate(dialogue_sentences))
    try:
        response = requests.post(OLLAMA_URL, json={
            'model': model,
            'messages': [
                {'role': 'system', 'content': _SYSTEM_PROMPT},
                {'role': 'user', 'content': numbered},
            ],
            'format': _RESPONSE_SCHEMA,
            'stream': False,
        }, timeout=120)
        response.raise_for_status()
        raw = response.json()['message']['content']
        flagged = json.loads(raw)['flagged']
    except Exception as e:
        print(f'Warning: literary analysis failed for this chapter, falling back to plain narration: {e}')
        return {}

    tone_shift, non_word = [], []
    for item in flagged:
        try:
            index = int(item['index'])
            instruction = str(item['instruction']).strip()
            reason = item.get('reason')
        except (KeyError, TypeError, ValueError):
            continue
        if not (0 <= index < len(dialogue_sentences) and instruction):
            continue
        (tone_shift if reason == 'tone_shift' else non_word).append((index, instruction))

    # Enforced here, not just requested in the prompt -- see module docstring
    # for why a smaller local model needs this backstop. NON_WORD (and
    # anything with an unrecognized/missing reason, treated the same way as a
    # conservative default) stays uncapped -- every interjection a
    # phonemizer would mangle, not just some of them.
    if len(tone_shift) > MAX_TONE_FLAGGED_PER_CHAPTER:
        tone_shift = tone_shift[:MAX_TONE_FLAGGED_PER_CHAPTER]

    annotations = {}
    for index, instruction in tone_shift + non_word:
        annotations[dialogue_sentences[index]] = instruction
    return annotations


def unload_model(model=DEFAULT_MODEL):
    """Explicitly unloads the model from Ollama's (and so the GPU's) memory.

    Ollama's own default idle timeout is much longer than "until the next
    thing in this process needs the GPU" -- confirmed directly: the model
    was still resident in VRAM 20+ minutes after the previous call. Relying
    on that timeout would risk this model and CosyVoice3 both being resident
    in the 2060's 6GB at once (see ExpressiveEngine's construction-order
    comment in engines.py) depending on exactly how fast analyze_book()
    finishes. Called once, after the whole book's worth of chapters are
    analyzed -- not per-chapter, which would force a reload every call for
    no reason during analyze_book()'s own loop.
    """
    try:
        requests.post(OLLAMA_URL, json={'model': model, 'messages': [], 'keep_alive': 0}, timeout=30)
    except Exception as e:
        print(f'Warning: could not explicitly unload {model} from Ollama: {e}')


def analyze_book(chapter_texts, split_into_spans):
    """Runs analyze_chapter() over every chapter's *dialogue-only* sentences
    and merges the results into one {sentence_text: instruction} dict
    spanning the whole book -- the shape ExpressiveEngine.synthesize() looks
    sentences up against.

    `split_into_spans` is passed in (rather than imported directly) to avoid
    this module importing core.py, which imports engines.py, which would
    import this module -- see engines.py's ExpressiveEngine for the same
    dependency direction. Expected to return [(kind, sentence), ...] per
    chapter, kind in {"narration", "quote"} -- see quote_split.py. Only
    "quote" sentences are ever sent to the LLM; narration is never a
    candidate, by construction, not by the model's judgment.
    """
    annotations = {}
    for i, text in enumerate(chapter_texts, start=1):
        spans = split_into_spans(text)
        dialogue_sentences = [sentence for kind, sentence in spans if kind == 'quote']
        print(f'Analyzing chapter {i}/{len(chapter_texts)} for expressive delivery '
              f'({len(dialogue_sentences)} dialogue line(s) of {len(spans)} total sentences)...')
        chapter_annotations = analyze_chapter(dialogue_sentences)
        if chapter_annotations:
            print(f'  Flagged {len(chapter_annotations)} line(s).')
        annotations.update(chapter_annotations)
    unload_model()
    return annotations


_DIRECT_ALL_SCHEMA = {
    'type': 'object',
    'properties': {
        'instructions': {'type': 'array', 'items': {'type': 'string'}},
    },
    'required': ['instructions'],
}

_DIRECT_ALL_SYSTEM_PROMPT = """\
You are directing audiobook narration. You will be given a chapter's \
dialogue lines -- text already confirmed to be spoken by a character, each \
on its own numbered line. For EVERY line, give a short, direct delivery \
instruction describing how it should be said -- tone, pace, emotion, or (for \
interjections/filler sounds/drawn-out noises) how the sound itself should be \
voiced. Base each instruction on that line's own content and immediate \
context, in the style of "Say this calmly and warmly" or "Whisper this, \
hushed and afraid" or "Say this as a drawn-out, skeptical hmm sound" -- one \
clause, not a paragraph. Every line gets an instruction, including ordinary \
lines -- there is no threshold to clear.

Respond with a JSON object of this exact shape:
{{"instructions": ["...", "...", ...]}}

The array must have exactly {n} entries, in the same order as the numbered \
lines given to you. Do not include the line's text itself in your response \
-- only the instructions, in order.\
"""


def direct_all_dialogue(dialogue_sentences, model=DEFAULT_MODEL):
    """Experimental alternative to analyze_chapter(): every dialogue line
    gets CosyVoice3 + an LLM-written instruction, with no flagging/filtering
    step at all. Returns {sentence_text: instruction} covering *all* of
    `dialogue_sentences` (barring a malformed/short response -- see below).

    Not the production default (see analyze_chapter()/analyze_book() for
    that): this exists to compare against the sparse-flagging design before
    deciding which one to keep, and trades a real amount of synthesis speed
    for it -- CosyVoice3 handling 100% of a chapter's dialogue rather than a
    handful of flagged lines. Never raises, same fallback contract as
    analyze_chapter(): a failed/malformed call returns an empty dict (every
    line stays on Kokoro), it does not block synthesis.
    """
    if not dialogue_sentences:
        return {}

    numbered = '\n'.join(f'{i}: {s}' for i, s in enumerate(dialogue_sentences))
    try:
        response = requests.post(OLLAMA_URL, json={
            'model': model,
            'messages': [
                {'role': 'system', 'content': _DIRECT_ALL_SYSTEM_PROMPT.format(n=len(dialogue_sentences))},
                {'role': 'user', 'content': numbered},
            ],
            'format': _DIRECT_ALL_SCHEMA,
            'stream': False,
        }, timeout=180)
        response.raise_for_status()
        raw = response.json()['message']['content']
        instructions = json.loads(raw)['instructions']
    except Exception as e:
        print(f'Warning: literary analysis failed for this chapter, falling back to plain narration: {e}')
        return {}

    # If the model returned the wrong count (has happened with smaller local
    # models -- see analyze_chapter()'s docstring for the same class of
    # unreliability), pair up what's there rather than discard the whole
    # response; any sentence left without an instruction stays on Kokoro.
    annotations = {}
    for sentence, instruction in zip(dialogue_sentences, instructions):
        instruction = str(instruction).strip()
        if instruction:
            annotations[sentence] = instruction
    if len(instructions) != len(dialogue_sentences):
        print(f'Warning: asked for {len(dialogue_sentences)} instructions, got {len(instructions)}; '
              f'{len(dialogue_sentences) - len(annotations)} line(s) left on Kokoro.')
    return annotations

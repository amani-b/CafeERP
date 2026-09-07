# Voice mode (Phase 8) — EN + AM, free / zero backend cost

Client-side only: the browser's native **Web Speech API**
(`SpeechRecognition` for input, `speechSynthesis` for output).
No paid STT/TTS service, no server compute, no new backend endpoint.
Implementation: `static/js/assistant-voice.js` + voice controls in
`fragments/layout.html` + Phase 8 section of `assistant-chat.css`.

## Why native Web Speech (and what was rejected)

Amharic support in Web Speech is **inconsistent across browsers** —
that is the core constraint, and the implementation is built around
detecting it at runtime rather than assuming it.

| Option | Cost | Amharic quality | Verdict |
|---|---|---|---|
| Native `SpeechRecognition` w/ `lang="am-ET"` | Free | Good in Chrome (Google's recognizer lists `am-ET` / አማርኛ); absent in Firefox (disabled by default) and legacy Edge | **Primary STT** with honest fallback |
| Native `speechSynthesis` w/ `am-ET` voice | Free | Good **where an OS Amharic voice pack is installed** (Edge Win11 `Mekdes`/`AmehaNeural`, Android Google TTS, some ChromeOS); missing on most bare desktops | **Primary TTS** with text-only fallback |
| In-browser Whisper (`Transformers.js`, `tiny`) | Free but ~50 MB download | Worse Amharic than Chrome native; dead weight elsewhere | **Rejected** — heavier, worse, still needs fallback |
| Paid STT/TTS API (Google Cloud, Azure, OpenAI) | $$$ + backend proxy | Best quality | **Rejected** — violates the zero-added-cost constraint |

## Capability contract (no silent failures)

All detection happens at runtime in `assistant-voice.js`:

- **STT support:** `window.SpeechRecognition || window.webkitSpeechRecognition`.
  Missing → mic button disabled with tooltip + inline status on tap:
  *"Voice input isn't supported in this browser yet — try Chrome. You can still type."*
- **STT `language-not-supported` for `am-ET`:** inline status —
  *"Voice input for Amharic isn't supported in this browser yet — you can still type in Amharic."*
  (Never a garbled transcription passed off as Amharic.)
- **Mic denied / no internet:** distinct messages (permission hint vs.
  service-needs-internet hint) rather than one generic error.
- **TTS support:** `'speechSynthesis' in window`. Voices resolve
  asynchronously (`voiceschanged` + a 1.5 s re-poll for Chrome's lazy list).
- **Amharic voice:** `lang` starts with `am`, or name matches
  `amhar|mekdes|ameha|ethiop`. A reply containing Ge'ez script
  (`[\u1200-\u137F…]`) or transliterated-Amharic keywords with **no**
  Amharic voice on device → **text-only + brief note**, never read in an
  English voice (which mangles it):
  *"🔇 No Amharic voice found in this browser — showing text only.
  To hear Amharic aloud, install an Amharic (am-ET) voice in your OS language settings."*

## UX

- **Mic button** (composer): tap to start/stop. Interim transcript streams
  into the composer; the final text lands there for **review, then the user
  presses Send** — dictation is never auto-sent, so a mis-transcription
  (especially Amharic) can't fire off a wrong turn unattended.
- **Language select** `Auto / EN / አማ`: `Auto` follows `navigator.language`
  (`am-*` → `am-ET`, else `en-US`); manual override restarts recognition
  immediately. Persisted in `localStorage`.
- **Read-aloud toggle** (composer, persisted): off by default. When on, fresh
  replies are spoken; history restores never auto-speak (an `expectReply`
  flag armed only by Send). Tapping mic stops speech so the mic doesn't
  transcribe our own TTS.
- **Per-reply speaker button** (in the copy/regenerate row): replays any
  reply on demand — also the retry path when voices loaded late, and the
  only path on Safari iOS (which drops non-gesture `speak()` calls).
- **Agentic actions (Phase 4) unchanged:** voice turns travel the identical
  send path and render the identical Confirm/Cancel cards; autonomy modes
  apply equally. Voice cannot bypass confirmation.
- TTS input is markdown-stripped and chunked (~180 chars on sentence
  boundaries incl. `።`/`፣`) to dodge Chrome's long-utterance cutoff.
  Reduced-motion users get no pulse animations; the status line is
  `aria-live="polite"`.

## Browser / locale matrix (expected)

| Browser | EN STT | AM (`am-ET`) STT | EN TTS | AM TTS |
|---|---|---|---|---|
| Chrome desktop (HTTPS) | ✅ | ✅ (Google recognizer) | ✅ | ✅ iff OS am voice pack installed, else text-only + note |
| Chrome Android + Google TTS am pack | ✅ | ✅ | ✅ | ✅ |
| Edge Chromium Win11 | ❌ (no STT impl.) → typed fallback msg | ❌ → Amharic-typing msg | ✅ | ✅ (`Mekdes`/`Ameha` natural voices) |
| Safari macOS/iOS | ✅ (14.1+/14.5+) | ⚠️ inconsistent → fallback msg if rejected | ✅ | ⚠️ OS-dependent; text-only + note if absent; replay tap required on iOS |
| Firefox desktop | ❌ (disabled by default) → fallback msg | ❌ → fallback msg | ✅ (desktop) | ⚠️ OS-dependent; text-only + note if absent |

## Manual end-to-end test (Chrome, the reference browser)

1. Serve over HTTPS or `localhost` (mic requires a secure context).
2. Open the app, open the assistant, set voice lang to **EN**, tap mic,
   allow the mic, say *"show today's orders"* → text appears → press Send →
   reply arrives, action cards (if any) still need Confirm.
3. Toggle **read-aloud ON** → next English reply is spoken; toggle OFF
   mid-speech → speech stops.
4. Set voice lang to **አማ**, allow mic, speak Amharic → Ge'ez transcript
   lands in the composer → Send → Amharic reply (server-side Amharic voice
   unchanged). If an `am-ET` voice pack is installed the reply is spoken;
   without one, the reply shows the text-only note and stays silent.
5. In a browser without STT (Firefox default): mic is disabled with the
   honest tooltip; typing in Amharic still gets full Amharic replies.
6. Confirm a pending write action proposed from a **voice** turn — the
   Confirm/Cancel card behaves exactly as for typed turns.

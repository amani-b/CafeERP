/* ============================================================
   Cafe ERP — Phase 8: assistant voice mode (EN + AM, free tier)
   ------------------------------------------------------------
   100% client-side Web Speech API. Zero backend cost, zero paid
   STT/TTS service, no audio ever leaves the browser except to the
   browser vendor's own speech service (Chrome's network recognizer).

   Two halves:
     STT  SpeechRecognition (or webkit prefix)  -> fills composer
     TTS  speechSynthesis                        -> reads replies

   Honest-fallback contract (the whole point of this file):
   - Amharic support is detected at RUNTIME, never assumed.
   - If am-ET recognition fails -> inline message, typed Amharic
     still fully works (server-side Amharic handling is untouched).
   - If no Amharic synthesis voice exists -> that reply stays
     TEXT-ONLY with a brief note. We NEVER read Ge'ez script or
     transliterated Amharic aloud in an English voice.
   - Voice NEVER bypasses the Phase 4 confirmation UX: dictation
     only fills the composer; the user still presses Send and still
     presses Confirm/Cancel on action cards like any typed turn.

   Considered and REJECTED (documented in docs/VOICE_MODE.md):
   client-side Whisper via Transformers.js — 50MB+ model download
   for a `tiny` model whose Amharic quality is worse than Chrome's
   native am-ET recognizer where that exists, and dead weight where
   it doesn't. Native-first + honest fallback wins on every axis.
   ============================================================ */
(function () {
    'use strict';

    var widget = document.getElementById('assistant-chat-widget');
    if (!widget) return; // widget not rendered on this page

    var inputEl = document.getElementById('assistant-chat-input');
    var sendBtn = document.getElementById('assistant-chat-send');
    var micBtn = document.getElementById('assistant-chat-mic');
    var langSelect = document.getElementById('assistant-chat-voice-lang');
    var ttsToggle = document.getElementById('assistant-chat-tts-toggle');
    var statusEl = document.getElementById('assistant-chat-voice-status');
    var messagesContainer = document.getElementById('assistant-chat-messages');
    if (!inputEl || !sendBtn || !micBtn || !langSelect || !ttsToggle || !messagesContainer) return;

    var AMHARIC_STT_LANG = 'am-ET';
    var ENGLISH_STT_LANG = 'en-US';
    var LS_LANG = 'cafeerp.voice.lang';
    var LS_READ_ALOUD = 'cafeerp.voice.readAloud';

    var SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;
    var hasSTT = !!SpeechRec;
    var hasTTS = ('speechSynthesis' in window) && !!window.speechSynthesis;

    var recognition = null;
    var listening = false;
    var baseText = '';      // composer content before this dictation session
    var expectReply = false; // set on send; next fresh assistant msg may speak

    // ------------------------- tiny helpers -------------------------

    function showStatus(msg) {
        if (!statusEl) return;
        if (!msg) {
            statusEl.hidden = true;
            statusEl.textContent = '';
            return;
        }
        statusEl.hidden = false;
        statusEl.textContent = msg;
    }

    function autoGrow() {
        inputEl.style.height = 'auto';
        inputEl.style.height = Math.min(inputEl.scrollHeight, 110) + 'px';
    }

    function safeGet(key) {
        try { return window.localStorage.getItem(key); } catch (e) { return null; }
    }
    function safeSet(key, val) {
        try { window.localStorage.setItem(key, val); } catch (e) { /* private mode */ }
    }

    // ------------------------- language -------------------------

    // Compact client-side Amharic detector (mirrors the server-side
    // AmharicLanguageSupport for TTS routing only — the server remains
    // the authority for reply language). Ge'ez regex + strong-signal
    // transliteration lexicon.
    var GEEZ_RE = /[ሀ-፿ᎀ-᎟ⶀ-⷟꬀-꬯]/;
    var TRANSLITERATED_HITS = (
        ' selam selem selmat tenesa dehna dehine dehinet endet endit ' +
        ' negn nesh nachew ishi eshi eshe eko betam abro konjo bunna buna ' +
        ' injera enjera berbere tella tej shai waga minale menale demoz ' +
        ' demewez amesegnalehu ameseginalehu ebakwo ebakh ebaksh lirdawo ' +
        ' yichalal aychal aydelm zare nege tilant tinish mindin sint sinte ' +
        ' huneta mereja dereswal kutir malet yete tirf ale minalew tizaz '
    );

    function containsGeez(text) {
        return GEEZ_RE.test(text || '');
    }

    function looksTransliteratedAmharic(text) {
        var words = String(text || '').toLowerCase().split(/[^a-z]+/);
        for (var i = 0; i < words.length; i++) {
            if (words[i].length > 1 && TRANSLITERATED_HITS.indexOf(' ' + words[i] + ' ') !== -1) {
                return true;
            }
        }
        return false;
    }

    function isAmharicText(text) {
        return containsGeez(text) || looksTransliteratedAmharic(text);
    }

    function sttLangForSetting(setting) {
        if (setting === 'am') return AMHARIC_STT_LANG;
        if (setting === 'en') return ENGLISH_STT_LANG;
        // Auto: follow the browser UI locale — an am- locale user almost
        // certainly wants am-ET; everyone else gets en-US. Explicit
        // override is one tap away in the select.
        var nav = (navigator.language || 'en').toLowerCase();
        return nav.indexOf('am') === 0 ? AMHARIC_STT_LANG : ENGLISH_STT_LANG;
    }

    // ------------------------- STT (input) -------------------------

    function setMicUI(on) {
        listening = on;
        micBtn.classList.toggle('listening', on);
        micBtn.setAttribute('aria-pressed', String(on));
        micBtn.setAttribute('aria-label', on ? 'Stop voice input' : 'Start voice input');
        micBtn.title = on ? 'Stop listening' : 'Start voice input';
        inputEl.placeholder = on ? 'Listening… speak now (tap mic to stop)' : 'Ask a question...';
    }

    function stopRecognition() {
        if (recognition) {
            try { recognition.stop(); } catch (e) { /* already ended */ }
        }
    }

    function startListening() {
        if (!hasSTT) {
            showStatus('Voice input isn\u2019t supported in this browser yet — try Chrome. You can still type.');
            return;
        }
        if (listening) {
            stopRecognition();
            return;
        }
        var lang = sttLangForSetting(langSelect.value);
        try {
            recognition = new SpeechRec();
        } catch (e) {
            showStatus('Voice input isn\u2019t supported in this browser yet — you can still type.');
            return;
        }
        recognition.lang = lang;
        recognition.interimResults = true;
        recognition.continuous = false; // one utterance per tap; predictable + mobile-safe
        recognition.maxAlternatives = 1;
        baseText = inputEl.value.trim();

        var gotFinal = false;

        recognition.onresult = function (event) {
            var interim = '';
            var finalText = '';
            for (var i = event.resultIndex; i < event.results.length; i++) {
                var res = event.results[i];
                if (res.isFinal) finalText += res[0].transcript;
                else interim += res[0].transcript;
            }
            var combined = (baseText ? baseText + ' ' : '') + (finalText || interim);
            inputEl.value = combined.trimStart();
            autoGrow();
            if (finalText) {
                gotFinal = true;
                baseText = inputEl.value.trim(); // chain further finals in this session
            }
        };

        recognition.onerror = function (event) {
            var err = (event && event.error) || '';
            setMicUI(false);
            recognition = null;
            if (err === 'not-allowed' || err === 'service-not-allowed') {
                showStatus('Microphone blocked — allow mic access in the browser address bar, or keep typing.');
            } else if (err === 'network') {
                showStatus('Speech service unreachable (it needs internet) — you can still type.');
            } else if (err === 'language-not-supported') {
                if (lang === AMHARIC_STT_LANG) {
                    showStatus('Voice input for Amharic isn\u2019t supported in this browser yet — you can still type in Amharic.');
                } else {
                    showStatus('This speech language isn\u2019t supported in this browser yet — you can still type.');
                }
            } else if (err === 'no-speech') {
                if (!gotFinal && !inputEl.value.trim()) {
                    showStatus('Didn\u2019t catch that — try again, or type instead.');
                }
            } else if (err === 'aborted') {
                // User-stopped or superseded: not an error worth announcing.
            } else {
                showStatus('Voice input hiccup — you can still type.');
            }
        };

        recognition.onend = function () {
            var wasListening = listening;
            setMicUI(false);
            recognition = null;
            if (wasListening) {
                inputEl.focus();
                // Keep any error/status text; otherwise confirm what happened.
                if (!statusEl || statusEl.hidden) {
                    if (inputEl.value.trim() && inputEl.value.trim() !== baseText) {
                        showStatus('Dictated — review, then press Send.');
                        setTimeout(function () {
                            // Auto-clear the transient confirmation only if the
                            // user hasn't hit a real error since.
                            if (statusEl && statusEl.textContent.indexOf('Dictated') === 0) showStatus(null);
                        }, 4000);
                    }
                }
            }
        };

        try {
            showStatus(null);
            recognition.start();
            setMicUI(true);
        } catch (e) {
            setMicUI(false);
            recognition = null;
            showStatus('Voice input couldn\u2019t start here — you can still type.');
        }
    }

    // ------------------------- TTS (output) -------------------------

    var amVoiceCache = null;
    var voicesLoaded = false;

    function allVoices() {
        if (!hasTTS) return [];
        try { return window.speechSynthesis.getVoices() || []; } catch (e) { return []; }
    }

    function findAmharicVoice(voices) {
        for (var i = 0; i < voices.length; i++) {
            var v = voices[i] || {};
            var lang = String(v.lang || '').toLowerCase();
            var name = String(v.name || '').toLowerCase();
            if (lang.indexOf('am') === 0 ||
                name.indexOf('amhar') !== -1 ||
                name.indexOf('mekdes') !== -1 ||
                name.indexOf('ameha') !== -1 ||
                name.indexOf('ethiop') !== -1) {
                return v;
            }
        }
        return null;
    }

    function refreshVoices() {
        var voices = allVoices();
        voicesLoaded = voices.length > 0;
        amVoiceCache = findAmharicVoice(voices);
        // Reflect capability on the toggle so absence is visible, not silent.
        if (!hasTTS) {
            ttsToggle.disabled = true;
            ttsToggle.title = 'Read-aloud isn\u2019t supported in this browser';
        } else {
            ttsToggle.disabled = false;
            updateTtsToggleTitle();
        }
    }

    function isReadAloudOn() {
        return ttsToggle.getAttribute('aria-pressed') === 'true';
    }

    function updateTtsToggleTitle() {
        if (!hasTTS) return;
        if (!amVoiceCache) {
            ttsToggle.title = isReadAloudOn()
                ? 'Read aloud: ON (English only — no Amharic voice found on this device)'
                : 'Read aloud: OFF (no Amharic voice found on this device — English replies can still be read)';
        } else {
            ttsToggle.title = isReadAloudOn() ? 'Read aloud: ON' : 'Read aloud: OFF';
        }
    }

    function setReadAloud(on) {
        ttsToggle.setAttribute('aria-pressed', String(on));
        ttsToggle.classList.toggle('on', on);
        safeSet(LS_READ_ALOUD, on ? '1' : '0');
        updateTtsToggleTitle();
        if (!on) stopSpeaking();
        else if (!hasTTS) {
            showStatus('Read-aloud isn\u2019t supported in this browser — replies stay text-only.');
        }
    }

    function stopSpeaking() {
        if (!hasTTS) return;
        try { window.speechSynthesis.cancel(); } catch (e) { /* noop */ }
        var playing = messagesContainer.querySelectorAll('.msg-speak.playing');
        for (var i = 0; i < playing.length; i++) playing[i].classList.remove('playing');
    }

    // Strip markdown/formatting so the voice reads words, not syntax.
    function toSpeechText(markdown) {
        var t = String(markdown || '');
        t = t.replace(/```[\s\S]*?```/g, ' ');          // fenced code blocks
        t = t.replace(/`([^`]*)`/g, '$1');              // inline code
        t = t.replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1'); // images -> alt
        t = t.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1');  // links -> label
        t = t.replace(/^#{1,6}\s+/gm, '');              // headings
        t = t.replace(/[*_~]{1,3}(\S[^*_~]*\S|[^*_~])/g, '$1'); // emphasis
        t = t.replace(/^\s*[-*+]\s+/gm, '');            // bullets
        t = t.replace(/^\s*\d+[.)]\s+/gm, '');          // numbered lists
        t = t.replace(/\|/g, ' ');                      // tables
        t = t.replace(/[<>]/g, ' ');
        t = t.replace(/\s+/g, ' ').trim();
        // Cap: long replies read their head, not the whole thread.
        if (t.length > 600) t = t.slice(0, 600).replace(/\s+\S*$/, '') + '…';
        return t;
    }

    // Chrome cancels utterances past ~15s/200 chars: chunk on sentence
    // boundaries (incl. Ge'ez ፣ and ።) and queue the queue.
    function chunkForSpeech(text) {
        var parts = String(text).split(/(?<=[.!?።፧፣;:])\s+/u);
        var chunks = [];
        var cur = '';
        for (var i = 0; i < parts.length; i++) {
            var p = parts[i].trim();
            if (!p) continue;
            if ((cur + ' ' + p).trim().length > 180 && cur) {
                chunks.push(cur.trim());
                cur = p;
            } else {
                cur = (cur ? cur + ' ' : '') + p;
            }
        }
        if (cur.trim()) chunks.push(cur.trim());
        return chunks.length ? chunks : [text];
    }

    function speakText(text, opts) {
        opts = opts || {};
        if (!hasTTS || !isReadAloudOn()) return false;
        var plain = toSpeechText(text);
        if (!plain) return false;

        var amharic = isAmharicText(plain);
        var voice = null;
        var lang = ENGLISH_STT_LANG;
        if (amharic) {
            // THE honesty rule: no Amharic voice -> text only, never an
            // English voice mangling Ge'ez/transliterated Amharic.
            if (!amVoiceCache && voicesLoaded) {
                attachTextOnlyNote(opts.host, true);
                return false;
            }
            if (!amVoiceCache && !voicesLoaded) {
                refreshVoices(); // late voice list (Chrome async) — one retry
                if (!amVoiceCache) {
                    // Voices simply not in yet: stay silent rather than risk
                    // the wrong voice. The replay button remains available.
                    attachTextOnlyNote(opts.host, false);
                    return false;
                }
            }
            voice = amVoiceCache;
            lang = 'am-ET';
        }

        try {
            window.speechSynthesis.cancel();
            var chunks = chunkForSpeech(plain);
            for (var i = 0; i < chunks.length; i++) {
                var u = new SpeechSynthesisUtterance(chunks[i]);
                if (voice) u.voice = voice;
                u.lang = lang;
                u.rate = 1;
                if (i === chunks.length - 1 && opts.onend) u.onend = opts.onend;
                if (opts.onerror) u.onerror = opts.onerror;
                window.speechSynthesis.speak(u);
            }
            return true;
        } catch (e) {
            return false;
        }
    }

    function attachTextOnlyNote(host, definitive) {
        if (!host || host.querySelector('.assistant-voice-note')) return;
        var note = document.createElement('div');
        note.className = 'assistant-voice-note';
        note.textContent = definitive
            ? '\uD83D\uDD07 No Amharic voice found in this browser — showing text only. ' +
              'To hear Amharic aloud, install an Amharic (am-ET) voice in your OS language settings.'
            : '\uD83D\uDD07 Amharic voice not ready yet — showing text only. Tap the speaker icon on this reply to retry.';
        host.appendChild(note);
    }

    // ------------------------- per-message replay -------------------------

    var SPEAKER_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
        'stroke-linecap="round" stroke-linejoin="round"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon>' +
        '<path d="M15.54 8.46a5 5 0 0 1 0 7.07"></path></svg>';

    function ensureReplayButton(assistantRoot) {
        if (!hasTTS) return;
        var inner = assistantRoot.querySelector('.msg-actions-inner');
        if (!inner || inner.querySelector('.msg-speak')) return;
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'msg-action-btn msg-speak';
        btn.title = 'Read this reply aloud';
        btn.setAttribute('aria-label', 'Read this reply aloud');
        btn.innerHTML = SPEAKER_SVG;
        btn.addEventListener('click', function (e) {
            e.stopPropagation();
            var textEl = assistantRoot.querySelector('.msg-text');
            var text = textEl ? textEl.textContent : '';
            if (!text.trim()) return;
            if (!isReadAloudOn()) setReadAloud(true); // explicit tap opts in
            btn.classList.add('playing');
            var done = function () { btn.classList.remove('playing'); };
            var ok = speakText(text, { host: assistantRoot, onend: done, onerror: done });
            if (!ok) done();
            // Keep the pane pinned so the user sees which reply is speaking.
            if (messagesContainer.scrollHeight) {
                messagesContainer.scrollTop = messagesContainer.scrollHeight;
            }
        });
        inner.appendChild(btn);
    }

    // Auto-read fresh replies ONLY (never history loads): the flag is set
    // on send and consumed by the first new assistant node. History
    // restores (many nodes at once) never trigger speech.
    var observer = new MutationObserver(function (mutations) {
        mutations.forEach(function (m) {
            m.addedNodes.forEach(function (node) {
                if (!node.classList || !node.classList.contains('assistant-chat-msg')) return;
                if (node.getAttribute('data-role') !== 'assistant') return;
                ensureReplayButton(node);
                if (expectReply && isReadAloudOn()) {
                    expectReply = false;
                    var textEl = node.querySelector('.msg-text');
                    // The reveal animation streams textContent; wait for it to
                    // settle before speaking, else we'd read a prefix.
                    var tries = 0;
                    var iv = setInterval(function () {
                        tries++;
                        var stillRevealing = textEl && textEl.classList.contains('is-revealing');
                        if (!stillRevealing || tries > 40) {
                            clearInterval(iv);
                            if (textEl && textEl.textContent.trim()) {
                                speakText(textEl.textContent, { host: node });
                            }
                        }
                    }, 250);
                }
            });
        });
    });
    observer.observe(messagesContainer, { childList: true });

    // Backfill replay buttons for server-rendered history threads.
    function backfillReplay() {
        var nodes = messagesContainer.querySelectorAll('.assistant-chat-msg[data-role="assistant"]');
        for (var i = 0; i < nodes.length; i++) ensureReplayButton(nodes[i]);
    }

    // ------------------------- wiring -------------------------

    // Prefs first so controls render in the persisted state.
    var savedLang = safeGet(LS_LANG);
    if (savedLang === 'en' || savedLang === 'am' || savedLang === 'auto') {
        langSelect.value = savedLang;
    }
    langSelect.addEventListener('change', function () {
        safeSet(LS_LANG, langSelect.value);
        if (listening) {
            // Restart recognition so the new language applies immediately.
            stopRecognition();
            setTimeout(startListening, 350);
        }
    });

    setReadAloud(safeGet(LS_READ_ALOUD) === '1');
    refreshVoices();
    if (hasTTS) {
        if (typeof window.speechSynthesis.onvoiceschanged !== 'undefined') {
            window.speechSynthesis.onvoiceschanged = refreshVoices;
        }
        // Chrome populates voices lazily — poll once more shortly after load.
        setTimeout(refreshVoices, 1500);
    }

    if (!hasSTT) {
        micBtn.disabled = true;
        micBtn.classList.add('unsupported');
        micBtn.title = 'Voice input isn\u2019t supported in this browser — try Chrome. You can still type.';
    }

    micBtn.addEventListener('click', function () {
        if (micBtn.disabled) {
            showStatus('Voice input isn\u2019t supported in this browser yet — try Chrome. You can still type.');
            return;
        }
        // Tapping mic while the assistant speaks: stop output first so the
        // mic doesn't transcribe our own TTS.
        stopSpeaking();
        startListening();
    });

    ttsToggle.addEventListener('click', function () {
        setReadAloud(!isReadAloudOn());
        if (!isReadAloudOn()) {
            showStatus(null);
            return;
        }
        // No TTS at all: setReadAloud already left the honest
        // unsupported note — don't overwrite it with an ON promise.
        if (!hasTTS) return;
        showStatus(amVoiceCache || !voicesLoaded
            ? 'Read-aloud ON — replies will be spoken.'
            : 'Read-aloud ON — English replies will be spoken; Amharic replies stay text-only (no Amharic voice on this device).');
    });

    function armExpectReply() {
        // Only auto-speak when the user asked for read-aloud AND the send
        // actually carries text (guard against empty sends).
        if (isReadAloudOn() && inputEl.value.trim()) expectReply = true;
        stopSpeaking(); // never talk over the incoming reply
        if (listening) stopRecognition();
    }
    sendBtn.addEventListener('click', armExpectReply);
    inputEl.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey) armExpectReply();
    });

    // Escape stops dictation first (then falls through to chat.js handlers).
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' && listening) {
            stopRecognition();
        }
    }, true);

    backfillReplay();

    // Diagnostics hook for the docs' manual test plan (harmless in prod).
    window.AssistantVoice = {
        hasSTT: hasSTT,
        hasTTS: hasTTS,
        hasAmharicVoice: function () { refreshVoices(); return !!amVoiceCache; },
        voices: allVoices
    };
})();

package com.cafeerp.assistant;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Native Amharic voice support — detection plus inference-time prompt
 * engineering. Free-tier only: pure lexical logic plus system-prompt text, no
 * external service, no fine-tune, nothing that costs money or can be
 * rate-limited away.
 *
 * <p>Two halves:
 * <ol>
 *   <li><b>Detection</b> ({@link #detect}) — classifies an inbound message as
 *       Ge'ez script, Latin-transliterated Amharic, English, or a
 *       code-switched mix. Transliterated Amharic is a first-class input: the
 *       same language in a different keyboard, never a lesser one.</li>
 *   <li><b>Voice</b> ({@link #directiveFor}, {@link #boosterFor}) — the
  *       register/personality brief that makes the model answer like a sharp
  *       bilingual Ethiopian colleague instead of an English reply wearing
  *       Amharic words. Plus a small per-provider booster because the models
  *       behind the failover chain do not have the same out-of-the-box
  *       fluency.</li>
 * </ol>
 */
public final class AmharicLanguageSupport {

    private AmharicLanguageSupport() {
    }

    /** Which language/script the user is speaking. */
    public enum Script {
        /** Ge'ez script (U+1200 block and friends), e.g. ሰላም. */
        GEEZ,
        /** Amharic typed in Latin letters, e.g. "selam, dehna neh?". */
        TRANSLITERATED,
        /** Plain English. */
        ENGLISH,
        /** Code-switched: Amharic + English (either script) in one message. */
        MIXED
    }

    // ---------------------------------------------------------------
    //  Detection
    // ---------------------------------------------------------------

    /** Matches any Ethiopic code point (main block + supplement + extended). */
    static final Pattern GEEZ_PATTERN =
            Pattern.compile("[\\u1200-\\u137F\\u1380-\\u139F\\u2D80-\\u2DDF\\uAB00-\\uAB2F]");

    /**
     * High-signal transliterated tokens: essentially unambiguous Amharic
     * outside Amharic context (weight 2 each — ONE is enough to claim a
     * short message like "selam" or "dehna neh?").
     */
    static final Set<String> STRONG_SIGNALS = Set.of(
            "selam", "selem", "selmat", "tenesa", "tenesawoch",
            "dehna", "dehine", "dehinet",
            "endet", "endit", "endeh", "endetne", "endetnesh", "endetneh",
            "negn", "nesh", "nachew", "nachu", "nachehu", "nachehe",
            "ishi", "eshi", "eshe", "ishii", "eshii",
            "eko", "betam", "abro", "konjo",
            "bunna", "buna", "injera", "enjera", "berbere", "berberew",
            "tella", "tej", "tejbet", "shai", "chai",
            "waga", "minale", "menale", "kushina",
            "shyach", "shiyach", "tirf", "demoz", "demewez",
            "amesegnalehu", "ameseginalehu", "ebakwo", "ebakh", "ebaksh",
            "lirdawo", "lirdaw", "min", "man",
            "yichalal", "aychalm", "aychal", "aydelem",
            "zare", "zarey", "nege", "tilant", "tinish",
            "bet", "betu", "wede", "keza", "keze", "hulum", "hulunem",
            // ERP question words (transliterated): what / how-much / there-is /
            // status / information / arrived / number / meaning.
            "mindin", "sint", "sinte", "huneta", "mereja", "dereswal",
            "kutir", "malet", "yete");

    /**
     * Contextual strong tokens: genuine Amharic words that ALSO occur as
     * ordinary English words, so they only count beside other Amharic company.
     * <ul>
     *   <li>"min"/"man"/"yet" — English words AND Amharic question words.</li>
     *   <li>"ale" (አለ, "there is": "buna ale?" = "is there coffee?") — but
     *       also English "ale" ("ginger ale"), which must stay English.</li>
     * </ul>
     */
    static final Set<String> CONTEXTUAL_STRONG = Set.of("min", "man", "yet", "ale");

    /**
     * Amharic nominal suffixes for stem lookup: "minalew" (the menu),
     * "tizazu" (the order), "selame" (vocative). Lookup tries the bare token
     * first, then one suffix stripped — a stem only ever counts when it is a
     * known signal, so English words are unaffected ("menu"→"men" matches
     * nothing, "new"→"ne" is weak-only, never decisive alone).
     */
    static final List<String> AMHARIC_SUFFIXES = List.of(
            "woch", "achew", "achen", "och", "ew", "ye", "un", "u", "w", "n", "e");

    /**
     * Low-signal tokens: real Amharic function words/particles that ALSO occur
     * as ordinary English words ("no", "be", "he", "me", "ye", "a", "at"-like
     * clitics). Weight 1 each and NEVER decisive alone — they only count when
     * at least one other signal (strong or weak) is present, or a Ge'ez
     * character anchors the message.
     */
    static final Set<String> WEAK_SIGNALS = Set.of(
            "neh", "no", "nat", "naw", "new", "nowe",
            "le", "be", "ke", "ye", "ne", "we", "ande",
            "eh", "ah", "oh", "ay", "gn", "hu", "esh");

    /**
     * High-signal English function words. ERP domain nouns (order, menu,
     * sales, stock, kitchen, price, inventory) are DELIBERATELY excluded —
     * they occur in both languages ("order 482 yet laye?") and must not drag
     * a transliterated message into ENGLISH.
     */
    static final Set<String> ENGLISH_SIGNALS = Set.of(
            "the", "is", "are", "was", "were", "been", "being",
            "what", "when", "where", "which", "how", "why", "whose",
            "you", "your", "yours", "please", "thanks", "thank",
            "there", "their", "they", "them", "this", "that", "these", "those",
            "with", "from", "have", "has", "had", "will", "would", "could",
            "should", "show", "give", "tell", "hello", "hi", "hey", "okay");

    /** Tokenizer: lowercase ASCII letters only; Ge'ez handled separately. */
    static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z]+");

    /**
     * Classify an inbound message. Pure function of the text — no I/O, no
     * model, works identically for every provider including the deterministic
     * fallback.
     */
    public static Script detect(String message) {
        if (message == null || message.isBlank()) {
            return Script.ENGLISH;
        }
        boolean geez = GEEZ_PATTERN.matcher(message).find();

        String lower = message.toLowerCase(Locale.ROOT);
        String[] raw = TOKEN_SPLIT.split(lower);
        Set<String> tokens = new HashSet<>();
        for (String t : raw) {
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }

        int strong = 0;
        for (String t : tokens) {
            if (STRONG_SIGNALS.contains(t) || stemMatchesStrong(t)) {
                strong++;
            }
        }
        int weakHits = 0;
        for (String t : tokens) {
            if (WEAK_SIGNALS.contains(t) && (strong > 0 || geez)) {
                weakHits++;
            }
        }
        // Contextual words (min/man/yet/ale) count only with Amharic company.
        int contextualWeak = 0;
        if (strong > 0 || geez) {
            for (String t : tokens) {
                if (CONTEXTUAL_STRONG.contains(t) || stemMatches(t, CONTEXTUAL_STRONG)) {
                    contextualWeak++;
                }
            }
        }
        int amharicScore = strong * 2 + weakHits + contextualWeak;

        int englishScore = 0;
        for (String t : tokens) {
            if (ENGLISH_SIGNALS.contains(t)) {
                englishScore++;
            }
        }
        // Contextual words as plain English ("yet another", "min wage"…) lean
        // English when there is no Amharic company.
        if (strong == 0 && !geez) {
            for (String demoted : CONTEXTUAL_STRONG) {
                if (tokens.contains(demoted)) {
                    englishScore++;
                }
            }
        }

        if (geez) {
            if (amharicScore > 0 && englishScore > 0) {
                return Script.MIXED;
            }
            if (englishScore > 0) {
                return Script.MIXED; // Ge'ez + English words in one message
            }
            return Script.GEEZ;
        }
        if (amharicScore > 0 && englishScore > 0) {
            return Script.MIXED;
        }
        if (strong >= 1 && englishScore == 0) {
            return Script.TRANSLITERATED;
        }
        return Script.ENGLISH;
    }

    /**
     * Strip one Amharic nominal suffix and test the stem against a lexicon.
     * Longest suffixes first ("woch" before "och" before bare "h"-forms).
     */
    static boolean stemMatches(String token, Set<String> lexicon) {
        if (token == null || token.length() < 5) {
            return false; // stem would be too short to trust
        }
        for (String suffix : AMHARIC_SUFFIXES) {
            if (token.endsWith(suffix) && token.length() - suffix.length() >= 3) {
                String stem = token.substring(0, token.length() - suffix.length());
                if (lexicon.contains(stem)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Stem-aware strong-signal test (exact match or one suffix stripped). */
    static boolean stemMatchesStrong(String token) {
        return stemMatches(token, STRONG_SIGNALS);
    }

    /** True for anything the Amharic voice should answer (incl. mixed). */
    public static boolean isAmharic(Script script) {
        return script == Script.GEEZ || script == Script.TRANSLITERATED || script == Script.MIXED;
    }

    /** True when the message contains Ge'ez characters. */
    public static boolean containsGeez(String message) {
        return message != null && GEEZ_PATTERN.matcher(message).find();
    }

    // ---------------------------------------------------------------
    //  Voice — the native-speaker brief (shared by every provider)
    // ---------------------------------------------------------------

    /**
     * The register/personality brief appended to the system prompt whenever
     * the user's turn is Amharic (any script). Written as behavioural
     * instruction with concrete in-language examples — NOT "respond in
     * Amharic" alone, which is what produced the old calque-translated tone.
     */
    static final String AMHARIC_VOICE = """
            Amharic voice — this is a native voice, NOT a translation layer. Never compose the reply \
            in English first and convert it; think and answer directly in the user's Amharic. What that \
            means in practice:
            - Register: a warm, sharp Ethiopian colleague on a work chat — direct, competent, never \
            stiff or textbook-formal, never corporate-robotic. Short sentences, natural rhythm, the \
            connective tissue a real person uses (እንደዚያ, ስለዚህ, ግን, ደግሞ, ከዚያ / in Latin script: \
            endet, silezih, gin, degmo, kezia).
            - Particles and softeners carry the tone — use them the way speech does: እኮ / eko for \
            shared-context nudges ("that thing we both know"), እሺ / ishi-eshi to acknowledge, በጣም / \
            betam for emphasis, በእርግጥ / be'ergit for genuine agreement, እባክዎ / ebakwo (polite) or \
            እባክህ/እባክሽ / ebakh-ebaksh (familiar) to soften requests — matched to how formal the user \
            sounds, exactly as you would calibrate politeness in English.
            - Honorifics: default to polite-neutral (the እርስዎ / irswo verb forms) with anyone who sounds \
            like a manager or a stranger; mirror casual forms only when the user is clearly casual. When \
            unsure, polite wins.
            - ANTI-CALQUE rule (most important): never render English idioms or sentence frames \
            word-for-word. "How can I help you?" is እንዴት ልርዳዎት? / endet lirdawo? — never a literal \
            assembly of "how + can + I + help". "Let me check that for you" is አሁን አጣርቼ እነግርዎታለሁ / \
            ahun ataricho enegrwo-talehu in spirit — say what a person would say, not what the English \
            says. If a sentence would sound odd read aloud to an Addis colleague, rewrite it.
            - ERP facts (order numbers, item names, prices, statuses like PENDING/READY, tool results) \
            stay EXACTLY as returned — never transliterate or translate data, never invent Amharic \
            spellings of menu items. Explain AROUND the data in native Amharic; leave the data itself untouched.
            - Personality parity: the English assistant is friendly and direct. The Amharic reply must \
            feel equally alive — if it reads more formal or more robotic than the English equivalent \
            would, that is a bug: loosen up, shorten the sentences, talk like a person.
            - Never drop back into English mid-reply while the user is in Amharic mode. The ONLY English \
            allowed inside an Amharic reply is verbatim ERP data (names, numbers, statuses, URLs).""";

    /** Script-matching rule: which script the reply itself must be written in. */
    static final String SCRIPT_RULE_GEEZ =
            "Script rule: the user wrote in Ge'ez script — reply ENTIRELY in Ge'ez script ( fidel ). "
            + "No Latin-script Amharic, no English sentences. ERP data stays verbatim as returned.";

    static final String SCRIPT_RULE_TRANSLITERATED =
            "Script rule: the user wrote Amharic in Latin letters (transliterated Amharic — their keyboard, "
            + "not a lesser language) — reply ENTIRELY in the same Latin-transliterated Amharic, e.g. "
            + "\"selam! dehna negn, endet lirdawo?\" style, with the same warmth and particles "
            + "(eko, ishi, betam, ebakh/ebaksh, gin, degmo) spelled naturally. Never answer them in Ge'ez "
            + "script unless they ask, and never in English. ERP data stays verbatim as returned.";

    static final String SCRIPT_RULE_MIXED =
            "Script rule: the user code-switched (Amharic + English, possibly both scripts) — mirror them "
            + "naturally: lead in the script they led in, keep each language's spans in that language's own "
            + "script, and let the blend feel conversational rather than forcing everything into one "
            + "language. ERP data stays verbatim as returned.";

    /**
     * Full language directive for one user turn: empty for pure English (the
     * English path is byte-for-byte untouched), voice + script rule otherwise.
     */
    public static String directiveFor(String userMessage) {
        Script script = detect(userMessage);
        return switch (script) {
            case ENGLISH -> "";
            case GEEZ -> AMHARIC_VOICE + "\n" + SCRIPT_RULE_GEEZ;
            case TRANSLITERATED -> AMHARIC_VOICE + "\n" + SCRIPT_RULE_TRANSLITERATED;
            case MIXED -> AMHARIC_VOICE + "\n" + SCRIPT_RULE_MIXED;
        };
    }

    // ---------------------------------------------------------------
    //  Per-provider boosters (free-tier fluency gap closers)
    // ---------------------------------------------------------------

    /**
     * Extra few lines tuned per provider, appended AFTER the shared voice
     * brief. The primary chat model is the strongest multilingual of the three
     * so it needs only a light touch; the secondary model needs an explicit
     * don't-explain-don't-romanize guard; the tertiary model is the weakest,
     * so it gets anchored few-shot pairs plus a short-answer scaffold. All
     * plain prompt text — free at inference time.
     *
     * @param providerName the configured provider name; unknown names get the
     *                     medium-strength guard.
     */
    public static String boosterFor(String providerName, Script script) {
        if (script == Script.ENGLISH) {
            return "";
        }
        String name = providerName == null ? "" : providerName.toLowerCase(Locale.ROOT);
        if (name.contains("groq")) {
            return "Fluency note: you are fully fluent — trust the Amharic voice above and answer "
                    + "without hedging or meta-commentary about language. No preamble about "
                    + "translating; just answer.";
        }
        if (name.contains("gemini")) {
            return "Fluency note (follow strictly): answer DIRECTLY in the user's script — do NOT add "
                    + "an English version, English explanation, or English summary alongside it. Do NOT "
                    + "romanize Ge'ez into Latin and do NOT convert Latin Amharic into Ge'ez. One reply, "
                    + "one script, no language meta-talk.";
        }
        if (name.contains("openrouter")) {
            return "Fluency note (follow strictly): stay in the user's script for the WHOLE reply — even if "
                    + "unsure of a word, paraphrase inside Amharic; never fall back to English sentences. Keep "
                    + "it SHORT: answer first in 1-3 short sentences, at most one brief follow-up question. "
                    + "Tone anchors (match this feel, do not copy blindly): Ge'ez user "
                    + "\"ሰላም, ትዕዛዝ 482 የት ደርሷል?\" → acknowledge warmly, give the tool status, one short "
                    + "next step. Latin user \"selam, order 482 yet laye?\" → the same warmth in Latin "
                    + "script (\"selam! ahun ayto enegrwo-talehu\" in spirit). Thanks (\"አመሰግናለሁ!\" / "
                    + "\"amesegnalehu!\") → accept warmly and briefly, no lecture.";
        }
        // Unknown / future provider: medium-strength guard.
        return "Fluency note: answer directly in the user's script with no English alongside it, and keep "
                + "the reply short and conversational.";
    }

    // ---------------------------------------------------------------
    //  Titling language hint
    // ---------------------------------------------------------------

    /**
     * Language instruction for sidebar-title generation, so Amharic threads
     * get native idiomatic titles in the user's own script — never a stiff
     * translation of an English title.
     */
    public static String titleHintFor(String firstUserMessage) {
        Script script = detect(firstUserMessage);
        return switch (script) {
            case GEEZ -> " The conversation is in Amharic (Ge'ez script): the title itself must be "
                    + "native, idiomatic Amharic in Ge'ez script (3 to 6 words) — never an English title, "
                    + "never a word-for-word translation of one.";
            case TRANSLITERATED -> " The conversation is in Latin-transliterated Amharic: the title itself "
                    + "must be native, idiomatic Amharic in the same Latin script (3 to 6 words) — never an "
                    + "English title, never a translation of one.";
            case MIXED -> " The conversation mixes Amharic and English: title it the way the user led — "
                    + "Amharic words in the user's own script where they led in Amharic (3 to 6 words), "
                    + "never a stiff translation.";
            case ENGLISH -> "";
        };
    }
}

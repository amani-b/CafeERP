package com.cafeerp.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cafeerp.assistant.AmharicLanguageSupport.Script;
import com.cafeerp.assistant.AssistantService.AssistantReply;
import com.cafeerp.inventory.InventoryService;
import com.cafeerp.menu.MenuService;
import com.cafeerp.order.OrderService;
import com.cafeerp.report.ReportService;
import com.cafeerp.user.Role;

/**
 * Native Amharic voice: script/register detection, per-provider voice
 * boosters, Amharic-aware deterministic fallback and access-gate denials.
 */
@ExtendWith(MockitoExtension.class)
class AmharicLanguageSupportTest {

    @Mock
    private OrderService orderService;
    @Mock
    private MenuService menuService;
    @Mock
    private ReportService reportService;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private AssistantToolRegistry toolRegistry;

    // ---------------------------------------------------------------
    //  Detection — Ge'ez
    // ---------------------------------------------------------------

    @Test
    void detect_geezGreeting_isGeez() {
        assertEquals(Script.GEEZ, AmharicLanguageSupport.detect("ሰላም, ደህና ነህ?"));
    }

    @Test
    void detect_geezErpQuestion_isGeez() {
        assertEquals(Script.GEEZ, AmharicLanguageSupport.detect("ትዕዛዝ 482 የት ደርሷል?"));
    }

    @Test
    void detect_geezPlusEnglish_isMixed() {
        assertEquals(Script.MIXED,
                AmharicLanguageSupport.detect("ሰላም, what's the status of order 5?"));
    }

    // ---------------------------------------------------------------
    //  Detection — transliterated Amharic (must NOT read as English)
    // ---------------------------------------------------------------

    @Test
    void detect_transliteratedGreeting_isTransliterated() {
        assertEquals(Script.TRANSLITERATED,
                AmharicLanguageSupport.detect("selam, dehna neh?"));
    }

    @Test
    void detect_singleSelam_isTransliterated() {
        assertEquals(Script.TRANSLITERATED, AmharicLanguageSupport.detect("selam"));
    }

    @Test
    void detect_transliteratedSmallTalk_isTransliterated() {
        assertEquals(Script.TRANSLITERATED, AmharicLanguageSupport.detect("dehna negn, ante endet neh?"));
    }

    @Test
    void detect_transliteratedErpQuery_isTransliterated() {
        // "order" is a domain loanword, not an English sentence — the reply
        // must stay in Latin Amharic.
        assertEquals(Script.TRANSLITERATED,
                AmharicLanguageSupport.detect("selam, order 482 yet laye?"));
    }

    @Test
    void detect_transliteratedThanks_isTransliterated() {
        assertEquals(Script.TRANSLITERATED, AmharicLanguageSupport.detect("amesegnalehu!"));
    }

    @Test
    void detect_suffixedMenuQuestion_isTransliterated() {
        // Iteration fix: suffixed definitives ("minalew" = the menu) must not
        // fall through to English.
        assertEquals(Script.TRANSLITERATED,
                AmharicLanguageSupport.detect("minalew mindin new?"));
    }

    @Test
    void detect_bunaAle_isTransliterated() {
        assertEquals(Script.TRANSLITERATED, AmharicLanguageSupport.detect("buna ale?"));
    }

    @Test
    void detect_gingerAle_staysEnglish() {
        // "ale" is also an English word — without Amharic company it leans English.
        assertEquals(Script.ENGLISH, AmharicLanguageSupport.detect("ginger ale"));
    }

    @Test
    void detect_transliteratedMixedWithEnglish_isMixed() {
        assertEquals(Script.MIXED,
                AmharicLanguageSupport.detect("selam, what is the status of order 5?"));
    }

    // ---------------------------------------------------------------
    //  Detection — English and ambiguous traps
    // ---------------------------------------------------------------

    @Test
    void detect_plainEnglish_isEnglish() {
        assertEquals(Script.ENGLISH,
                AmharicLanguageSupport.detect("What's the status of order 5?"));
    }

    @Test
    void detect_yetAnotherOrder_isEnglish() {
        // "yet" is a real English word — without Amharic company it leans English.
        assertEquals(Script.ENGLISH, AmharicLanguageSupport.detect("yet another order please"));
    }

    @Test
    void detect_loneNo_isEnglish() {
        assertEquals(Script.ENGLISH, AmharicLanguageSupport.detect("no"));
    }

    @Test
    void detect_loneOk_isEnglish() {
        assertEquals(Script.ENGLISH, AmharicLanguageSupport.detect("ok"));
    }

    @Test
    void detect_blank_isEnglish() {
        assertEquals(Script.ENGLISH, AmharicLanguageSupport.detect("  "));
        assertEquals(Script.ENGLISH, AmharicLanguageSupport.detect(null));
    }

    // ---------------------------------------------------------------
    //  Voice directive + per-provider boosters
    // ---------------------------------------------------------------

    @Test
    void directiveFor_english_isEmpty() {
        assertTrue(AmharicLanguageSupport.directiveFor("What's the status of order 5?").isEmpty());
    }

    @Test
    void directiveFor_geez_namesGeezScript() {
        String directive = AmharicLanguageSupport.directiveFor("ሰላም, ደህና ነህ?");
        assertTrue(directive.contains("Ge'ez"), "must pin Ge'ez script: " + directive);
        assertTrue(directive.contains("ANTI-CALQUE"), "must carry the anti-calque rule");
    }

    @Test
    void directiveFor_transliterated_namesLatinScript() {
        String directive = AmharicLanguageSupport.directiveFor("selam, dehna neh?");
        assertTrue(directive.contains("Latin"), "must pin Latin script: " + directive);
        assertTrue(directive.contains("ANTI-CALQUE"), "must carry the anti-calque rule");
    }

    @Test
    void boosterFor_english_isEmptyForEveryProvider() {
        for (String provider : List.of("groq", "gemini", "openrouter", "unknown")) {
            assertTrue(AmharicLanguageSupport.boosterFor(provider, Script.ENGLISH).isEmpty(),
                    "English path must be untouched for " + provider);
        }
    }

    @Test
    void boosterFor_amharic_differsByProvider() {
        String groq = AmharicLanguageSupport.boosterFor("groq", Script.TRANSLITERATED);
        String gemini = AmharicLanguageSupport.boosterFor("gemini", Script.TRANSLITERATED);
        String openrouter = AmharicLanguageSupport.boosterFor("openrouter", Script.TRANSLITERATED);
        assertFalse(groq.isEmpty());
        assertFalse(gemini.isEmpty());
        assertFalse(openrouter.isEmpty());
        assertTrue(gemini.contains("DIRECTLY"), "gemini needs the no-English-alongside guard");
        assertTrue(openrouter.contains("SHORT"), "openrouter needs the short-answer scaffold");
    }

    @Test
    void titleHintFor_english_isEmpty() {
        assertTrue(AmharicLanguageSupport.titleHintFor("What's on the menu?").isEmpty());
    }

    @Test
    void titleHintFor_geez_requestsGeezTitle() {
        assertTrue(AmharicLanguageSupport.titleHintFor("ሰላም, ምናሌው ምንድን ነው?").contains("Ge'ez"));
    }

    @Test
    void titleHintFor_transliterated_requestsLatinTitle() {
        assertTrue(AmharicLanguageSupport.titleHintFor("selam, minalew mindin new?").contains("Latin"));
    }

    // ---------------------------------------------------------------
    //  Amharic order-id extraction (deterministic fast path)
    // ---------------------------------------------------------------

    @Test
    void extractOrderId_transliteratedTizaz() {
        Optional<Long> id = DeterministicFallbackHandler.extractOrderId("tizaz 482 yet laye?");
        assertEquals(Optional.of(482L), id);
    }

    @Test
    void extractOrderId_geezTizaz() {
        Optional<Long> id = DeterministicFallbackHandler.extractOrderId("ትዕዛዝ 482 የት ደርሷል?");
        assertEquals(Optional.of(482L), id);
    }

    @Test
    void extractOrderId_suffixedForms() {
        assertEquals(Optional.of(482L),
                DeterministicFallbackHandler.extractOrderId("tizazu 482 yet laye?"));
        assertEquals(Optional.of(482L),
                DeterministicFallbackHandler.extractOrderId("ትዕዛዙ 482 የት ደርሷል?"));
    }

    // ---------------------------------------------------------------
    //  Access guard — Amharic cannot sidestep the gate, denial is Amharic
    // ---------------------------------------------------------------

    @Test
    void guard_transliteratedSalesQuestion_deniedInLatin() {
        AssistantAccessGuard guard = new AssistantAccessGuard();
        var decision = guard.check(Role.STAFF, "ye-zare shyach sint new?");
        assertFalse(decision.allowed());
        assertTrue(decision.denialText().contains("yikerta"),
                "denial must be Latin Amharic, not English: " + decision.denialText());
    }

    @Test
    void guard_geezSalesQuestion_deniedInGeez() {
        AssistantAccessGuard guard = new AssistantAccessGuard();
        var decision = guard.check(Role.STAFF, "የዛሬ ሽያጭ ስንት ነው?");
        assertFalse(decision.allowed());
        assertTrue(AmharicLanguageSupport.containsGeez(decision.denialText()),
                "denial must be Ge'ez Amharic, not English: " + decision.denialText());
    }

    @Test
    void guard_transliteratedPayQuestion_denied() {
        AssistantAccessGuard guard = new AssistantAccessGuard();
        assertFalse(guard.check(Role.STAFF, "demoz sint new?").allowed());
    }

    @Test
    void guard_amharicSmallTalk_allowed() {
        AssistantAccessGuard guard = new AssistantAccessGuard();
        assertTrue(guard.check(Role.STAFF, "selam, dehna neh?").allowed());
        assertTrue(guard.check(Role.STAFF, "ሰላም, ደህና ነህ?").allowed());
    }

    // ---------------------------------------------------------------
    //  Deterministic fallback — honest Amharic degradation
    // ---------------------------------------------------------------

    private DeterministicFallbackHandler handlerWith(Set<String> tools) {
        lenient().when(toolRegistry.allowedToolNamesForRole(any())).thenReturn(tools);
        return new DeterministicFallbackHandler(orderService, menuService,
                reportService, inventoryService, toolRegistry);
    }

    @Test
    void unavailableMessage_transliterated_isLatinAmharic() {
        DeterministicFallbackHandler handler =
                handlerWith(Set.of("getOrderStatus", "getMenuItems"));
        AssistantReply reply = handler.unavailableMessage(Role.STAFF, "selam, dehna neh?");
        assertTrue(reply.text().contains("yikerta"),
                "fallback must not fail silent-English-only: " + reply.text());
        assertFalse(AmharicLanguageSupport.containsGeez(reply.text()));
    }

    @Test
    void unavailableMessage_geez_isGeezAmharic() {
        DeterministicFallbackHandler handler =
                handlerWith(Set.of("getOrderStatus", "getMenuItems"));
        AssistantReply reply = handler.unavailableMessage(Role.STAFF, "ሰላም, ደህና ነህ?");
        assertTrue(AmharicLanguageSupport.containsGeez(reply.text()),
                "fallback must be Ge'ez: " + reply.text());
    }

    @Test
    void unavailableMessage_english_staysEnglish() {
        DeterministicFallbackHandler handler =
                handlerWith(Set.of("getOrderStatus", "getMenuItems"));
        AssistantReply reply = handler.unavailableMessage(Role.STAFF, "hello?");
        assertTrue(reply.text().contains("temporarily unavailable"));
    }
}

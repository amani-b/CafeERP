package com.cafeerp.demo;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo-mode assistant budget readout for the chat UI.
 * <p>
 * Lets the widget tell visitors about their per-visit message allowance
 * <i>before</i> they hit it (the header shows "N of 15 messages left"),
 * instead of surprising them with the cap message afterwards. Active only
 * under the {@code demo} profile.
 */
@RestController
@Profile("demo")
@RequestMapping("/assistant")
public class DemoQuotaController {

    private final DemoAssistantQuota quota;

    public DemoQuotaController(DemoAssistantQuota quota) {
        this.quota = quota;
    }

    /** Current visitor session's allowance: total and remaining turns. */
    @GetMapping("/demo-quota")
    public Map<String, Integer> quota() {
        return Map.of(
                "limit", DemoAssistantQuota.MAX_MESSAGES_PER_SESSION,
                "remaining", quota.remaining());
    }
}

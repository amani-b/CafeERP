package com.cafeerp.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes demo-mode flags to every Thymeleaf view when the {@code demo}
 * profile is active (banner + demo credentials hint + nav adjustments).
 * Inactive in every other profile, so production templates are untouched.
 */
@ControllerAdvice
@Profile("demo")
public class DemoModeControllerAdvice {

    /** True in all views while the demo profile is active. */
    @ModelAttribute("demoMode")
    public boolean demoMode() {
        return true;
    }

    /** Login hint: the demo accounts and shared password. */
    @ModelAttribute("demoAccountsHint")
    public String demoAccountsHint() {
        return "Try demo-admin, demo-staff or demo-kitchen — password: "
                + DemoSeedData.DEMO_PASSWORD;
    }
}

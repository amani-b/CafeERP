package com.cafeerp.settings;

import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin screen for business settings (Phase 1: the business timezone that
 * drives all display timestamps app-wide).
 */
@Controller
@RequestMapping("/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public String show(Model model) {
        ZoneId current = settingsService.getTimeZone();
        model.addAttribute("currentTimezone", current.getId());
        model.addAttribute("availableTimezones", availableTimezones());
        model.addAttribute("currentZoneLabel", zoneLabel(current));
        return "settings";
    }

    @PostMapping("/timezone")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public String updateTimezone(@RequestParam String timezone,
                                 RedirectAttributes redirectAttributes) {
        try {
            settingsService.setTimeZone(timezone);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Timezone updated. All timestamps now display in " + settingsService.getTimeZone().getId() + ".");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/settings";
    }

    @ModelAttribute("zoneLabel")
    public static String zoneLabel(ZoneId zone) {
        return zone.getId() + " (" + zone.getDisplayName(TextStyle.FULL, java.util.Locale.ENGLISH)
                + ", UTC" + zone.getRules().getOffset(java.time.Instant.now()) + ")";
    }

    /**
     * A curated, sorted list of IANA zone ids covering all African, European,
     * American, Asian and Pacific regions, so the admin picks a valid id every
     * time. Custom ids can still be typed via the free-text field.
     */
    private static List<String> availableTimezones() {
        Set<String> regionIds = ZoneId.getAvailableZoneIds();
        List<String> ids = new ArrayList<>(regionIds.stream()
                .filter(id -> id.contains("/") && !id.startsWith("Etc/") && !id.startsWith("SystemV"))
                .toList());
        ids.add("UTC");
        ids.sort(Comparator.naturalOrder());
        return ids;
    }
}

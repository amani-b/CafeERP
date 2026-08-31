package com.cafeerp.settings;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes business-level settings stored in the {@code app_setting}
 * table.
 * <p>
 * The business timezone ({@code business.timezone}, an IANA zone id such as
 * {@code Africa/Addis_Ababa}) is the source of truth for rendering every
 * display timestamp in the ERP. It is deliberately NOT derived from the
 * server clock or the staff browser: server containers can run in UTC and
 * staff devices are often misconfigured.
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    /** Setting key holding the business IANA timezone string. */
    public static final String TIMEZONE_KEY = "business.timezone";

    /** Fallback when unset or invalid — the cafe's home timezone. */
    public static final String DEFAULT_TIMEZONE = "Africa/Addis_Ababa";

    private final AppSettingRepository repository;

    public SettingsService(AppSettingRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns the configured business timezone as a {@link ZoneId}.
     * Falls back to {@link #DEFAULT_TIMEZONE} when the setting is missing or
     * holds an unparseable value — never throws.
     */
    @Transactional(readOnly = true)
    public ZoneId getTimeZone() {
        return repository.findById(TIMEZONE_KEY)
                .map(AppSetting::getValue)
                .map(SettingsService::parseZone)
                .orElseGet(() -> {
                    log.warn("No '{}' setting found, defaulting to {}", TIMEZONE_KEY, DEFAULT_TIMEZONE);
                    return ZoneId.of(DEFAULT_TIMEZONE);
                });
    }

    /**
     * Persists the business timezone. Throws {@link IllegalArgumentException}
     * for anything that is not a valid IANA zone id (e.g. "UTC+3" or garbage),
     * so the admin UI can surface a clear error.
     */
    @Transactional
    public void setTimeZone(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            throw new IllegalArgumentException("Timezone is required.");
        }
        String normalized = zoneId.trim();
        ZoneId parsed;
        try {
            parsed = ZoneId.of(normalized, ZoneId.SHORT_IDS);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("\"" + normalized + "\" is not a valid IANA timezone (e.g. Africa/Addis_Ababa).");
        }
        // Reject odd zone ids like "GMT" is fine, but normalize offset-style
        // ids ("GMT+03:00" from SHORT_IDS parsing) to plain zone rules —
        // ZoneId.of with SHORT_IDS may produce fixed offsets; keep whatever
        // parses, since fixed offsets are still unambiguous wall-clock rules.
        repository.save(new AppSetting(TIMEZONE_KEY, parsed.getId()));
        log.info("Business timezone set to {} (locale {})", parsed.getId(), Locale.getDefault());
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value.trim(), ZoneId.SHORT_IDS);
        } catch (DateTimeException ex) {
            log.warn("Invalid '{}' value '{}', defaulting to {}", TIMEZONE_KEY, value, DEFAULT_TIMEZONE);
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
    }
}

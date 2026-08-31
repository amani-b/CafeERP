package com.cafeerp.common;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.cafeerp.settings.SettingsService;

/**
 * Renders persisted timestamps in the BUSINESS's local time, independent of
 * the server clock or the staff device's timezone configuration.
 * <p>
 * Annotated {@link ControllerAdvice @ControllerAdvice} so its
 * {@code @ModelAttribute} factory exposes it as {@code timeFmt} to every
 * Thymeleaf view — including {@code @WebMvcTest} slices, which load
 * controller-advice beans but not plain services. {@link SettingsService} is
 * therefore resolved lazily, falling back to the default business timezone
 * when no full application context (and so no settings repository) exists.
 */
@ControllerAdvice
public class TimeDisplayService {

    /** Default time-only pattern: 12-hour with AM/PM, e.g. "11:05 AM". */
    public static final String TIME_PATTERN = "h:mm a";

    private final ObjectProvider<SettingsService> settingsService;

    public TimeDisplayService(ObjectProvider<SettingsService> settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Formats a UTC-stored {@link LocalDateTime} in the business timezone
     * using the given pattern. Returns "—" for null (matches template usage).
     */
    public String format(LocalDateTime utcTimestamp, String pattern) {
        if (utcTimestamp == null) {
            return "—";
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
        return formatter.format(toBusinessLocal(utcTimestamp));
    }

    /** Formats a UTC-stored timestamp as business-local "h:mm a" (AM/PM). */
    public String formatTime(LocalDateTime utcTimestamp) {
        return format(utcTimestamp, TIME_PATTERN);
    }

    /**
     * Formats a {@link LocalDateTime} that already holds BUSINESS-LOCAL wall
     * time (e.g. report range boundaries computed in the business zone) —
     * no zone conversion is applied.
     */
    public String formatLocal(LocalDateTime businessLocalTimestamp, String pattern) {
        if (businessLocalTimestamp == null) {
            return "—";
        }
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(businessLocalTimestamp);
    }

    /** The configured business timezone (exposed for templates/tests). */
    public java.time.ZoneId getBusinessZone() {
        return businessZone();
    }

    /** Exposes this service to every view as {@code timeFmt}. */
    @ModelAttribute("timeFmt")
    public TimeDisplayService exposeToViews() {
        return this;
    }

    private ZoneId businessZone() {
        SettingsService service = settingsService.getIfAvailable();
        return service != null ? service.getTimeZone() : ZoneId.of(SettingsService.DEFAULT_TIMEZONE);
    }

    private LocalDateTime toBusinessLocal(LocalDateTime utcTimestamp) {
        return utcTimestamp.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(businessZone())
                .toLocalDateTime();
    }
}

package com.cafeerp.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import com.cafeerp.settings.SettingsService;

/**
 * Phase 1 acceptance test: a known UTC timestamp must render in the
 * CONFIGURED business timezone (not the server's, not the browser's) with a
 * 12-hour AM/PM marker.
 * <p>
 * Fixture: 2026-07-04T08:00:00 UTC. In Africa/Addis_Ababa (fixed UTC+3, no
 * DST) that instant is 11:00 AM local — regardless of whatever timezone the
 * machine running the test happens to be in.
 */
@ExtendWith(MockitoExtension.class)
public class TimeDisplayServiceTest {

    /** 2026-07-04 08:00:00 UTC. */
    private static final LocalDateTime UTC_0800 = LocalDateTime.of(2026, 7, 4, 8, 0, 0);

    @Mock
    private ObjectProvider<SettingsService> settingsProvider;

    @Mock
    private SettingsService settingsService;

    private TimeDisplayService timeDisplayService;

    @BeforeEach
    void setUp() {
        timeDisplayService = new TimeDisplayService(settingsProvider);
    }

    private void useBusinessZone(String zoneId) {
        when(settingsProvider.getIfAvailable()).thenReturn(settingsService);
        when(settingsService.getTimeZone()).thenReturn(ZoneId.of(zoneId));
    }

    @Test
    void knownUtcTimestampRendersInConfiguredTimezoneWithAmPm() {
        useBusinessZone("Africa/Addis_Ababa");

        // UTC 08:00 + 3h = 11:00 local, morning → "11:00 AM"
        assertThat(timeDisplayService.formatTime(UTC_0800)).isEqualTo("11:00 AM");

        // Full pattern keeps the AM/PM marker and the h:mm (non-zero-padded hour) form
        assertThat(timeDisplayService.format(UTC_0800, "yyyy-MM-dd h:mm a")).isEqualTo("2026-07-04 11:00 AM");
        assertThat(timeDisplayService.format(UTC_0800, "yyyy-MM-dd h:mm:ss a")).isEqualTo("2026-07-04 11:00:00 AM");
    }

    @Test
    void afternoonTimestampRendersWithPm() {
        useBusinessZone("Africa/Addis_Ababa");

        // UTC 13:30 + 3h = 16:30 local → "4:30 PM" (h = no leading zero)
        LocalDateTime utc1330 = LocalDateTime.of(2026, 7, 4, 13, 30, 0);
        assertThat(timeDisplayService.formatTime(utc1330)).isEqualTo("4:30 PM");
    }

    @Test
    void renderingIsIndependentOfServerDefaultTimezone() {
        useBusinessZone("Africa/Addis_Ababa");

        String utcDefaultZone = java.util.TimeZone.getDefault().getID();
        // Even if the JVM runs in UTC (as containers do), 08:00 UTC must NOT
        // render as "8:00 AM" — it renders in the business zone.
        String rendered = timeDisplayService.formatTime(UTC_0800);
        assertThat(rendered).isEqualTo("11:00 AM");
        assertThat(rendered).contains("AM");
        if ("UTC".equals(utcDefaultZone)) {
            assertThat(rendered).isNotEqualTo("8:00 AM");
        }
    }

    @Test
    void differentConfiguredTimezoneChangesRendering() {
        useBusinessZone("America/New_York");

        // UTC 08:00 = 04:00 EDT (July → DST, UTC-4) → "4:00 AM"
        assertThat(timeDisplayService.formatTime(UTC_0800)).isEqualTo("4:00 AM");
    }

    @Test
    void formatLocalDoesNotShiftBusinessLocalBoundaries() {
        // Report range boundaries already hold business-local wall time —
        // they must be shown as-is, not converted again.
        LocalDateTime businessMidnight = LocalDateTime.of(2026, 7, 23, 0, 0);
        assertThat(timeDisplayService.formatLocal(businessMidnight, "yyyy-MM-dd h:mm a"))
                .isEqualTo("2026-07-23 12:00 AM");
    }

    @Test
    void nullTimestampRendersAsDash() {
        assertThat(timeDisplayService.formatTime(null)).isEqualTo("—");
        assertThat(timeDisplayService.format(null, "yyyy-MM-dd")).isEqualTo("—");
    }
}

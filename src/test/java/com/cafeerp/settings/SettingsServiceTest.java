package com.cafeerp.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class SettingsServiceTest {

    @Mock
    private AppSettingRepository repository;

    @Test
    void returnsConfiguredTimezone() {
        when(repository.findById(SettingsService.TIMEZONE_KEY))
                .thenReturn(Optional.of(new AppSetting(SettingsService.TIMEZONE_KEY, "Africa/Addis_Ababa")));

        assertThat(new SettingsService(repository).getTimeZone()).isEqualTo(ZoneId.of("Africa/Addis_Ababa"));
    }

    @Test
    void fallsBackToDefaultWhenSettingMissing() {
        when(repository.findById(SettingsService.TIMEZONE_KEY)).thenReturn(Optional.empty());

        assertThat(new SettingsService(repository).getTimeZone())
                .isEqualTo(ZoneId.of(SettingsService.DEFAULT_TIMEZONE));
    }

    @Test
    void fallsBackToDefaultWhenSettingInvalid() {
        when(repository.findById(SettingsService.TIMEZONE_KEY))
                .thenReturn(Optional.of(new AppSetting(SettingsService.TIMEZONE_KEY, "Not/AZone")));

        assertThat(new SettingsService(repository).getTimeZone())
                .isEqualTo(ZoneId.of(SettingsService.DEFAULT_TIMEZONE));
    }

    @Test
    void rejectsInvalidTimezone() {
        SettingsService service = new SettingsService(repository);

        assertThatThrownBy(() -> service.setTimeZone("GMT+3ish"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setTimeZone("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void persistsValidTimezone() {
        SettingsService service = new SettingsService(repository);

        service.setTimeZone("Africa/Nairobi");

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getKey()).isEqualTo(SettingsService.TIMEZONE_KEY);
        assertThat(captor.getValue().getValue()).isEqualTo("Africa/Nairobi");
    }
}

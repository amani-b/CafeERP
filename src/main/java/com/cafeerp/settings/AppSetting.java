package com.cafeerp.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A single business-level setting, stored as a key/value pair.
 * <p>
 * Settings are admin-configurable at runtime (no redeploy needed) and act as
 * the source of truth for business-wide behavior — e.g. the timezone all
 * displayed timestamps are rendered in.
 */
@Entity
@Table(name = "app_setting")
public class AppSetting {

    @Id
    @Column(name = "\"key\"", length = 100)
    private String key;

    @Column(name = "\"value\"", nullable = false, length = 255)
    private String value;

    public AppSetting() {
    }

    public AppSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}

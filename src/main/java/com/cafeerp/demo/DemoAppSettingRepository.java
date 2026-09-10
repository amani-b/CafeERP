package com.cafeerp.demo;

import java.util.Map;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.web.context.annotation.SessionScope;

import com.cafeerp.settings.AppSetting;
import com.cafeerp.settings.AppSettingRepository;

/**
 * Demo-mode {@link AppSettingRepository}: session-scoped, seeded per visitor.
 * Replaces the JPA bean only when the {@code demo} profile is active.
 */
@Repository("demoAppSettingRepository")
@Profile("demo")
@Primary
@SessionScope
public class DemoAppSettingRepository extends InMemoryJpaRepository<AppSetting, String>
        implements AppSettingRepository {

    private final DemoSessionStore store;

    public DemoAppSettingRepository(DemoSessionStore store) {
        this.store = store;
    }

    @Override
    protected Map<String, AppSetting> store() {
        return store.settings();
    }

    @Override
    protected String idOf(AppSetting entity) {
        return entity.getKey();
    }

    @Override
    protected void setId(AppSetting entity, String id) {
        entity.setKey(id);
    }

    @Override
    protected String nextId() {
        throw new UnsupportedOperationException("AppSetting keys are natural keys in demo mode");
    }
}

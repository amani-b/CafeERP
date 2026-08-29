package com.cafeerp.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Build version stamp for deploy self-announcement.
 * <p>
 * {@code build-version.properties} is generated at BUILD time by Maven
 * resource filtering (project version + build timestamp). The value is:
 * <ol>
 *   <li>embedded into every page as a {@code <meta name="build-version">}
 *       tag (via the {@code buildVersion} model attribute), and</li>
 *   <li>exposed as {@code GET /build-version} (public, no auth — it leaks
 *       nothing but the version string).</li>
 * </ol>
 * The frontend polls the endpoint on an interval / on next navigation and,
 * when it differs from the meta tag, shows a small non-intrusive banner:
 * "Updates available — refresh to see the latest changes." It never
 * force-refreshes the user.
 */
@Configuration
@PropertySource("classpath:build-version.properties")
@RestController
@ControllerAdvice
public class BuildInfo {

    private final String version;

    public BuildInfo(@Value("${app.build.version:unknown}") String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }

    /** Server-reported build version, polled by the frontend. */
    @GetMapping("/build-version")
    public String buildVersion() {
        return version;
    }

    /** Makes the version available to all Thymeleaf templates. */
    @ModelAttribute("buildVersion")
    public String buildVersionAttribute() {
        return version;
    }
}
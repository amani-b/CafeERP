package com.cafeerp.demo;

import java.io.IOException;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Demo-mode guard for admin/config screens.
 * <p>
 * The portfolio demo showcases menu browsing, inventory, orders, the kitchen
 * queue, reports and the AI assistant. User management, business settings and
 * the assistant audit viewers are not meaningful to a portfolio visitor, so in
 * the {@code demo} profile they are disabled server-side (HTTP 403 with an
 * explanation) rather than reimplemented on throwaway data. The nav links are
 * hidden too (see {@code layout.html}), so this filter only fires for direct
 * URL access. Runs after Spring Security, so anonymous visitors still get the
 * normal login redirect.
 */
@Component
@Profile("demo")
@Order(Ordered.LOWEST_PRECEDENCE)
public class DemoModeFilter extends OncePerRequestFilter {

    /** URL prefixes disabled in the public demo (direct access → 403). */
    static final List<String> BLOCKED_PREFIXES = List.of(
            "/users",
            "/settings",
            "/admin/",
            "/assistant/admin");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        boolean blocked = path.equals("/admin");
        for (String prefix : BLOCKED_PREFIXES) {
            String subtree = prefix.endsWith("/") ? prefix : prefix + "/";
            if (path.equals(prefix) || path.startsWith(subtree)) {
                blocked = true;
                break;
            }
        }
        if (blocked) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("""
                    <!doctype html><html lang="en"><head><meta charset="utf-8"><title>Disabled in demo</title></head>
                    <body style="font-family:sans-serif;max-width:40rem;margin:4rem auto;padding:0 1rem">
                    <h1>Disabled in the public demo</h1>
                    <p>User management, business settings and assistant audit viewers are turned off
                    in this shared showcase — every visitor gets a throwaway sandbox, and these
                    screens are not part of it.</p>
                    <p><a href="/">Back to the dashboard</a></p>
                    </body></html>""");
            return;
        }
        filterChain.doFilter(request, response);
    }
}

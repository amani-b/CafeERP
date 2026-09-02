package com.cafeerp.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.cafeerp.user.User;
import com.cafeerp.user.UserRepository;
import com.cafeerp.user.UserSessionLog;
import com.cafeerp.user.UserSessionLogRepository;

/**
 * Records a LOGIN row in {@code user_session_log} whenever a user completes
 * interactive authentication (form login). The matching LOGOUT row is written
 * by {@link com.cafeerp.common.AuditLogoutSuccessHandler}.
 */
@Component
public class SessionAuditLoginListener {

    private static final Logger log = LoggerFactory.getLogger(SessionAuditLoginListener.class);

    private final UserRepository userRepository;
    private final UserSessionLogRepository sessionLogRepository;

    public SessionAuditLoginListener(UserRepository userRepository,
                                     UserSessionLogRepository sessionLogRepository) {
        this.userRepository = userRepository;
        this.sessionLogRepository = sessionLogRepository;
    }

    @EventListener
    public void onInteractiveLogin(InteractiveAuthenticationSuccessEvent event) {
        try {
            Authentication auth = event.getAuthentication();
            if (!(auth.getPrincipal() instanceof UserDetails details)) {
                return;
            }
            userRepository.findByUsername(details.getUsername()).ifPresent(user -> {
                // Best-effort session id capture; the event itself carries none.
                String sessionId = null;
                var attrs = RequestContextHolder.getRequestAttributes();
                if (attrs instanceof ServletRequestAttributes servletAttrs
                        && servletAttrs.getRequest().getSession(false) != null) {
                    sessionId = servletAttrs.getRequest().getSession(false).getId();
                }
                sessionLogRepository.save(new UserSessionLog(user, UserSessionLog.Event.LOGIN, sessionId));
                log.debug("Session audit: LOGIN recorded for '{}'", user.getUsername());
            });
        } catch (Exception e) {
            // Never let audit failures break the login flow.
            log.error("Failed to record LOGIN in user_session_log", e);
        }
    }
}
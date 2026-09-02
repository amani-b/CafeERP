package com.cafeerp.common;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Component;

import com.cafeerp.user.User;
import com.cafeerp.user.UserRepository;
import com.cafeerp.user.UserSessionLog;
import com.cafeerp.user.UserSessionLogRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Writes the LOGOUT row of the session audit trail ({@code user_session_log})
 * before delegating to the standard {@code /login?logout} redirect.
 */
@Component
public class AuditLogoutSuccessHandler implements LogoutSuccessHandler {

    private final UserRepository userRepository;
    private final UserSessionLogRepository sessionLogRepository;

    public AuditLogoutSuccessHandler(UserRepository userRepository,
                                     UserSessionLogRepository sessionLogRepository) {
        this.userRepository = userRepository;
        this.sessionLogRepository = sessionLogRepository;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
                                Authentication authentication) throws IOException {
        try {
            if (authentication != null && authentication.isAuthenticated()) {
                String username = authentication.getName();
                HttpSession session = request.getSession(false);
                String sessionId = session != null ? session.getId() : null;
                userRepository.findByUsername(username).ifPresent(user ->
                        sessionLogRepository.save(new UserSessionLog(
                                user, UserSessionLog.Event.LOGOUT, sessionId)));
            }
        } catch (Exception e) {
            // Never let audit failures break logout.
        }
        response.sendRedirect("/login?logout");
    }
}
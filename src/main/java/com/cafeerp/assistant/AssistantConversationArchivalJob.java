package com.cafeerp.assistant;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chat conversation retention:
 * <ol>
 *   <li><b>Auto-archive</b> — conversations with no activity for
 *       {@code assistant.chat.archive-after-days} (default 30) are
 *       SOFT-archived: {@code archived_at} is stamped and the conversation
 *       disappears from the default history list, but nothing is deleted and
 *       users can recover archived threads via the history "Archived"
 *       filter.</li>
 *   <li><b>Hard purge</b> — conversations archived for more than
 *       {@code assistant.chat.purge-after-days} (default 90 days). Messages are
 *       removed first, then the conversations — no other data is touched. Both
 *       thresholds are configuration values, tunable without a redeploy.</li>
 * </ol>
 */
@Component
public class AssistantConversationArchivalJob {

    private static final Logger log = LoggerFactory.getLogger(AssistantConversationArchivalJob.class);

    private final AssistantConversationRepository conversationRepository;
    private final AssistantMessageRepository messageRepository;
    private final int archiveAfterDays;
    private final int purgeAfterDays;

    public AssistantConversationArchivalJob(
            AssistantConversationRepository conversationRepository,
            AssistantMessageRepository messageRepository,
            @Value("${assistant.chat.archive-after-days:30}") int archiveAfterDays,
            @Value("${assistant.chat.purge-after-days:365}") int purgeAfterDays) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.archiveAfterDays = archiveAfterDays;
        this.purgeAfterDays = purgeAfterDays;
    }

    /**
     * Nightly at 03:00 server time. Public so tests (and the runbook's manual
     * maintenance) can trigger a run directly.
     *
     * @return number of conversations archived by this run
     */
    @Scheduled(cron = "${assistant.chat.archive-cron:0 0 3 * * *}")
    @Transactional
    public int archiveStaleConversations() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(archiveAfterDays);
        List<AssistantConversation> stale =
                conversationRepository.findByArchivedAtIsNullAndLastActivityAtBefore(cutoff);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (AssistantConversation conversation : stale) {
            conversation.setArchivedAt(now);
            conversationRepository.save(conversation);
        }
        if (!stale.isEmpty()) {
            log.info("Auto-archived {} assistant conversation(s) idle for more than {} days",
                    stale.size(), archiveAfterDays);
        }
        return stale.size();
    }

    /**
     * Nightly at 03:30 server time (after the archive run). PERMANENTLY
     * deletes conversations that have been soft-archived for more than
     * {@code assistant.chat.purge-after-days} (default 90). Messages are
     * removed first, then the conversations — no other data is touched.
     * Irreversible by design.
     *
     * @return number of conversations purged by this run
     */
    @Scheduled(cron = "${assistant.chat.purge-cron:0 30 3 * * *}")
    @Transactional
    public int purgeExpiredArchivedConversations() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(purgeAfterDays);
        List<AssistantConversation> expired =
                conversationRepository.findByArchivedAtIsNotNullAndArchivedAtBefore(cutoff);
        for (AssistantConversation conversation : expired) {
            messageRepository.deleteByConversation(conversation);
            conversationRepository.delete(conversation);
        }
        if (!expired.isEmpty()) {
            log.info("Purged {} assistant conversation(s) archived more than {} days ago",
                    expired.size(), purgeAfterDays);
        }
        return expired.size();
    }
}
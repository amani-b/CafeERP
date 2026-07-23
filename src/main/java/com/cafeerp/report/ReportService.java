package com.cafeerp.report;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cafeerp.order.ItemSalesProjection;
import com.cafeerp.order.OrderItemRepository;
import com.cafeerp.order.OrderRepository;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public ReportService(OrderRepository orderRepository,
                         OrderItemRepository orderItemRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
    }

    /**
     * Resolves a date range preset into concrete from/to dates.
     *
     * @param preset one of "today", "week", "month", "all", or null (defaults to "today")
     * @return a DateRange with resolved from/to
     */
    public DateRange resolveDateRange(String preset) {
        LocalDate today = LocalDate.now();
        LocalDateTime from;
        LocalDateTime to;

        if (preset == null) {
            preset = "today";
        }

        switch (preset.toLowerCase()) {
            case "today":
                from = today.atStartOfDay();
                to = today.atTime(LocalTime.MAX);
                break;
            case "week":
                from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
                to = today.atTime(LocalTime.MAX);
                break;
            case "month":
                from = today.withDayOfMonth(1).atStartOfDay();
                to = today.atTime(LocalTime.MAX);
                break;
            case "all":
                from = EPOCH;
                to = today.atTime(LocalTime.MAX);
                break;
            default:
                log.warn("Unknown report preset '{}', defaulting to today", preset);
                from = today.atStartOfDay();
                to = today.atTime(LocalTime.MAX);
                break;
        }

        return new DateRange(from, to);
    }

    /**
     * Resolves from/to dates, preferring explicit custom dates if provided,
     * otherwise falling back to a preset.
     */
    public DateRange resolveDateRange(String preset, LocalDate customFrom, LocalDate customTo) {
        if (customFrom != null && customTo != null) {
            if (customTo.isBefore(customFrom)) {
                throw new IllegalArgumentException("End date cannot be before start date");
            }
            return new DateRange(customFrom.atStartOfDay(), customTo.atTime(LocalTime.MAX));
        }
        return resolveDateRange(preset);
    }

    @Transactional(readOnly = true)
    public ReportData generateReport(LocalDateTime from, LocalDateTime to) {
        log.debug("Generating report from {} to {}", from, to);

        BigDecimal totalSales = orderRepository.sumTotalAmountBetween(from, to);
        long orderCount = orderRepository.countByCreatedAtBetween(from, to);
        List<ItemSalesProjection> topItems = orderItemRepository.findTopSellingItems(from, to);

        // Limit to top 5
        List<ItemSalesProjection> top5 = topItems.size() > 5 ? topItems.subList(0, 5) : topItems;

        return new ReportData(totalSales, orderCount, top5, from, to);
    }

    /**
     * Value object for a resolved date range.
     */
    public record DateRange(LocalDateTime from, LocalDateTime to) {
    }

    /**
     * Value object carrying all report data for the view.
     */
    public record ReportData(
            BigDecimal totalSales,
            long orderCount,
            List<ItemSalesProjection> topItems,
            LocalDateTime from,
            LocalDateTime to) {
    }
}
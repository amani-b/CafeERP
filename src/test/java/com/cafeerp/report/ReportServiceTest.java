package com.cafeerp.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cafeerp.order.ItemSalesProjection;
import com.cafeerp.order.OrderItemRepository;
import com.cafeerp.order.OrderRepository;
import com.cafeerp.report.ReportService.DateRange;
import com.cafeerp.report.ReportService.ReportData;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @InjectMocks
    private ReportService reportService;

    private final LocalDate today = LocalDate.now();

    // -------------------------------------------------------
    //  Date-range resolution
    // -------------------------------------------------------

    @Test
    void resolveDateRange_today() {
        DateRange range = reportService.resolveDateRange("today");
        assertEquals(today.atStartOfDay(), range.from());
        assertEquals(today.atTime(23, 59, 59, 999_999_999), range.to());
    }

    @Test
    void resolveDateRange_week() {
        DateRange range = reportService.resolveDateRange("week");
        assertEquals(today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).atStartOfDay(), range.from());
        assertEquals(today.atTime(23, 59, 59, 999_999_999), range.to());
    }

    @Test
    void resolveDateRange_month() {
        DateRange range = reportService.resolveDateRange("month");
        assertEquals(today.withDayOfMonth(1).atStartOfDay(), range.from());
        assertEquals(today.atTime(23, 59, 59, 999_999_999), range.to());
    }

    @Test
    void resolveDateRange_all() {
        DateRange range = reportService.resolveDateRange("all");
        assertEquals(LocalDateTime.of(1970, 1, 1, 0, 0), range.from());
        assertEquals(today.atTime(23, 59, 59, 999_999_999), range.to());
    }


    @Test
    void resolveDateRange_customDates() {
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        DateRange range = reportService.resolveDateRange(null, from, to);
        assertEquals(from.atStartOfDay(), range.from());
        assertEquals(to.atTime(23, 59, 59, 999_999_999), range.to());
    }

    @Test
    void resolveDateRange_customDates_invalidRange_throws() {
        LocalDate from = LocalDate.of(2026, 7, 10);
        LocalDate to = LocalDate.of(2026, 7, 5);
        assertThrows(IllegalArgumentException.class,
            () -> reportService.resolveDateRange(null, from, to));
    }

    @Test
    void resolveDateRange_customDatesOverridesPreset() {
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        // Preset is ignored when custom dates are provided
        DateRange range = reportService.resolveDateRange("all", from, to);
        assertEquals(from.atStartOfDay(), range.from());
        assertEquals(to.atTime(23, 59, 59, 999_999_999), range.to());
    }

    // -------------------------------------------------------
    //  Aggregate queries
    // -------------------------------------------------------

    @Test
    void generateReport_withData() {
        LocalDateTime from = today.atStartOfDay();
        LocalDateTime to = today.atTime(23, 59, 59, 999_999_999);

        when(orderRepository.sumTotalAmountBetween(from, to)).thenReturn(new BigDecimal("150.00"));
        when(orderRepository.countByCreatedAtBetween(from, to)).thenReturn(5L);

        ItemSalesProjection item1 = mockProjection("Latte", 10L);
        ItemSalesProjection item2 = mockProjection("Cappuccino", 7L);
        ItemSalesProjection item3 = mockProjection("Mocha", 5L);
        ItemSalesProjection item4 = mockProjection("Espresso", 3L);
        ItemSalesProjection item5 = mockProjection("Tea", 2L);
        ItemSalesProjection item6 = mockProjection("Hot Chocolate", 1L);

        when(orderItemRepository.findTopSellingItems(from, to))
                .thenReturn(List.of(item1, item2, item3, item4, item5, item6));

        ReportData report = reportService.generateReport(from, to);

        assertEquals(new BigDecimal("150.00"), report.totalSales());
        assertEquals(5L, report.orderCount());
        assertEquals(5, report.topItems().size());
        assertEquals("Latte", report.topItems().get(0).getItemName());
        assertEquals(10L, report.topItems().get(0).getTotalQuantity());
    }

    @Test
    void generateReport_noDataReturnsZeros() {
        LocalDateTime from = today.atStartOfDay();
        LocalDateTime to = today.atTime(23, 59, 59, 999_999_999);

        when(orderRepository.sumTotalAmountBetween(from, to)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.countByCreatedAtBetween(from, to)).thenReturn(0L);
        when(orderItemRepository.findTopSellingItems(from, to)).thenReturn(List.of());

        ReportData report = reportService.generateReport(from, to);

        assertEquals(BigDecimal.ZERO, report.totalSales());
        assertEquals(0L, report.orderCount());
        assertTrue(report.topItems().isEmpty());
    }

    private static ItemSalesProjection mockProjection(String name, Long quantity) {
        return new ItemSalesProjection() {
            @Override
            public String getItemName() {
                return name;
            }

            @Override
            public Long getTotalQuantity() {
                return quantity;
            }
        };
    }
}
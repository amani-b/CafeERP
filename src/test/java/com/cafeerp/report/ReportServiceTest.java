package com.cafeerp.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cafeerp.order.ItemSalesProjection;
import com.cafeerp.order.OrderItemRepository;
import com.cafeerp.order.OrderRepository;
import com.cafeerp.report.ReportService.DateRange;
import com.cafeerp.report.ReportService.ReportData;
import com.cafeerp.settings.SettingsService;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    /** Fixed-offset zone (UTC+3, no DST) so expectations are machine-independent. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Addis_Ababa");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private SettingsService settingsService;

    @InjectMocks
    private ReportService reportService;

    /** "Today" in the BUSINESS zone (what resolveDateRange now uses). */
    private final LocalDate today = LocalDate.now(BUSINESS_ZONE);

    @BeforeEach
    void setUp() {
        lenient().when(settingsService.getTimeZone()).thenReturn(BUSINESS_ZONE);
    }

    /** Mirrors ReportService.toUtc: business-local wall time -> UTC. */
    private static LocalDateTime toUtc(LocalDateTime businessLocal) {
        return businessLocal.atZone(BUSINESS_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }

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

        // Repository query receives UTC boundaries (business-local minus 3h).
        // Phase 9: total + count arrive as ONE combined row [total, count].
        when(orderRepository.sumAndCountBetween(toUtc(from), toUtc(to)))
                .thenReturn(List.<Object[]>of(new Object[] {new BigDecimal("150.00"), 5L}));

        ItemSalesProjection item1 = mockProjection("Latte", 10L);
        ItemSalesProjection item2 = mockProjection("Cappuccino", 7L);
        ItemSalesProjection item3 = mockProjection("Mocha", 5L);
        ItemSalesProjection item4 = mockProjection("Espresso", 3L);
        ItemSalesProjection item5 = mockProjection("Tea", 2L);
        ItemSalesProjection item6 = mockProjection("Hot Chocolate", 1L);

        when(orderItemRepository.findTopSellingItems(toUtc(from), toUtc(to)))
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

        // Empty range: the DB returns Integer 0 (not BigDecimal) for the
        // coalesced total — the service must convert, not cast.
        when(orderRepository.sumAndCountBetween(toUtc(from), toUtc(to)))
                .thenReturn(List.<Object[]>of(new Object[] {0, 0L}));
        when(orderItemRepository.findTopSellingItems(toUtc(from), toUtc(to))).thenReturn(List.of());

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
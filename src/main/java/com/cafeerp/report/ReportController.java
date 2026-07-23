package com.cafeerp.report;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.cafeerp.report.ReportService.DateRange;
import com.cafeerp.report.ReportService.ReportData;

@Controller
@RequestMapping("/reports")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    public String showReport(
            @RequestParam(required = false) String preset,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            Model model) {

        DateRange range = reportService.resolveDateRange(preset, from, to);
        ReportData data = reportService.generateReport(range.from(), range.to());

        model.addAttribute("report", data);
        model.addAttribute("selectedPreset", preset != null ? preset : "today");
        model.addAttribute("customFrom", from);
        model.addAttribute("customTo", to);

        return "reports/index";
    }
}
package com.griddb.solar.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the main dashboard HTML page (Thymeleaf template).
 */
@Controller
public class DashboardController {

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("title", "Solar Potential Analytics");
        model.addAttribute("subtitle", "A Time-Series Look at Regional Solar Radiation Using GridDB");
        return "dashboard";
    }
}

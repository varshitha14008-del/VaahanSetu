
package com.vaahansetu.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vaahansetu.repository.RouteRepository;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class RouteController {

    private final RouteRepository routeRepository;

    public RouteController(RouteRepository routeRepository) {
        this.routeRepository = routeRepository;
    }

    // Get all active routes
    @GetMapping("/routes")
    public List<Map<String, Object>> getRoutes() {
        return routeRepository.findAllActiveRoutes();
    }

    // Get stops for a selected route
    @GetMapping("/routes/{routeId}/stops")
    public List<Map<String, Object>> getStops(
            @PathVariable Long routeId) {

        return routeRepository.findStopsByRouteId(routeId);
    }

    // Get buses allocated to a selected route
    @GetMapping("/routes/{routeId}/buses")
    public List<Map<String, Object>> getAllocatedBuses(
            @PathVariable Long routeId) {

        return routeRepository.findAllocatedBuses(routeId);
    }
}

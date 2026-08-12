package com.ugnay.platform.warehouse;

import com.ugnay.platform.warehouse.WarehouseContracts.AnalyticsView;
import com.ugnay.platform.warehouse.WarehouseContracts.ContinuationHistoryView;
import com.ugnay.platform.warehouse.WarehouseContracts.LoadView;
import com.ugnay.platform.warehouse.WarehouseContracts.QualityIssueView;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/warehouse")
@PreAuthorize("isAuthenticated()")
public class WarehouseController {
    private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");
    private final WarehouseService warehouse;

    public WarehouseController(WarehouseService warehouse) {
        this.warehouse = warehouse;
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasRole('CURATOR')")
    public ResponseEntity<LoadView> refresh(Authentication authentication) {
        LoadView load = warehouse.refresh(authentication);
        HttpStatus status = switch (load.status()) {
            case "PUBLISHED" -> HttpStatus.CREATED;
            case "UNCHANGED" -> HttpStatus.OK;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(load);
    }

    @GetMapping("/loads/latest")
    @PreAuthorize("hasRole('CURATOR')")
    public LoadView latestLoad(Authentication authentication) {
        return warehouse.latestLoad(authentication);
    }

    @GetMapping("/loads/{loadId}")
    @PreAuthorize("hasRole('CURATOR')")
    public LoadView load(@PathVariable UUID loadId, Authentication authentication) {
        return warehouse.load(authentication, loadId);
    }

    @GetMapping("/loads/{loadId}/quality")
    @PreAuthorize("hasRole('CURATOR')")
    public List<QualityIssueView> quality(@PathVariable UUID loadId, Authentication authentication) {
        return warehouse.qualityIssues(authentication, loadId);
    }

    @GetMapping("/analytics")
    public AnalyticsView analytics(Authentication authentication,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) Integer fromYear,
            @RequestParam(required = false) Integer toYear) {
        return warehouse.analytics(authentication, department, fromYear, toYear);
    }

    @GetMapping(value = "/analytics.csv", produces = "text/csv")
    public ResponseEntity<byte[]> analyticsCsv(Authentication authentication,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) Integer fromYear,
            @RequestParam(required = false) Integer toYear) {
        return csv("ugnay-warehouse-analytics.csv", warehouse.analyticsCsv(authentication, department, fromYear, toYear));
    }

    @GetMapping("/continuation-history")
    public ContinuationHistoryView continuationHistory(Authentication authentication,
            @RequestParam(defaultValue = "100") int limit) {
        return warehouse.continuationHistory(authentication, limit);
    }

    @GetMapping(value = "/continuation-history.csv", produces = "text/csv")
    public ResponseEntity<byte[]> continuationCsv(Authentication authentication,
            @RequestParam(defaultValue = "500") int limit) {
        return csv("ugnay-continuation-history.csv", warehouse.continuationCsv(authentication, limit));
    }

    private static ResponseEntity<byte[]> csv(String filename, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentLength(bytes.length)
                .body(bytes);
    }
}

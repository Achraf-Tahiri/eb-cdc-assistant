package com.ebcdc.assistant.web;

import com.ebcdc.assistant.analytics.AnalyticsService;
import com.ebcdc.assistant.domain.AppUser;
import com.ebcdc.assistant.domain.Role;
import com.ebcdc.assistant.domain.WorkflowAction;
import com.ebcdc.assistant.workflow.ForbiddenActionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /** L'analytique prestataire est réservée aux équipes internes. */
    private static void internalOnly(Authentication auth) {
        AppUser user = (AppUser) auth.getPrincipal();
        if (user.getRole() == Role.PRESTATAIRE) {
            throw new ForbiddenActionException(user.getRole(), WorkflowAction.CLOSE);
        }
    }

    @GetMapping("/providers")
    public List<Map<String, Object>> providers(Authentication auth) {
        internalOnly(auth);
        return analyticsService.providerMetrics();
    }

    @GetMapping("/documents/{id}")
    public List<Map<String, Object>> documentBreakdown(@PathVariable UUID id,
                                                       Authentication auth) {
        internalOnly(auth);
        return analyticsService.documentBreakdown(id);
    }

    @GetMapping("/correlation")
    public List<Map<String, Object>> correlation(Authentication auth) {
        internalOnly(auth);
        return analyticsService.completenessCorrelation();
    }

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts(Authentication auth) {
        internalOnly(auth);
        return analyticsService.alerts();
    }

    /** Export CSV (séparateur point-virgule, BOM UTF-8) pour les revues de contrat TMA. */
    @GetMapping("/providers.csv")
    public ResponseEntity<byte[]> providersCsv(Authentication auth) {
        internalOnly(auth);
        List<Map<String, Object>> rows = analyticsService.providerMetrics();
        StringBuilder sb = new StringBuilder();
        sb.append("prestataire;contrat;documents;m1_attente_moyenne_jours;")
          .append("m2_taux_tardif_pct;m3_cycles_par_document;m4_ratio_cycles_questions;")
          .append("questions;cycles\n");
        for (Map<String, Object> r : rows) {
            sb.append(csv(r.get("name"))).append(';')
              .append(csv(r.get("contract_ref"))).append(';')
              .append(csv(r.get("nb_documents"))).append(';')
              .append(csv(r.get("m1_attente_moyenne_jours"))).append(';')
              .append(csv(r.get("m2_taux_tardif_pct"))).append(';')
              .append(csv(r.get("m3_cycles_par_document"))).append(';')
              .append(csv(r.get("m4_ratio_cycles_questions"))).append(';')
              .append(csv(r.get("total_questions"))).append(';')
              .append(csv(r.get("total_cycles"))).append('\n');
        }
        byte[] bytes = ("\uFEFF" + sb).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"metriques-prestataires.csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(bytes);
    }

    private static String csv(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}

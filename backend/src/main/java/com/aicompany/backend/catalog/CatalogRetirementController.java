package com.aicompany.backend.catalog;

import com.aicompany.backend.security.Caller;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PHASE 32 (TD-71): the retired entries of the shipped catalog, and the way back.
 * Under {@code /api/admin}: retiring and restoring are an admin's decisions, like
 * the deletions that retire.
 */
@RestController
public class CatalogRetirementController {

    private final CatalogRetirements retirements;

    public CatalogRetirementController(CatalogRetirements retirements) {
        this.retirements = retirements;
    }

    @GetMapping("/api/admin/catalog/retirements")
    public List<CatalogRetirements.Retirement> catalogRetirements() {
        return retirements.list();
    }

    /** Puts the entry back as the catalog ships it. */
    @DeleteMapping("/api/admin/catalog/retirements/{catalog}/{key}")
    public ResponseEntity<Void> restoreCatalogEntry(@PathVariable CatalogRetirements.Catalog catalog,
                                                    @PathVariable String key, @AuthenticationPrincipal Caller caller) {
        retirements.restore(catalog, key, caller.name());
        return ResponseEntity.noContent().build();
    }
}

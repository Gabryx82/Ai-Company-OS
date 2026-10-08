package com.aicompany.backend.software.service;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.catalog.CatalogBootstrap;
import com.aicompany.backend.deletion.DeletionPreview;
import com.aicompany.backend.software.exception.SoftwareNotFoundException;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.software.repository.SoftwareRepository;
import com.aicompany.backend.user.service.SecurityLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * PHASE 32 (TD-68): a software entry added through {@code POST /api/software} is
 * deleted the way PHASE 30 deletes everything else (ADR-035): a preview computed
 * with the same rules as the act, an admin, the entry's tag. An entry of the
 * shipped catalog is refused -- a start would insert it again -- and is disabled
 * instead. The program installed on the machine is never touched: only the entry
 * leaves, with its links to agents and its place in the coordinated start.
 */
@Service
@Transactional
public class SoftwareDeletionService {

    private static final Set<String> SHIPPED = CatalogBootstrap.shippedKeys("catalog/software.json");

    private final SoftwareRepository repository;
    private final JdbcTemplate jdbc;
    private final SecurityLog log;
    private final com.aicompany.backend.catalog.CatalogRetirements retirements;

    public SoftwareDeletionService(SoftwareRepository repository, JdbcTemplate jdbc, SecurityLog log,
                                   com.aicompany.backend.catalog.CatalogRetirements retirements) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.log = log;
        this.retirements = retirements;
    }

    /** Whether the key belongs to the catalog AI Company OS ships, which a start re-inserts. */
    public static boolean shipped(String key) {
        return SHIPPED.contains(key);
    }

    @Transactional(readOnly = true)
    public DeletionPreview preview(String key) {
        return impact(repository.findByKey(key).orElseThrow(() -> new SoftwareNotFoundException(key)));
    }

    public void delete(String key, Precondition precondition, String by) {
        Software software = repository.findByKeyForUpdate(key).orElseThrow(() -> new SoftwareNotFoundException(key));
        precondition.requireSatisfiedBy(software.getVersion());
        impact(software).requireAllowed();
        jdbc.update("DELETE FROM agent_software WHERE software_id = ?", software.getId());
        jdbc.update("DELETE FROM ecosystem_autostart WHERE software_key = ?", software.getKey());
        repository.delete(software);
        repository.flush();
        // PHASE 32 (TD-71): a shipped entry is retired, so that the next start does not bring it back.
        if (shipped(key)) {
            retirements.retire(com.aicompany.backend.catalog.CatalogRetirements.Catalog.SOFTWARE, key, by);
        }
        log.record(SecurityLog.DATA_DELETED, by, null, "software " + key);
    }

    private DeletionPreview impact(Software software) {
        String name = software.getName();
        DeletionPreview.Builder preview = DeletionPreview.of("SOFTWARE").subject(software.getKey(), name)
                .deletes("«" + name + "» esce dal Software Hub.")
                .keeps("Il programma installato sul PC non è toccato.");
        if (shipped(software.getKey())) {
            preview.deletes("È nel catalogo di AI Company OS: la voce viene congedata e il prossimo avvio non la reinserisce. "
                    + "Si ripristina, com'è nel catalogo, da Impostazioni › Voci congedate.");
        }
        List<String> agents = jdbc.queryForList("SELECT a.name FROM agent_software l JOIN agents a ON a.id = l.agent_id "
                + "WHERE l.software_id = ? ORDER BY a.name", String.class, software.getId());
        if (!agents.isEmpty()) {
            preview.detaches("Gli agenti che potevano usarlo lo perdono e restano: " + String.join(", ", agents) + ".");
        }
        Integer started = jdbc.queryForObject("SELECT count(*) FROM ecosystem_autostart WHERE software_key = ?",
                Integer.class, software.getKey());
        if (started != null && started > 0) {
            preview.detaches("Esce dall'avvio coordinato dell'ecosistema.");
        }
        return preview.build();
    }
}

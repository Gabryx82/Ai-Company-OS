package com.aicompany.backend.llm.service;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.catalog.CatalogBootstrap;
import com.aicompany.backend.catalog.CatalogRetirements;
import com.aicompany.backend.deletion.DeletionPreview;
import com.aicompany.backend.llm.exception.ModelNotFoundException;
import com.aicompany.backend.llm.model.LlmModel;
import com.aicompany.backend.llm.repository.LlmModelRepository;
import com.aicompany.backend.user.service.SecurityLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * PHASE 32 (TD-71): a model leaves the catalog the way PHASE 30 deletes everything
 * else (ADR-035): a preview computed with the same rules as the act, an admin, the
 * entry's tag. A model the catalog ships is retired, so the next start does not
 * insert it again; it comes back, as shipped, from the retired entries. Nothing is
 * removed from Ollama or from any provider: only the entry.
 */
@Service
@Transactional
public class ModelDeletionService {

    private static final Set<String> SHIPPED = CatalogBootstrap.shippedKeys("catalog/models.json");

    private final LlmModelRepository models;
    private final JdbcTemplate jdbc;
    private final SecurityLog log;
    private final CatalogRetirements retirements;

    public ModelDeletionService(LlmModelRepository models, JdbcTemplate jdbc, SecurityLog log,
                                CatalogRetirements retirements) {
        this.models = models;
        this.jdbc = jdbc;
        this.log = log;
        this.retirements = retirements;
    }

    @Transactional(readOnly = true)
    public DeletionPreview preview(String key) {
        return impact(models.findByKey(key).orElseThrow(() -> new ModelNotFoundException(key)));
    }

    public void delete(String key, Precondition precondition, String by) {
        LlmModel model = models.findByKeyForUpdate(key).orElseThrow(() -> new ModelNotFoundException(key));
        precondition.requireSatisfiedBy(model.getVersion());
        impact(model).requireAllowed();
        models.delete(model);
        models.flush();
        if (SHIPPED.contains(key)) {
            retirements.retire(CatalogRetirements.Catalog.MODEL, key, by);
        }
        log.record(SecurityLog.DATA_DELETED, by, null, "model " + key);
    }

    private DeletionPreview impact(LlmModel model) {
        String name = model.getDisplayName();
        DeletionPreview.Builder preview = DeletionPreview.of("MODEL").subject(model.getKey(), name)
                .deletes("«" + name + "» esce dal catalogo dei modelli, con il suo ruolo e il suo prezzo.")
                .keeps("Il modello non è rimosso da Ollama né dal provider: esce solo la voce.")
                .keeps("Le esecuzioni passate restano con il loro modello e il loro costo.");
        if (SHIPPED.contains(model.getKey())) {
            preview.deletes("È nel catalogo di AI Company OS: la voce viene congedata e il prossimo avvio non la reinserisce. "
                    + "Si ripristina, com'è nel catalogo, da Impostazioni › Voci congedate.");
        }
        List<String> agents = jdbc.queryForList("SELECT name FROM agents WHERE model = ? ORDER BY name", String.class,
                model.getKey());
        if (!agents.isEmpty()) {
            preview.keeps("Gli agenti che lo usano lo tengono, fuori catalogo: " + String.join(", ", agents) + ".");
        }
        return preview.build();
    }
}

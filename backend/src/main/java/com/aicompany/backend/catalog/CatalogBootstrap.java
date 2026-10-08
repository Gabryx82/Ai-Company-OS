package com.aicompany.backend.catalog;

import com.aicompany.backend.harness.model.HarnessResource;
import com.aicompany.backend.harness.repository.HarnessResourceRepository;
import com.aicompany.backend.llm.model.Billing;
import com.aicompany.backend.llm.model.LlmModel;
import com.aicompany.backend.llm.model.ModelLifecycle;
import com.aicompany.backend.llm.model.ModelProvider;
import com.aicompany.backend.llm.model.ModelRole;
import com.aicompany.backend.llm.model.ProviderKind;
import com.aicompany.backend.llm.model.ProviderStatus;
import com.aicompany.backend.llm.repository.LlmModelRepository;
import com.aicompany.backend.llm.repository.ModelProviderRepository;
import com.aicompany.backend.software.dto.SoftwareRequest;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.software.repository.SoftwareRepository;
import com.aicompany.backend.usage.model.QuotaPlan;
import com.aicompany.backend.usage.model.UsageSource;
import com.aicompany.backend.usage.model.WindowKind;
import com.aicompany.backend.usage.repository.QuotaPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/**
 * Inserts the reference catalogs -- software, providers, models, quota windows
 * -- from {@code classpath:catalog/*.json} (ADR-018 §4).
 *
 * <p><strong>Only missing keys are inserted.</strong> An entry the operator
 * edited is never overwritten by a later start, and an entry removed from the
 * file is never removed from the database: the file proposes, the operator
 * owns. Unknown fields in a file fail the start -- a typo in a catalog must not
 * silently produce an entry without the field it was meant to set.
 */
@Component
@Order(0)
public class CatalogBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogBootstrap.class);

    record ProviderEntry(String key, String name, ProviderKind kind, String engineProvider, Billing billing,
                         ProviderStatus status, String baseUrl, String docsUrl, String notes) {
    }

    record ModelEntry(String key, String providerKey, String displayName, ModelRole role, List<String> capabilities,
                      Integer contextWindow, BigDecimal sizeGb, String parameters, ModelLifecycle lifecycle,
                      String replacedBy, String notes, List<ModelRole> alsoServes) {
    }

    record ResourceEntry(String key, HarnessResource.Kind kind, String name, String description, List<String> tags,
                         String sourceUrl, String searchUrl, String configuration) {
    }

    record QuotaEntry(String key, String name, String subject, UsageSource source, WindowKind windowKind,
                      Integer resetWeekday, String resetTime, String resetZone, String limitNote, String usageUrl) {
    }

    private final SoftwareRepository software;
    private final ModelProviderRepository providers;
    private final LlmModelRepository models;
    private final QuotaPlanRepository quotas;
    private final HarnessResourceRepository resources;
    private final TransactionTemplate transactions;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public CatalogBootstrap(SoftwareRepository software, ModelProviderRepository providers, LlmModelRepository models,
                            QuotaPlanRepository quotas, HarnessResourceRepository resources,
                            TransactionTemplate transactions, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.software = software;
        this.providers = providers;
        this.models = models;
        this.quotas = quotas;
        this.resources = resources;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(status -> {
            int added = 0;
            // PHASE 32 (TD-71): a retired entry is not inserted again.
            java.util.Set<String> retired = retired();
            added += insertSoftware(key -> !retired.contains("SOFTWARE:" + key));
            for (ProviderEntry p : read("catalog/providers.json", ProviderEntry[].class)) {
                if (!providers.existsByKey(p.key())) {
                    providers.save(new ModelProvider(p.key(), p.name(), p.kind(), p.engineProvider(), p.billing(),
                            p.status(), p.baseUrl(), p.docsUrl(), p.notes()));
                    added++;
                }
            }
            providers.flush();
            added += insertModels(key -> !retired.contains("MODEL:" + key));
            for (QuotaEntry q : read("catalog/quota-plans.json", QuotaEntry[].class)) {
                if (!quotas.existsByKey(q.key())) {
                    quotas.save(new QuotaPlan(q.key(), q.name(), q.subject(), q.source(), q.windowKind(),
                            q.resetWeekday(), q.resetTime() == null ? null : LocalTime.parse(q.resetTime()),
                            q.resetZone(), q.limitNote(), q.usageUrl()));
                    added++;
                }
            }
            java.util.Set<String> shippedResources = shippedKeys("catalog/resources.json");
            added += insertResources(key -> !retired.contains("RESOURCE:" + key));
            // PHASE 32 (TD-73): POST /api/resources used to record a person's entry as CATALOG.
            // An entry labelled CATALOG that the shipped catalog does not have is one of those.
            int relabelled = 0;
            for (HarnessResource r : resources.findAll()) {
                if (r.getOrigin() == HarnessResource.Origin.CATALOG && !shippedResources.contains(r.getKey())) {
                    r.createdByAPerson();
                    relabelled++;
                }
            }
            if (relabelled > 0) {
                log.info("Catalog bootstrap: {} entries created by a person relabelled USER", relabelled);
            }
            if (added > 0) {
                log.info("Catalog bootstrap: {} new reference entries", added);
            }
        });
    }

    private int insertSoftware(java.util.function.Predicate<String> wanted) {
        int added = 0;
        for (SoftwareRequest entry : read("catalog/software.json", SoftwareRequest[].class)) {
            if (wanted.test(entry.key()) && !software.existsByKey(entry.key())) {
                software.save(new Software(entry.key(), entry.definition()));
                added++;
            }
        }
        return added;
    }

    private int insertModels(java.util.function.Predicate<String> wanted) {
        int added = 0;
        for (ModelEntry m : read("catalog/models.json", ModelEntry[].class)) {
            if (wanted.test(m.key()) && !models.existsByKey(m.key())) {
                LlmModel model = new LlmModel(m.key(), m.providerKey(), m.displayName(), m.role(), m.capabilities(),
                        m.contextWindow(), m.sizeGb(), m.parameters(), m.lifecycle(), m.replacedBy(), m.notes());
                model.alsoServe(m.alsoServes());
                models.save(model);
                added++;
            }
        }
        return added;
    }

    private int insertResources(java.util.function.Predicate<String> wanted) {
        int added = 0;
        for (ResourceEntry r : read("catalog/resources.json", ResourceEntry[].class)) {
            if (wanted.test(r.key()) && !resources.existsByKey(r.key())) {
                resources.save(new HarnessResource(r.key(), r.kind(), r.name(), r.description(), r.tags(),
                        r.sourceUrl(), r.searchUrl(), r.configuration()));
                added++;
            }
        }
        return added;
    }

    /** PHASE 32 (TD-71): the retired entries, as {@code CATALOG:key}. */
    private java.util.Set<String> retired() {
        return java.util.Set.copyOf(jdbc.queryForList("SELECT catalog || ':' || key FROM catalog_retirements", String.class));
    }

    /**
     * PHASE 32 (TD-71): puts one shipped entry back, as the catalog ships it, in the
     * caller's transaction. False when the catalog does not ship that key, or the entry
     * is already there.
     */
    public boolean restore(String catalog, String key) {
        return switch (catalog) {
            case "SOFTWARE" -> insertSoftware(key::equals) > 0;
            case "MODEL" -> insertModels(key::equals) > 0;
            case "RESOURCE" -> insertResources(key::equals) > 0;
            default -> false;
        };
    }

    /**
     * PHASE 30 (ADR-035 §7): the keys a catalog file ships -- the entries a start
     * inserts again whenever they are missing. Deleting one of them would not
     * last, so the delete refuses them and says why.
     */
    public static java.util.Set<String> shippedKeys(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            java.util.Set<String> keys = new java.util.HashSet<>();
            JsonMapper.builder().build().readTree(in).forEach(entry -> keys.add(entry.path("key").asString()));
            return java.util.Set.copyOf(keys);
        } catch (IOException e) {
            throw new UncheckedIOException("Catalog " + resource + " is unreadable", e);
        }
    }

    private <T> List<T> read(String resource, Class<T[]> type) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return List.of(json.readValue(in, type));
        } catch (IOException e) {
            throw new UncheckedIOException("Catalog " + resource + " is unreadable", e);
        }
    }
}

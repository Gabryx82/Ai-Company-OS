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
                      String replacedBy, String notes) {
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
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public CatalogBootstrap(SoftwareRepository software, ModelProviderRepository providers, LlmModelRepository models,
                            QuotaPlanRepository quotas, HarnessResourceRepository resources,
                            TransactionTemplate transactions) {
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
            for (SoftwareRequest entry : read("catalog/software.json", SoftwareRequest[].class)) {
                if (!software.existsByKey(entry.key())) {
                    software.save(new Software(entry.key(), entry.definition()));
                    added++;
                }
            }
            for (ProviderEntry p : read("catalog/providers.json", ProviderEntry[].class)) {
                if (!providers.existsByKey(p.key())) {
                    providers.save(new ModelProvider(p.key(), p.name(), p.kind(), p.engineProvider(), p.billing(),
                            p.status(), p.baseUrl(), p.docsUrl(), p.notes()));
                    added++;
                }
            }
            providers.flush();
            for (ModelEntry m : read("catalog/models.json", ModelEntry[].class)) {
                if (!models.existsByKey(m.key())) {
                    models.save(new LlmModel(m.key(), m.providerKey(), m.displayName(), m.role(), m.capabilities(),
                            m.contextWindow(), m.sizeGb(), m.parameters(), m.lifecycle(), m.replacedBy(), m.notes()));
                    added++;
                }
            }
            for (QuotaEntry q : read("catalog/quota-plans.json", QuotaEntry[].class)) {
                if (!quotas.existsByKey(q.key())) {
                    quotas.save(new QuotaPlan(q.key(), q.name(), q.subject(), q.source(), q.windowKind(),
                            q.resetWeekday(), q.resetTime() == null ? null : LocalTime.parse(q.resetTime()),
                            q.resetZone(), q.limitNote(), q.usageUrl()));
                    added++;
                }
            }
            for (ResourceEntry r : read("catalog/resources.json", ResourceEntry[].class)) {
                if (!resources.existsByKey(r.key())) {
                    resources.save(new HarnessResource(r.key(), r.kind(), r.name(), r.description(), r.tags(),
                            r.sourceUrl(), r.searchUrl(), r.configuration()));
                    added++;
                }
            }
            if (added > 0) {
                log.info("Catalog bootstrap: {} new reference entries", added);
            }
        });
    }

    private <T> List<T> read(String resource, Class<T[]> type) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return List.of(json.readValue(in, type));
        } catch (IOException e) {
            throw new UncheckedIOException("Catalog " + resource + " is unreadable", e);
        }
    }
}

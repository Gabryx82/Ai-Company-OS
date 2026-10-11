package com.aicompany.backend.usage.dto;

import com.aicompany.backend.usage.model.QuotaPlan;
import com.aicompany.backend.usage.model.UsageSource;
import com.aicompany.backend.usage.model.WindowKind;
import com.aicompany.backend.usage.service.UsageService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The wire shapes of the usage view. */
public final class UsageDtos {

    private UsageDtos() {
    }

    public record ModelTokens(String model, long tokens) {
    }

    /** PHASE 33 (ADR-038): a model with its own quota; {@code resetsAt} is null once it has reset since. */
    public record ModelQuotaResponse(String model, double usedPercent, @Nullable Instant resetsAt,
                                     boolean resetSinceObservation) {
    }

    public record UsageWindowResponse(String key, String name, String subject, UsageSource source,
                                      WindowKind windowKind, UsageService.Status status, @Nullable Double usedPercent,
                                      @Nullable Long tokens, @Nullable Long outputTokens, @Nullable Instant windowStart, @Nullable Instant nextResetAt,
                                      @Nullable Instant observedAt, @Nullable String detail, List<ModelTokens> byModel,
                                      List<ModelQuotaResponse> modelQuotas,
                                      @Nullable Integer resetWeekday, @Nullable String resetTime, @Nullable String resetZone, @Nullable String limitNote,
                                      String usageUrl, long version) {

        public static UsageWindowResponse from(UsageService.Window w) {
            QuotaPlan p = w.plan();
            return new UsageWindowResponse(p.getKey(), p.getName(), p.getSubject(), p.getSource(), p.getWindowKind(),
                    w.status(), w.usedPercent(), w.tokens(), w.outputTokens(), w.windowStart(), w.nextResetAt(),
                    w.observedAt(), w.detail(),
                    w.byModel().stream().map(m -> new ModelTokens(m.model(), m.tokens())).toList(),
                    w.modelQuotas().stream().map(m -> new ModelQuotaResponse(m.model(), m.usedPercent(), m.resetsAt(),
                            m.resetSinceObservation())).toList(),
                    p.getResetWeekday(), p.getResetTime() == null ? null : p.getResetTime().toString(),
                    p.getResetZone(), p.getLimitNote(), p.getUsageUrl(), p.getVersion());
        }
    }

    public record QuotaAnchorRequest(@Min(1) @Max(7) Integer resetWeekday,
                                     @Size(max = 8) String resetTime,
                                     @Size(max = 64) String resetZone,
                                     @Size(max = 500) String limitNote) {
    }
}

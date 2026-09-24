package com.aicompany.backend.run;

import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.run.engine.EngineProperties;
import com.aicompany.backend.run.engine.HttpEngineClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The executor and the engine client (ADR-016 §3).
 *
 * <p>A bounded pool with a bounded queue: a burst of launches waits, and one
 * beyond the queue fails as {@code rejected} instead of growing memory without a
 * limit. Two threads by default -- a local model on one machine does not get
 * faster by being asked more things at once.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EngineProperties.class)
public class RunConfiguration {

    @Bean
    EngineClient engineClient(EngineProperties properties) {
        return new HttpEngineClient(properties);
    }

    @Bean(name = "runExecutor", destroyMethod = "shutdown")
    ThreadPoolTaskExecutor runExecutor(@Value("${aicos.runs.concurrency:2}") int concurrency,
                                       @Value("${aicos.runs.queue-capacity:50}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("run-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * PHASE 10 (ADR-021). Plan generations: one at a time, because a local
     * planner model is the heaviest thing this machine runs, and a queue small
     * enough that a stuck engine is noticed rather than buried.
     */
    @Bean(name = "planExecutor", destroyMethod = "shutdown")
    ThreadPoolTaskExecutor planExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("plan-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}

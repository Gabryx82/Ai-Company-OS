package com.aicompany.backend.support;

import com.aicompany.backend.run.engine.EngineClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Every Spring test talks to the scripted engine; no test reaches a real one. */
@TestConfiguration(proxyBeanMethods = false)
public class ScriptedEngineConfiguration {

    @Bean
    @Primary
    ScriptedEngineClient scriptedEngineClient() {
        return new ScriptedEngineClient();
    }
}

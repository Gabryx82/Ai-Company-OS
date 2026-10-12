package com.aicompany.backend.workspace.desktop;

import com.aicompany.backend.software.service.HostEnvironment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PHASE 33 (ADR-040): the desktop the file explorer uses. Tests replace it with a
 * {@code @Primary} fake, as they do the Software Hub's host beans.
 */
@Configuration(proxyBeanMethods = false)
public class DesktopConfiguration {

    @Bean
    DesktopShell desktopShell(HostEnvironment host, @Value("${aicos.launcher.enabled:true}") boolean launcherEnabled) {
        return new WindowsDesktopShell(host.windows(), launcherEnabled, host.variables());
    }
}

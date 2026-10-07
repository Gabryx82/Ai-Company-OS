package com.aicompany.backend.harness.hub;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SkillInstallationRepository extends JpaRepository<SkillInstallation, Long> {

    Optional<SkillInstallation> findByResourceId(Long resourceId);

    List<SkillInstallation> findByProviderAndReference(String provider, String reference);
}

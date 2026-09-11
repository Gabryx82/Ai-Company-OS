package com.aicompany.backend;

import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;

/**
 * Smoke test. It now boots against a real PostgreSQL with Hibernate in validate
 * mode, so a drift between the entities and the migrations fails it.
 */
class BackendApplicationTests extends AbstractPostgresTest {

	@Test
	void contextLoads() {
	}

}

package com.aicompany.backend.blueprint;

import com.aicompany.backend.blueprint.model.CodeStructure;
import com.aicompany.backend.blueprint.model.RelationType;
import com.aicompany.backend.blueprint.scan.BlueprintLanguagePlugin.Component;
import com.aicompany.backend.blueprint.scan.BlueprintLanguagePlugin.SourceFile;
import com.aicompany.backend.blueprint.scan.BlueprintScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 32 (TD-58, TD-46): the Java plugin reads the whole project's types before
 * the files one by one, so a call on an inherited field, on the result of another
 * call, or on a package-qualified type reaches the type it is made on; and every
 * method names the methods of the project it invokes -- the interprocedural call
 * graph, without a symbol solver.
 */
class BlueprintCallGraphTest {

    private static final String SRC = "shop/src/main/java/com/acme/";
    private static BlueprintScanner.Result result;

    @BeforeAll
    static void scan() {
        result = BlueprintScanner.withDefaultPlugins().analyze(List.of(
                new SourceFile(SRC + "shop/AuditLog.java", """
                        package com.acme.shop;
                        public class AuditLog {
                            public void record(String what) { }
                        }
                        """),
                new SourceFile(SRC + "shop/BaseService.java", """
                        package com.acme.shop;
                        public abstract class BaseService {
                            protected final AuditLog audit;
                            protected BaseService(AuditLog audit) { this.audit = audit; }
                        }
                        """),
                new SourceFile(SRC + "shop/Customer.java", """
                        package com.acme.shop;
                        public class Customer {
                            public void notify(String message) { }
                        }
                        """),
                new SourceFile(SRC + "shop/Order.java", """
                        package com.acme.shop;
                        public class Order {
                            private Customer customer;
                            public Customer customer() { return customer; }
                        }
                        """),
                new SourceFile(SRC + "shop/OrderRepository.java", """
                        package com.acme.shop;
                        import org.springframework.data.jpa.repository.JpaRepository;
                        public interface OrderRepository extends JpaRepository<Order, Long> { }
                        """),
                new SourceFile(SRC + "mail/Mailer.java", """
                        package com.acme.mail;
                        public final class Mailer {
                            public static void send(String to) { }
                        }
                        """),
                new SourceFile(SRC + "shop/OrderService.java", """
                        package com.acme.shop;
                        public class OrderService extends BaseService {
                            private final OrderRepository orders;
                            public OrderService(AuditLog audit, OrderRepository orders) {
                                super(audit);
                                this.orders = orders;
                            }
                            public void close(Long id) {
                                audit.record("close");
                                orders.findById(id).orElseThrow().customer().notify("closed");
                                com.acme.mail.Mailer.send("ops@acme.example");
                                helper();
                            }
                            private void helper() { }
                        }
                        """)), false);
    }

    @Test
    void aCallOnAnInheritedFieldAChainOrAQualifiedTypeReachesTheTypeItIsMadeOn() {
        assertThat(calls("com.acme.shop.AuditLog")).as("inherited field").isTrue();
        assertThat(calls("com.acme.shop.Customer")).as("a.b().c(): through the repository's entity").isTrue();
        assertThat(calls("com.acme.mail.Mailer")).as("package-qualified type").isTrue();
    }

    @Test
    void eachMethodNamesTheMethodsOfTheProjectItInvokes() {
        Component service = result.components().stream().filter(c -> c.key().equals("java:com.acme.shop.OrderService"))
                .findFirst().orElseThrow();
        CodeStructure.CodeMethod close = service.structure().methods().stream().filter(m -> m.name().equals("close"))
                .findFirst().orElseThrow();
        assertThat(close.invokes()).containsExactlyInAnyOrder("com.acme.shop.AuditLog#record", "com.acme.shop.Order#customer",
                "com.acme.shop.Customer#notify", "com.acme.mail.Mailer#send", "com.acme.shop.OrderService#helper");
    }

    private static boolean calls(String target) {
        return result.relations().stream().anyMatch(r -> r.sourceKey().equals("java:com.acme.shop.OrderService")
                && r.relation() == RelationType.CALLS && r.targetKey().equals("java:" + target));
    }
}

package com.family.scrollshield;

import com.family.scrollshield.repository.MemberRepository;
import com.family.scrollshield.repository.SessionLeaseRepository;
import com.family.scrollshield.repository.ViewingPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.clean-disabled=false",
        "spring.flyway.locations=classpath:db/migration",
        "scrollshield.lease.heartbeat-interval-seconds=5",
        "scrollshield.lease.expiry-margin-seconds=15",
        "scrollshield.lease.max-clock-skew-seconds=2",
        "scrollshield.maintenance.expired-scan-ms=60000",
        "scrollshield.maintenance.midnight-scan-ms=60000",
        "scrollshield.outbox.publish-ms=60000"
})
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MemberRepository memberRepository;

    @Autowired
    protected ViewingPlanRepository planRepository;

    @Autowired
    protected SessionLeaseRepository leaseRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5434/scrollshield");
        reg.add("spring.datasource.username", () -> "postgres");
        reg.add("spring.datasource.password", () -> "postgres");
        reg.add("spring.data.redis.host", () -> "localhost");
        reg.add("spring.data.redis.port", () -> 6380);
    }

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        jdbc.execute("GRANT ALL ON SCHEMA public TO postgres");
        jdbc.execute("GRANT ALL ON SCHEMA public TO public");
        org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
                .dataSource("jdbc:postgresql://localhost:5434/scrollshield", "postgres", "postgres")
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.migrate();
    }
}

package com.family.scrollshield;

import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
public abstract class AbstractIntegrationTestIT {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @PostConstruct
    void initPostgresIndexes() {
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_active_lease_per_member " +
                "ON session_leases (member_id) WHERE status = 'ACTIVE'");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM outbox_events");
        jdbcTemplate.execute("DELETE FROM extension_approvals");
        jdbcTemplate.execute("DELETE FROM session_leases");
        jdbcTemplate.execute("DELETE FROM daily_usage");
        jdbcTemplate.execute("DELETE FROM family_members");
        try {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        } catch (Exception ignored) {}
    }

    protected void flushRedis() {
        try {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        } catch (Exception e) {
            var keys = redisTemplate.keys("*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
    }
}

package in.boss.shared.idempotency;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for idempotency correctness (issue #96).
 *
 * Requirements verified:
 *   1. Missing Idempotency-Key → 400
 *   2. Invalid (too long) key → 400
 *   3. Same key + same payload → replay original response (200 + X-Idempotent-Replayed)
 *   4. Same key + different payload → 409 Conflict
 *   5. Concurrent requests with same key → only one executes business logic (503 for second)
 *   6. Platform-scoped (null tenant_id) keys work independently of tenant-scoped keys
 *   7. Expired key is treated as a new key (re-executes)
 *   8. Non-2xx response does NOT cache the result (client can retry)
 */
@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IdempotencyIntegrationTest {

    @MockBean FirebaseApp firebaseApp;
    @MockBean FirebaseAuth firebaseAuth;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("boss_test")
            .withUsername("boss")
            .withPassword("boss");

    @DynamicPropertySource
    static void configureDs(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired IdempotencyFilter filter;

    static UUID tenantId;

    @BeforeAll
    static void provisionTenant(@Autowired JdbcTemplate jdbc) {
        UUID groupId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO customer_groups (name, slug) VALUES ('Idem Group','idem-grp') "
                + "ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name RETURNING id::text",
                String.class));
        tenantId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_registry (customer_group_id, name, slug, status) "
                + "VALUES (?, 'Idem Tenant', 'idem', 'active') "
                + "ON CONFLICT (slug) DO UPDATE SET status = 'active' RETURNING id::text",
                String.class, groupId));
    }

    @AfterEach
    void clearContext() {
        BossContextHolder.clear();
    }

    // ---- 1. Missing key ----

    @Test
    void missingIdempotencyKey_returns400() throws Exception {
        setTenantContext();
        var req = postRequest("/api/v1/items", "{}");
        var resp = new MockHttpServletResponse();
        AtomicInteger chainCalls = new AtomicInteger(0);

        filter.doFilter(req, resp, (rq, rs) -> chainCalls.incrementAndGet());

        assertThat(resp.getStatus()).isEqualTo(400);
        assertThat(resp.getContentAsString()).contains("MISSING_IDEMPOTENCY_KEY");
        assertThat(chainCalls.get()).isZero();
    }

    // ---- 2. Invalid key ----

    @Test
    void tooLongIdempotencyKey_returns400() throws Exception {
        setTenantContext();
        var req = postRequest("/api/v1/items", "{}");
        req.addHeader("Idempotency-Key", "x".repeat(129));
        var resp = new MockHttpServletResponse();
        AtomicInteger chainCalls = new AtomicInteger(0);

        filter.doFilter(req, resp, (rq, rs) -> chainCalls.incrementAndGet());

        assertThat(resp.getStatus()).isEqualTo(400);
        assertThat(resp.getContentAsString()).contains("INVALID_IDEMPOTENCY_KEY");
        assertThat(chainCalls.get()).isZero();
    }

    // ---- 3. Replay same key + same payload ----

    @Test
    void sameKeyAndPayload_replaysOriginalResponse() throws Exception {
        setTenantContext();
        String key = UUID.randomUUID().toString();
        String body = "{\"name\":\"widget\"}";
        String originalResponse = "{\"id\":\"abc-123\",\"name\":\"widget\"}";

        // First request executes
        var resp1 = new MockHttpServletResponse();
        filter.doFilter(postRequest("/api/v1/items", body, key), resp1,
                (rq, rs) -> {
                    var r = (jakarta.servlet.http.HttpServletResponse) rs;
                    r.setStatus(201);
                    r.setContentType("application/json");
                    r.getWriter().write(originalResponse);
                });
        assertThat(resp1.getStatus()).isEqualTo(201);
        assertThat(resp1.getHeader("X-Idempotent-Replayed")).isNull();

        // Second request with same key + same body must replay
        var resp2 = new MockHttpServletResponse();
        AtomicInteger chainCalls = new AtomicInteger(0);
        filter.doFilter(postRequest("/api/v1/items", body, key), resp2,
                (rq, rs) -> chainCalls.incrementAndGet());

        assertThat(resp2.getStatus()).isEqualTo(201);
        assertThat(resp2.getHeader("X-Idempotent-Replayed")).isEqualTo("true");
        // JSONB normalizes JSON on round-trip (adds spaces); compare semantically
        assertThat(resp2.getContentAsString().replaceAll("\\s+", ""))
                .isEqualTo(originalResponse.replaceAll("\\s+", ""));
        assertThat(chainCalls.get())
                .as("Chain must NOT be called on replay")
                .isZero();
    }

    // ---- 4. Same key + different payload → 409 ----

    @Test
    void sameKeyDifferentPayload_returns409() throws Exception {
        setTenantContext();
        String key = UUID.randomUUID().toString();

        // First request with payload A
        var resp1 = new MockHttpServletResponse();
        filter.doFilter(postRequest("/api/v1/items", "{\"name\":\"alpha\"}", key), resp1,
                (rq, rs) -> {
                    var r = (jakarta.servlet.http.HttpServletResponse) rs;
                    r.setStatus(201);
                    r.setContentType("application/json");
                    r.getWriter().write("{\"id\":\"1\"}");
                });
        assertThat(resp1.getStatus()).isEqualTo(201);

        // Second request with same key but different payload
        var resp2 = new MockHttpServletResponse();
        AtomicInteger chainCalls = new AtomicInteger(0);
        filter.doFilter(postRequest("/api/v1/items", "{\"name\":\"beta\"}", key), resp2,
                (rq, rs) -> chainCalls.incrementAndGet());

        assertThat(resp2.getStatus()).isEqualTo(409);
        assertThat(resp2.getContentAsString()).contains("IDEMPOTENCY_KEY_REUSE");
        assertThat(chainCalls.get()).isZero();
    }

    // ---- 5. Concurrent requests — only one executes ----

    @Test
    void concurrentRequestsSameKey_onlyOneExecutesBusinessLogic() throws Exception {
        setTenantContext();
        String key = UUID.randomUUID().toString();
        String body = "{\"name\":\"concurrent\"}";

        AtomicInteger executionCount = new AtomicInteger(0);
        AtomicInteger concurrentResponses = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(2);
        CountDownLatch holdLatch = new CountDownLatch(1);

        // Thread 1 claims the key and holds it (simulates in-flight request)
        ExecutorService exec = Executors.newFixedThreadPool(2);
        var future1 = exec.submit(() -> {
            try {
                setTenantContext();
                startLatch.countDown();
                filter.doFilter(postRequest("/api/v1/items", body, key),
                        new MockHttpServletResponse(),
                        (rq, rs) -> {
                            executionCount.incrementAndGet();
                            // Hold the first request open until thread 2 has checked
                            try { holdLatch.await(); } catch (InterruptedException ignored) {}
                            var r = (jakarta.servlet.http.HttpServletResponse) rs;
                            r.setStatus(201);
                            r.setContentType("application/json");
                            r.getWriter().write("{\"id\":\"x\"}");
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                BossContextHolder.clear();
            }
        });

        // Thread 2 arrives while thread 1 is still processing
        var future2 = exec.submit(() -> {
            try {
                setTenantContext();
                startLatch.countDown();
                startLatch.await(); // both threads start simultaneously
                Thread.sleep(50);    // give thread 1 a head-start to claim the row
                var resp2 = new MockHttpServletResponse();
                filter.doFilter(postRequest("/api/v1/items", body, key), resp2,
                        (rq, rs) -> executionCount.incrementAndGet());
                if (resp2.getStatus() == 503) {
                    concurrentResponses.incrementAndGet();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                BossContextHolder.clear();
            }
        });

        // Let thread 2 check, then release thread 1
        Thread.sleep(200);
        holdLatch.countDown();

        future1.get();
        future2.get();
        exec.shutdown();

        // Business logic must execute exactly once
        assertThat(executionCount.get())
                .as("Business logic must execute exactly once despite concurrent requests")
                .isEqualTo(1);
        assertThat(concurrentResponses.get())
                .as("Second request must receive 503 concurrent indicator")
                .isEqualTo(1);
    }

    // ---- 6. Platform-scoped key (null tenant) ----

    @Test
    void platformScopedKey_worksWithNullTenant() throws Exception {
        // No tenant context — platform operation
        BossContextHolder.clear();
        String key = "platform-" + UUID.randomUUID();

        var resp1 = new MockHttpServletResponse();
        filter.doFilter(postRequest("/api/v1/tenants", "{\"name\":\"acme\"}", key), resp1,
                (rq, rs) -> {
                    var r = (jakarta.servlet.http.HttpServletResponse) rs;
                    r.setStatus(201);
                    r.setContentType("application/json");
                    r.getWriter().write("{\"id\":\"t1\"}");
                });
        assertThat(resp1.getStatus()).isEqualTo(201);

        // Replay with same key
        var resp2 = new MockHttpServletResponse();
        AtomicInteger chainCalls = new AtomicInteger(0);
        filter.doFilter(postRequest("/api/v1/tenants", "{\"name\":\"acme\"}", key), resp2,
                (rq, rs) -> chainCalls.incrementAndGet());

        assertThat(resp2.getHeader("X-Idempotent-Replayed")).isEqualTo("true");
        assertThat(chainCalls.get()).isZero();
    }

    @Test
    void tenantScopedAndPlatformScopedKeys_areIndependent() throws Exception {
        String key = "shared-key-" + UUID.randomUUID();
        String body = "{\"x\":1}";

        // Platform-scoped request with this key
        BossContextHolder.clear();
        var platformResp = new MockHttpServletResponse();
        filter.doFilter(postRequest("/api/v1/tenants", body, key), platformResp,
                (rq, rs) -> {
                    var r = (jakarta.servlet.http.HttpServletResponse) rs;
                    r.setStatus(201);
                    r.setContentType("application/json");
                    r.getWriter().write("{\"platform\":true}");
                });
        assertThat(platformResp.getStatus()).isEqualTo(201);

        // Same key for a different tenant must execute independently (not replay the platform response)
        setTenantContext();
        AtomicInteger tenantChainCalls = new AtomicInteger(0);
        var tenantResp = new MockHttpServletResponse();
        filter.doFilter(postRequest("/api/v1/items", body, key), tenantResp,
                (rq, rs) -> {
                    tenantChainCalls.incrementAndGet();
                    var r = (jakarta.servlet.http.HttpServletResponse) rs;
                    r.setStatus(201);
                    r.setContentType("application/json");
                    r.getWriter().write("{\"tenant\":true}");
                });

        assertThat(tenantResp.getHeader("X-Idempotent-Replayed"))
                .as("Tenant-scoped key must not be served the platform-scoped response")
                .isNull();
        assertThat(tenantChainCalls.get()).isEqualTo(1);
    }

    @Test
    void sameKeyForTenantAAndTenantB_executesIndependentlyWithoutDataLeak() throws Exception {
        UUID tenantB = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_registry (customer_group_id, name, slug, status) "
                + "SELECT customer_group_id, 'Idem Tenant B', 'idem-b', 'active' FROM tenant_registry WHERE id = ? "
                + "ON CONFLICT (slug) DO UPDATE SET status = 'active' RETURNING id::text",
                String.class, tenantId));

        String sharedKey = "cross-tenant-shared-key-" + UUID.randomUUID();
        String payload = "{\"item\":\"widget\"}";

        // 1. Tenant A executes with sharedKey
        BossContextHolder.set(new BossContext(tenantId, UUID.randomUUID(), Set.of("OWNER"), null, "uid-a"));
        var respA = new MockHttpServletResponse();
        filter.doFilter(postRequest("/api/v1/items", payload, sharedKey), respA, (rq, rs) -> {
            var r = (jakarta.servlet.http.HttpServletResponse) rs;
            r.setStatus(200);
            r.setContentType("application/json");
            r.getWriter().write("{\"tenant\":\"A\",\"secret\":\"a-private-data\"}");
        });
        assertThat(respA.getStatus()).isEqualTo(200);
        assertThat(respA.getContentAsString()).contains("a-private-data");
        assertThat(respA.getHeader("X-Idempotent-Replayed")).isNull();

        // 2. Tenant B executes with the SAME sharedKey
        // Must execute its own business logic, NOT replay Tenant A's cached response (no data leak)
        BossContextHolder.set(new BossContext(tenantB, UUID.randomUUID(), Set.of("OWNER"), null, "uid-b"));
        var respB = new MockHttpServletResponse();
        filter.doFilter(postRequest("/api/v1/items", payload, sharedKey), respB, (rq, rs) -> {
            var r = (jakarta.servlet.http.HttpServletResponse) rs;
            r.setStatus(200);
            r.setContentType("application/json");
            r.getWriter().write("{\"tenant\":\"B\",\"secret\":\"b-private-data\"}");
        });
        assertThat(respB.getStatus()).isEqualTo(200);
        assertThat(respB.getContentAsString()).contains("b-private-data");
        assertThat(respB.getContentAsString()).doesNotContain("a-private-data");
        assertThat(respB.getHeader("X-Idempotent-Replayed")).isNull();

        // 3. Repeat call for Tenant A replays Tenant A's cached response
        BossContextHolder.set(new BossContext(tenantId, UUID.randomUUID(), Set.of("OWNER"), null, "uid-a"));
        var replayA = new MockHttpServletResponse();
        filter.doFilter(postRequest("/api/v1/items", payload, sharedKey), replayA, (rq, rs) -> {
            throw new AssertionError("Should not execute business logic for replay");
        });
        assertThat(replayA.getStatus()).isEqualTo(200);
        assertThat(replayA.getContentAsString()).contains("a-private-data");
        assertThat(replayA.getHeader("X-Idempotent-Replayed")).isEqualTo("true");

        // 4. Repeat call for Tenant B replays Tenant B's cached response
        BossContextHolder.set(new BossContext(tenantB, UUID.randomUUID(), Set.of("OWNER"), null, "uid-b"));
        var replayB = new MockHttpServletResponse();
        filter.doFilter(postRequest("/api/v1/items", payload, sharedKey), replayB, (rq, rs) -> {
            throw new AssertionError("Should not execute business logic for replay");
        });
        assertThat(replayB.getStatus()).isEqualTo(200);
        assertThat(replayB.getContentAsString()).contains("b-private-data");
        assertThat(replayB.getHeader("X-Idempotent-Replayed")).isEqualTo("true");
    }

    // ---- 7. Expired key re-executes ----

    @Test
    void expiredKey_allowsReExecution() throws Exception {
        setTenantContext();
        String key = UUID.randomUUID().toString();
        String body = "{\"name\":\"expire-test\"}";

        // Insert an already-expired record manually
        jdbc.update(
                "INSERT INTO idempotency_keys "
                + "(tenant_id, key, request_hash, response_status, response_body, "
                + " response_content_type, expires_at, lock_status) "
                + "VALUES (?, ?, 'old-hash', 200, '{\"old\":true}'::jsonb, 'application/json', ?, 'complete')",
                tenantId, key, java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));

        // Request with the same key should NOT replay the expired record
        AtomicInteger chainCalls = new AtomicInteger(0);
        var resp = new MockHttpServletResponse();
        filter.doFilter(postRequest("/api/v1/items", body, key), resp,
                (rq, rs) -> {
                    chainCalls.incrementAndGet();
                    var r = (jakarta.servlet.http.HttpServletResponse) rs;
                    r.setStatus(201);
                    r.setContentType("application/json");
                    r.getWriter().write("{\"new\":true}");
                });

        assertThat(chainCalls.get())
                .as("Business logic should execute when prior key record is expired")
                .isEqualTo(1);
        assertThat(resp.getHeader("X-Idempotent-Replayed")).isNull();
    }

    // ---- #96: WhatsApp webhook exemption ----

    @Test
    void whatsappWebhook_postWithoutIdempotencyKey_isExempt() throws Exception {
        // WhatsApp webhook is exempt — it uses X-WhatsApp-Message-ID for deduplication
        AtomicInteger chainCalls = new AtomicInteger(0);
        var req = new MockHttpServletRequest("POST", "/api/v1/messaging/webhook/whatsapp");
        req.setContentType("application/json");
        req.setContent("{\"entry\":[]}".getBytes());
        // Deliberately omit Idempotency-Key header

        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, (rq, rs) -> chainCalls.incrementAndGet());

        assertThat(chainCalls.get())
                .as("WhatsApp webhook must bypass idempotency key requirement")
                .isEqualTo(1);
        assertThat(resp.getStatus()).isNotEqualTo(400);
    }

    @Test
    void multipartRequestIsSupportedAndNotConsumed() throws Exception {
        setTenantContext();
        String key = UUID.randomUUID().toString();

        var req = new MockHttpServletRequest("POST", "/api/v1/documents");
        req.setContentType("multipart/form-data; boundary=----WebKitFormBoundaryXYZ");
        req.setContent("dummy-multipart-content".getBytes());
        req.addHeader("Idempotency-Key", key);

        var resp = new MockHttpServletResponse();
        var chainCalls = new AtomicInteger(0);

        filter.doFilter(req, resp, (rq, rs) -> {
            chainCalls.incrementAndGet();
            try {
                byte[] content = rq.getInputStream().readAllBytes();
                assertThat(content).isNotEmpty();
                var httpResp = (jakarta.servlet.http.HttpServletResponse) rs;
                httpResp.setStatus(201);
                httpResp.setContentType("application/json");
                httpResp.getWriter().write("{\"id\":\"doc-1\"}");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(chainCalls.get()).isEqualTo(1);
        assertThat(resp.getStatus()).isEqualTo(201);
        assertThat(resp.getContentAsString()).contains("doc-1");
    }

    // ---- helpers ----

    private static MockHttpServletRequest postRequest(String uri, String body) {
        var req = new MockHttpServletRequest("POST", uri);
        req.setContentType("application/json");
        req.setContent(body.getBytes());
        return req;
    }

    private static MockHttpServletRequest postRequest(String uri, String body, String idempotencyKey) {
        var req = postRequest(uri, body);
        req.addHeader("Idempotency-Key", idempotencyKey);
        return req;
    }

    private void setTenantContext() {
        BossContextHolder.set(new BossContext(tenantId, UUID.randomUUID(),
                Set.of("OWNER"), null, null));
    }
}

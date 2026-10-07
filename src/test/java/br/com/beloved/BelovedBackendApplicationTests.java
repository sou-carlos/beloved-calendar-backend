package br.com.beloved;

import br.com.beloved.user.UserRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BelovedBackendApplicationTests {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}")
    int port;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired jakarta.servlet.ServletContext servletContext;

    private CookieManager cookies;
    private HttpClient client;

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1").start();
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @AfterAll
    static void closeDatabase() throws IOException { POSTGRES.close(); }

    @BeforeEach
    void setup() {
        users.deleteAll();
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    @Test
    void registerPersistsHashedPasswordAndRestoresSession() throws Exception {
        assertThat(get("/me").statusCode()).isEqualTo(401);
        var token = csrf();
        var oldSession = sessionId();
        var response = post("/register", registration("  EXPLORER@example.com  "), token);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).doesNotContain("password", "passwordHash", "segredo123");
        assertThat(json(response).get("email").asText()).isEqualTo("explorer@example.com");
        assertThat(json(response).get("name").asText()).isEqualTo("Scot Explorador");
        assertThat(sessionId()).isNotEqualTo(oldSession);
        assertThat(response.headers().allValues("Set-Cookie").toString().toLowerCase()).contains("httponly", "samesite=lax");
        var persisted = users.findByEmail("explorer@example.com").orElseThrow();
        assertThat(persisted.getPasswordHash()).isNotEqualTo("segredo123");
        assertThat(passwords.matches("segredo123", persisted.getPasswordHash())).isTrue();
        assertThat(get("/me").statusCode()).isEqualTo(200);
        assertThat(json(get("/me")).get("id").asText()).isEqualTo(persisted.getId().toString());
    }

    @Test
    void loginAndLogoutRotateCsrfAndInvalidateSession() throws Exception {
        assertThat(post("/register", registration("scot@example.com"), csrf()).statusCode()).isEqualTo(201);
        assertThat(post("/logout", Map.of(), csrf()).statusCode()).isEqualTo(204);
        assertThat(get("/me").statusCode()).isEqualTo(401);
        var beforeLogin = csrf();
        var login = post("/login", Map.of("email", " SCOT@example.com ", "password", "segredo123"), beforeLogin);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(get("/me").statusCode()).isEqualTo(200);
        assertThat(post("/logout", Map.of(), beforeLogin).statusCode()).isEqualTo(403);
        assertThat(post("/logout", Map.of(), csrf()).statusCode()).isEqualTo(204);
        assertThat(get("/me").statusCode()).isEqualTo(401);
    }

    @Test
    void persistentLoginSurvivesNewClientAndLogoutRevokesSavedCookie() throws Exception {
        assertThat(servletContext.getSessionTimeout()).isEqualTo(30 * 24 * 60);
        assertThat(post("/register", registration("persistent@example.com"), csrf()).statusCode()).isEqualTo(201);
        assertThat(post("/logout", Map.of(), csrf()).statusCode()).isEqualTo(204);
        var login = post("/login", registration("persistent@example.com"), csrf());
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().allValues("Set-Cookie").toString().toLowerCase())
            .contains("max-age=2592000", "httponly", "samesite=lax");
        var savedCookie = "BELOVED_SESSION=" + sessionId();
        // A reopened browser restores only its persistent cookie, with no client authentication state.
        var reopened = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(uri("/me")).header("Cookie", savedCookie).GET().build();
        assertThat(reopened.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(post("/logout", Map.of(), csrf()).statusCode()).isEqualTo(204);
        // Even a retained copy of the persistent cookie must stop working after logout.
        assertThat(reopened.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsDuplicateNormalizedEmail() throws Exception {
        assertThat(post("/register", registration("scot@example.com"), csrf()).statusCode()).isEqualTo(201);
        var duplicate = post("/register", registration(" SCOT@EXAMPLE.COM "), csrf());
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void validatesRegistrationAndRejectsMalformedJson() throws Exception {
        var response = post("/register", Map.of("name", " ", "email", "invalid", "password", "123"), csrf());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("fieldErrors").has("name")).isTrue();
        assertThat(json(response).get("fieldErrors").has("email")).isTrue();
        assertThat(json(response).get("fieldErrors").has("password")).isTrue();
        assertThat(users.count()).isZero();
        var token = csrf();
        var malformed = client.send(HttpRequest.newBuilder(uri("/register"))
            .header("Content-Type", "application/json")
            .header(token.get("headerName").asText(), token.get("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString("{broken")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(malformed.statusCode()).isEqualTo(400);
    }

    @Test
    void wrongPasswordAndUnknownAccountReturnSameError() throws Exception {
        assertThat(post("/register", registration("scot@example.com"), csrf()).statusCode()).isEqualTo(201);
        post("/logout", Map.of(), csrf());
        var wrong = post("/login", Map.of("email", "scot@example.com", "password", "senhaErrada"), csrf());
        var unknown = post("/login", Map.of("email", "missing@example.com", "password", "senhaErrada"), csrf());
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).isEqualTo(unknown.body());
        assertThat(get("/me").statusCode()).isEqualTo(401);
    }

    @Test
    void requiresCsrfForLoginRegistrationAndLogout() throws Exception {
        for (String endpoint : new String[]{"/login", "/register", "/logout"}) {
            assertThat(post(endpoint, registration("scot@example.com"), null).statusCode()).isEqualTo(403);
        }
        assertThat(users.count()).isZero();
    }

    @Test
    void allowsOnlyConfiguredCorsOrigins() throws Exception {
        var allowed = client.send(HttpRequest.newBuilder(uri("/login"))
            .header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "POST")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");
        var denied = client.send(HttpRequest.newBuilder(uri("/login"))
            .header("Origin", "https://untrusted.example")
            .header("Access-Control-Request-Method", "POST")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(403);
    }


    @Test
    void syncPersistsGiftsRetriesAndDeletionWithoutOverwritingConflicts() throws Exception {
        var account = json(post("/register", registration("sync@example.com"), csrf())).get("id").asText();
        var recordId = java.util.UUID.randomUUID().toString();
        var person = Map.of("id", recordId, "name", "Marina", "birthDate", "2000-02-29", "gifts",
            java.util.List.of(Map.of("id", "gift-1", "title", "Livro", "purchased", true)));
        var write = change(account, recordId, 0, person);
        assertThat(syncPost(write, null).statusCode()).isEqualTo(403);
        var first = syncPost(write, csrf());
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(json(first).get("status").asText()).isEqualTo("accepted");
        assertThat(json(first).get("record").get("version").asLong()).isEqualTo(1);
        assertThat(json(syncPost(write, csrf()))).isEqualTo(json(first));
        var alteredRetry = new java.util.LinkedHashMap<>(write);
        alteredRetry.put("person", Map.of("id", recordId, "name", "Outro", "birthDate", "2000-02-29", "gifts", java.util.List.of()));
        assertThat(syncPost(alteredRetry, csrf()).statusCode()).isEqualTo(409);
        var conflict = json(syncPost(change(account, recordId, 0, person), csrf()));
        assertThat(conflict.get("status").asText()).isEqualTo("conflict");
        assertThat(conflict.get("record").get("person").get("gifts").get(0).get("purchased").asBoolean()).isTrue();
        var deleted = json(syncPost(change(account, recordId, 1, null), csrf()));
        assertThat(deleted.get("record").get("version").asLong()).isEqualTo(2);
        assertThat(deleted.get("record").get("person").isNull()).isTrue();
        // An old device cannot resurrect a deletion silently.
        assertThat(json(syncPost(change(account, recordId, 1, person), csrf())).get("status").asText()).isEqualTo("conflict");
        assertThat(json(syncGet(account)).get("records").get(0).get("person").isNull()).isTrue();
        post("/logout", Map.of(), csrf());
        post("/login", registration("sync@example.com"), csrf());
        assertThat(json(syncGet(account)).get("records").get(0).get("version").asLong()).isEqualTo(2);
    }

    @Test
    void syncChecksOwnershipAndValidatesRecords() throws Exception {
        assertThat(syncGet(java.util.UUID.randomUUID().toString()).statusCode()).isEqualTo(401);
        var account = json(post("/register", registration("owner@example.com"), csrf())).get("id").asText();
        var id = "legacy-id";
        assertThat(syncPost(change(account, id, 0, Map.of("id", id, "name", "Amigo", "birthDate", "2000-02-30", "gifts", java.util.List.of())), csrf()).statusCode()).isEqualTo(400);
        assertThat(syncPost(change(account, id, 0, Map.of("id", "different", "name", "Amigo", "birthDate", "2000-02-28", "gifts", java.util.List.of())), csrf()).statusCode()).isEqualTo(400);
        assertThat(syncPost(change(account, id, 0, Map.of("id", id, "name", " ", "birthDate", "2000-02-28", "gifts", java.util.List.of())), csrf()).statusCode()).isEqualTo(400);
        assertThat(syncPost(change(account, id, 0, Map.of("id", id, "name", "Amigo", "birthDate", "2000-02-28", "gifts", java.util.List.of())), csrf()).statusCode()).isEqualTo(200);
        post("/logout", Map.of(), csrf());
        var other = json(post("/register", registration("other-sync@example.com"), csrf())).get("id").asText();
        assertThat(syncGet(account).statusCode()).isEqualTo(409);
        assertThat(syncPost(change(account, id, 1, null), csrf()).statusCode()).isEqualTo(409);
        assertThat(json(syncGet(other)).get("records").size()).isZero();
    }

    @Test
    void syncKeepsEveryAvatarChoiceAndUnknownYear() throws Exception {
        var account = json(post("/register", registration("avatar@example.com"), csrf())).get("id").asText();
        var id = java.util.UUID.randomUUID().toString();
        var avatar = Map.of("hair", "bob", "hairColor", "auburn", "skin", "tan", "eyes", "green", "shirt", "mustard", "age", "old",
            "hat", "straw", "earrings", "hoop", "glasses", "round", "beard", "lumberjack");
        assertThat(json(syncPost(change(account, id, 0, person(id, "2000-02-29", true, avatar)), csrf())).get("status").asText()).isEqualTo("accepted");
        var saved = json(syncGet(account)).get("records").get(0).get("person");
        assertThat(saved.get("yearUnknown").asBoolean()).isTrue();
        for (var entry : avatar.entrySet()) assertThat(saved.get("avatar").get(entry.getKey()).asText()).isEqualTo(entry.getValue());
    }

    @Test
    void syncAcceptsRecordsFromOlderClients() throws Exception {
        var account = json(post("/register", registration("older@example.com"), csrf())).get("id").asText();
        var id = java.util.UUID.randomUUID().toString();
        // No avatar and no year flag at all, as saved before this feature.
        assertThat(json(syncPost(change(account, id, 0, Map.of("id", id, "name", "Lia", "birthDate", "1990-05-01", "gifts", java.util.List.of())), csrf()))
            .get("status").asText()).isEqualTo("accepted");
        // A face saved before the optional choices existed.
        var firstFace = Map.of("hair", "bob", "hairColor", "auburn", "skin", "tan", "eyes", "green");
        assertThat(json(syncPost(change(account, id, 1, person(id, "1990-05-01", false, firstFace)), csrf())).get("status").asText()).isEqualTo("accepted");
        // An id this server has never heard of, e.g. from a newer app version, is kept as is.
        var newerFace = Map.of("hair", "future-style", "hairColor", "auburn", "skin", "tan", "eyes", "green");
        assertThat(json(syncPost(change(account, id, 2, person(id, "1990-05-01", false, newerFace)), csrf())).get("record").get("person").get("avatar").get("hair").asText())
            .isEqualTo("future-style");
    }

    @Test
    void syncDropsAStaleUnknownYearFlag() throws Exception {
        var account = json(post("/register", registration("stale@example.com"), csrf())).get("id").asText();
        var id = java.util.UUID.randomUUID().toString();
        // An older client edited the date to a real year but kept the flag from before.
        var result = json(syncPost(change(account, id, 0, person(id, "1995-03-07", true, null)), csrf()));
        assertThat(result.get("record").get("person").get("yearUnknown").isNull()).isTrue();
        assertThat(json(syncGet(account)).get("records").get(0).get("person").get("yearUnknown").isNull()).isTrue();
    }

    @Test
    void syncRejectsMalformedAvatars() throws Exception {
        var account = json(post("/register", registration("malformed@example.com"), csrf())).get("id").asText();
        var id = java.util.UUID.randomUUID().toString();
        var markup = Map.of("hair", "<b>", "hairColor", "auburn", "skin", "tan", "eyes", "green");
        assertThat(syncPost(change(account, id, 0, person(id, "2000-02-29", false, markup)), csrf()).statusCode()).isEqualTo(400);
        var missingRequired = Map.of("hair", "bob", "hairColor", "auburn", "skin", "tan");
        assertThat(syncPost(change(account, id, 0, person(id, "2000-02-29", false, missingRequired)), csrf()).statusCode()).isEqualTo(400);
        var tooLong = Map.of("hair", "x".repeat(33), "hairColor", "auburn", "skin", "tan", "eyes", "green");
        assertThat(syncPost(change(account, id, 0, person(id, "2000-02-29", false, tooLong)), csrf()).statusCode()).isEqualTo(400);
    }

    private Map<String, Object> person(String id, String birthDate, boolean yearUnknown, Map<String, String> avatar) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("id", id); body.put("name", "Lia"); body.put("birthDate", birthDate); body.put("gifts", java.util.List.of());
        if (yearUnknown) body.put("yearUnknown", true);
        if (avatar != null) body.put("avatar", avatar);
        return body;
    }

    @Test
    void concurrentDevicesCannotOverwriteTheSameVersion() throws Exception {
        var account = json(post("/register", registration("concurrent@example.com"), csrf())).get("id").asText();
        var token = csrf();
        var one = change(account, "same-id", 0, Map.of("id", "same-id", "name", "Versão A", "birthDate", "2000-01-01", "gifts", java.util.List.of()));
        var two = change(account, "same-id", 0, Map.of("id", "same-id", "name", "Versão B", "birthDate", "2000-01-01", "gifts", java.util.List.of()));
        var a = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return json(syncPost(one, token)).get("status").asText(); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        var b = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return json(syncPost(two, token)).get("status").asText(); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        assertThat(java.util.List.of(a.get(), b.get())).containsExactlyInAnyOrder("accepted", "conflict");
        assertThat(json(syncGet(account)).get("records").get(0).get("version").asLong()).isEqualTo(1);
    }

    private Map<String, Object> change(String account, String id, long version, Object person) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("accountId", account); body.put("id", id); body.put("baseVersion", version);
        body.put("operationId", java.util.UUID.randomUUID().toString()); body.put("person", person);
        return body;
    }

    private HttpResponse<String> syncGet(String account) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sync?accountId=" + account)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> syncPost(Object body, JsonNode token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sync")).header("Content-Type", "application/json");
        if (token != null) request.header(token.get("headerName").asText(), token.get("token").asText());
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }


    private Map<String, String> registration(String email) {
        return Map.of("name", "  Scot Explorador  ", "email", email, "password", "segredo123");
    }

    private URI uri(String endpoint) { return URI.create("http://localhost:" + port + "/api/auth" + endpoint); }

    private HttpResponse<String> get(String endpoint) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(endpoint)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode csrf() throws Exception { return json(get("/csrf")); }

    private String sessionId() {
        return cookies.getCookieStore().getCookies().stream()
            .filter(cookie -> cookie.getName().equals("BELOVED_SESSION")).findFirst().orElseThrow().getValue();
    }

    private HttpResponse<String> post(String endpoint, Object body, JsonNode token) throws Exception {
        var request = HttpRequest.newBuilder(uri(endpoint)).header("Content-Type", "application/json");
        if (token != null) request.header(token.get("headerName").asText(), token.get("token").asText());
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) { return JSON.readTree(response.body()); }
}

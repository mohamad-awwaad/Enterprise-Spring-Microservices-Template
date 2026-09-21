package com.example.common.test;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * A real Keycloak server, pre-loaded with this repo's {@code keycloak/realm-config/realm-export.json},
 * for hermetic integration tests that need a working OAuth2/OIDC provider (token issuance, JWKS,
 * the admin REST API) without depending on the Keycloak instance in the local dev {@code docker
 * compose} stack.
 * <p>
 * Started once per JVM as a singleton (the "singleton container" Testcontainers pattern): every
 * test class that calls {@link #getInstance()} shares the same running container instead of each
 * paying Keycloak's ~40-60s startup cost. The container is never stopped explicitly - Testcontainers'
 * Ryuk reaper tears it down when the JVM exits.
 */
public final class KeycloakTestContainer extends GenericContainer<KeycloakTestContainer> {

    private static final Logger log = LoggerFactory.getLogger(KeycloakTestContainer.class);

    private static final String IMAGE = "quay.io/keycloak/keycloak:26.7.4";
    private static final int KEYCLOAK_PORT = 8080;
    private static final String REALM = "my-realm";
    private static final String REALM_EXPORT_RELATIVE_PATH = "keycloak/realm-config/realm-export.json";
    private static final String REALM_EXPORT_CONTAINER_PATH = "/opt/keycloak/data/import/realm-export.json";

    /** Confidential client in the realm export dedicated to tests (password grant enabled). */
    private static final String TEST_CLIENT_ID = "test-client";
    private static final String TEST_CLIENT_SECRET = "test-secret";

    private static volatile KeycloakTestContainer instance;

    private KeycloakTestContainer() {
        super(IMAGE);
        withCommand("start-dev", "--import-realm");
        withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin");
        withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin");
        withEnv("KC_HEALTH_ENABLED", "true");
        withExposedPorts(KEYCLOAK_PORT);
        withCopyFileToContainer(MountableFile.forHostPath(resolveRealmExportFile()), REALM_EXPORT_CONTAINER_PATH);
        withLogConsumer(new Slf4jLogConsumer(log).withPrefix("keycloak"));
        waitingFor(Wait.forHttp("/realms/" + REALM)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(3)));
    }

    /**
     * Returns the shared, already-started container, starting it on first use.
     */
    public static KeycloakTestContainer getInstance() {
        KeycloakTestContainer result = instance;
        if (result == null) {
            synchronized (KeycloakTestContainer.class) {
                result = instance;
                if (result == null) {
                    result = new KeycloakTestContainer();
                    result.start();
                    instance = result;
                }
            }
        }
        return result;
    }

    /** Base URL of the realm, e.g. {@code http://localhost:32768/realms/my-realm}. */
    public String issuerUri() {
        return serverUrl() + "/realms/" + REALM;
    }

    /** Base URL of the Keycloak server itself, e.g. {@code http://localhost:32768}. */
    public String serverUrl() {
        return "http://" + getHost() + ":" + getMappedPort(KEYCLOAK_PORT);
    }

    /**
     * Obtains an access token via the OAuth2 password grant, using the realm's {@code test-client}
     * (the only client in the realm export with {@code directAccessGrantsEnabled=true} - real
     * clients like {@code bff-client} only support the authorization_code flow).
     */
    public String passwordGrantToken(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", TEST_CLIENT_ID);
        form.add("client_secret", TEST_CLIENT_SECRET);
        form.add("grant_type", "password");
        form.add("username", username);
        form.add("password", password);
        return requestToken(form);
    }

    /**
     * Obtains an access token via the OAuth2 client-credentials grant for the given client's
     * service account (e.g. {@code internal-client} or {@code admin-service-client}).
     */
    public String clientCredentialsToken(String clientId, String clientSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("grant_type", "client_credentials");
        return requestToken(form);
    }

    private String requestToken(MultiValueMap<String, String> form) {
        TokenResponse response = RestClient.create()
                .post()
                .uri(issuerUri() + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("Keycloak token endpoint returned no access_token for client_id=" + form.getFirst("client_id"));
        }
        return response.accessToken();
    }

    /**
     * Walks up from the current working directory looking for {@code keycloak/realm-config/realm-export.json}.
     * Maven runs the forked test JVM with the module's own directory as the working directory (e.g.
     * {@code <repo>/bff}), but this must also work when the working directory already is the repo
     * root, so every ancestor directory is checked.
     */
    private static Path resolveRealmExportFile() {
        Path dir = Path.of("").toAbsolutePath();
        Path start = dir;
        for (int depth = 0; depth < 8 && dir != null; depth++) {
            Path candidate = dir.resolve(REALM_EXPORT_RELATIVE_PATH);
            if (Files.isRegularFile(candidate)) {
                log.debug("Resolved realm export at {}", candidate);
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not locate " + REALM_EXPORT_RELATIVE_PATH + " by walking up from " + start
                        + " - is common-test being run from within the enterprise-spring-microservices-template repo?");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }
}

package com.example.common.security.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Supplies the resource-server properties every service needs, so a service does not have to
 * repeat them in its own {@code application.properties}:
 * <pre>
 * spring.security.oauth2.resourceserver.jwt.issuer-uri = ${KEYCLOAK_ISSUER_URI:http://localhost:8080/realms/my-realm}
 * spring.security.oauth2.resourceserver.jwt.audiences  = ${API_AUDIENCE:template-api}
 * </pre>
 * A library cannot ship an {@code application.properties} (the application's own file would
 * shadow it), and an auto-configuration class registered in {@code AutoConfiguration.imports}
 * only contributes beans, not properties. An {@link EnvironmentPostProcessor} is the hook Spring
 * Boot offers for a library to add configuration; it is registered in
 * {@code META-INF/spring.factories}.
 * <p>
 * The property source is added <em>last</em>, so anything a service sets itself (its
 * {@code application.properties}, environment variables, system properties, test properties)
 * still wins. The values keep their {@code ${VAR:default}} placeholders; Spring resolves them
 * when the property is read.
 */
public class ResourceServerDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "commonSecurityResourceServerDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                "${KEYCLOAK_ISSUER_URI:http://localhost:8080/realms/my-realm}",
                "spring.security.oauth2.resourceserver.jwt.audiences",
                "${API_AUDIENCE:template-api}")));
    }
}

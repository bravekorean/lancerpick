package com.example.lancerpick.support;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestDatabaseInitializerTests {

    @Test
    void testProfileLoadsCommonJpaSettingsAndOnlyTestCredentials() {
        var environment = new MockEnvironment()
                .withProperty("TEST_DB_URL", "jdbc:mysql://localhost:3306/lancerpick_test")
                .withProperty("TEST_DB_USERNAME", "test-only-user")
                .withProperty("TEST_DB_PASSWORD", "test-only-password")
                .withProperty("DB_URL", "jdbc:mysql://localhost:3306/lancerpick")
                .withProperty("DB_USERNAME", "development-user")
                .withProperty("DB_PASSWORD", "development-password");
        environment.setActiveProfiles("test");

        try (var context = new GenericApplicationContext()) {
            context.setEnvironment(environment);
            new ConfigDataApplicationContextInitializer().initialize(context);

            assertEquals("jdbc:mysql://localhost:3306/lancerpick_test",
                    environment.getProperty("spring.datasource.url"));
            assertEquals("test-only-user", environment.getProperty("spring.datasource.username"));
            assertEquals("test-only-password", environment.getProperty("spring.datasource.password"));
            assertEquals("false", environment.getProperty("spring.jpa.open-in-view"));
            assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
            assertEquals("UTC", environment.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone"));
            assertEquals("true", environment.getProperty("spring.flyway.enabled"));
            assertNull(environment.getProperty("spring.config.import"));
        }
    }

    @Test
    void missingTestCredentialsDoNotFallBackToDevelopmentCredentials() {
        var environment = new MockEnvironment()
                .withProperty("DB_USERNAME", "development-user")
                .withProperty("DB_PASSWORD", "development-password");
        environment.setActiveProfiles("test");

        try (var context = new GenericApplicationContext()) {
            context.setEnvironment(environment);
            new ConfigDataApplicationContextInitializer().initialize(context);

            assertThrows(IllegalArgumentException.class,
                    () -> environment.getProperty("spring.datasource.username"));
            assertThrows(IllegalArgumentException.class,
                    () -> environment.getProperty("spring.datasource.password"));
        }
    }

    @Test
    void acceptsDedicatedLocalAndCiDatabases() {
        assertDoesNotThrow(() -> TestDatabaseInitializer.validateUrl(
                "jdbc:mysql://localhost:3306/lancerpick_test?connectionTimeZone=UTC"));
        assertDoesNotThrow(() -> TestDatabaseInitializer.validateUrl(
                "jdbc:mysql://127.0.0.1:3306/lancerpick_ci?connectionTimeZone=UTC&sslMode=DISABLED"));
    }

    @Test
    void rejectsDevelopmentDatabaseAndMisleadingNames() {
        for (String url : new String[] {
                "jdbc:mysql://localhost:3306/lancerpick",
                "jdbc:mysql://localhost:3306/lancerpick?test=lancerpick_test",
                "jdbc:mysql://localhost:3306/lancerpick_test_backup",
                "jdbc:mysql://localhost:3306/lancerpick_test/other",
                "jdbc:h2:mem:lancerpick_test", "", null
        }) {
            assertThrows(IllegalStateException.class, () -> TestDatabaseInitializer.validateUrl(url));
        }
    }

    @Test
    void initializerRejectsEffectiveDatasourceOverrideWithoutExposingCredentials() {
        try (var context = new GenericApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test-override", Map.of("spring.datasource.url",
                    "jdbc:mysql://localhost:3306/lancerpick?password=hidden-password")));

            var exception = assertThrows(IllegalStateException.class,
                    () -> new TestDatabaseInitializer().initialize(context));

            assertFalse(exception.getMessage().contains("hidden-password"));
            assertFalse(context.isActive());
        }
    }
}

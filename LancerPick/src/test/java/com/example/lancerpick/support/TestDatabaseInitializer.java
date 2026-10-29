package com.example.lancerpick.support;

import java.util.regex.Pattern;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Reject a development database before the datasource and Flyway are started. */
public class TestDatabaseInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Pattern TEST_DATABASE_URL = Pattern.compile(
            "jdbc:mysql://[a-zA-Z0-9.\\-]+(?::[0-9]+)?/lancerpick_(?:test|ci)(?:\\?[^#]*)?");

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        validateUrl(context.getEnvironment().getRequiredProperty("spring.datasource.url"));
    }

    static void validateUrl(String url) {
        if (url == null || !TEST_DATABASE_URL.matcher(url).matches()) {
            // Do not include the supplied URL: it may contain credentials.
            throw new IllegalStateException(
                    "Integration tests require a MySQL database named lancerpick_test or lancerpick_ci. "
                            + "Configure TEST_DB_URL, TEST_DB_USERNAME and TEST_DB_PASSWORD.");
        }
    }
}

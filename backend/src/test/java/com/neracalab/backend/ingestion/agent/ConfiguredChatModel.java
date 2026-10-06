package com.neracalab.backend.ingestion.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The chat model the application is configured with, resolved like the application does it (no model
 * name in test code): {@code spring.ai.openai.chat.model} from {@code application.yaml}
 * ({@code ${OPENAI_MODEL:<default>}}), with {@code OPENAI_MODEL} taken from a real environment variable,
 * else from {@code backend/.env} (imported by {@code spring.config.import: optional:file:.env[.properties]}),
 * else the default written in {@code application.yaml}.
 */
final class ConfiguredChatModel {

    private static final String PROPERTY = "spring.ai.openai.chat.model";

    private ConfiguredChatModel() {
    }

    static String name() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties application = yaml.getObject();
        String raw = application == null ? null : application.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(PROPERTY + " is not set in application.yaml");
        }
        // system environment and system properties first (as in Spring Boot), then .env
        StandardEnvironment environment = new StandardEnvironment();
        Path dotEnv = Path.of(".env");
        if (Files.isRegularFile(dotEnv)) {
            environment.getPropertySources().addLast(new PropertiesPropertySource(".env", load(dotEnv)));
        }
        return environment.resolveRequiredPlaceholders(raw);
    }

    private static Properties load(Path file) {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file.toAbsolutePath(), e);
        }
        return properties;
    }
}

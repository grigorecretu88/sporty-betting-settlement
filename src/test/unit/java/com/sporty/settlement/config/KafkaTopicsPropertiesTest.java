package com.sporty.settlement.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTopicsPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsToAssignmentTopic() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KafkaTopicsProperties.class).eventOutcomes()).isEqualTo("event-outcomes");
        });
    }

    @Test
    void bindsExistingConfigurationPath() {
        contextRunner.withPropertyValues("app.kafka.topics.event-outcomes=custom-outcomes").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KafkaTopicsProperties.class).eventOutcomes()).isEqualTo("custom-outcomes");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void rejectsBlankTopicAtStartup(String topic) {
        contextRunner.withPropertyValues("app.kafka.topics.event-outcomes=" + topic).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KafkaTopicsProperties.class)
    static class PropertiesConfiguration {
    }
}

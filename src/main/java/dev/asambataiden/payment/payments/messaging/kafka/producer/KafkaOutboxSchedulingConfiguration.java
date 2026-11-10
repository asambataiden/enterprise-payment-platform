
package dev.asambataiden.payment.payments.messaging.kafka.producer;

import dev.asambataiden.payment.payments.outbox.SupervisedPaymentOutboxWorker;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Opt-in, bounded outbox polling for the simulated Kafka demo.
 * Disabled by default.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "payment.messaging.kafka",
    name = {"enabled", "dispatch.enabled"},
    havingValue = "true")
public class KafkaOutboxSchedulingConfiguration {

    @Bean
    KafkaOutboxScheduledRelay kafkaOutboxScheduledRelay(
        SupervisedPaymentOutboxWorker worker) {
        return new KafkaOutboxScheduledRelay(worker);
    }

    static final class KafkaOutboxScheduledRelay {
        private static final Logger log =
            LoggerFactory.getLogger(KafkaOutboxScheduledRelay.class);

        private final SupervisedPaymentOutboxWorker worker;
        private final AtomicBoolean stopped = new AtomicBoolean();

        KafkaOutboxScheduledRelay(SupervisedPaymentOutboxWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString =
            "${payment.messaging.kafka.dispatch.interval-ms:1000}")
        public void poll() {
            if (stopped.get()) {
                return;
            }

            try {
                int claimed = worker.runCycle();
                if (claimed > 0) {
                    log.debug("Outbox relay processed {} claims", claimed);
                }
            } catch (RuntimeException failure) {
                stopped.set(true);
                log.error(
                    "Outbox relay stopped after unexpected cycle failure; "
                        + "operator intervention required",
                    failure);
            }
        }
    }
}

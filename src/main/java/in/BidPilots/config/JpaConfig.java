package in.BidPilots.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables JPA Auditing → populates @CreatedDate / @LastModifiedDate on entities.
 *
 * NOTE: @EnableScheduling has been removed from here. It appears in only ONE
 * place — SchedulingConfig.java — to avoid duplicate registration warnings.
 * Having it in two configs is harmless but generates Spring startup noise.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
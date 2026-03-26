package in.BidPilots.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * FIX: @EnableAsync is REQUIRED for @Async to work.
 * Without this, Spring ignores @Async and the immediate-matching call
 * in UserBidMatchingService.runMatchingForFilter() runs synchronously,
 * blocking the HTTP response until all bids are scanned.
 *
 * This config also sets up a dedicated thread pool so the async matching
 * tasks do not steal threads from your web request pool.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "matchingTaskExecutor")
    public Executor matchingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("bid-matching-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
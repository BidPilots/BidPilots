package in.BidPilots.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

// AsyncConfig — Thread Pool Isolation

@Configuration
@EnableAsync
public class AsyncConfig {

	// ── 1. Scraping pool — ALL Selenium / Chrome work ─────────────────────────

	@Bean(name = "scrapingTaskExecutor")
	public Executor scrapingTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(3);
		executor.setThreadNamePrefix("gem-scraping-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(120);
		executor.setRejectedExecutionHandler((r, pool) -> org.slf4j.LoggerFactory.getLogger(AsyncConfig.class)
				.warn("⚠️ Scraping pool saturated — trigger ignored (already running)"));
		executor.initialize();
		return executor;
	}

	@Bean(name = "matchingTaskExecutor")
	@Primary // <-- ADD THIS ANNOTATION
	public Executor matchingTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(100);
		executor.setThreadNamePrefix("bid-matching-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		executor.initialize();
		return executor;
	}

	@Bean(name = "bidCloseTaskScheduler")
	public ThreadPoolTaskScheduler bidCloseTaskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("bid-close-");
		scheduler.setWaitForTasksToCompleteOnShutdown(true);
		scheduler.setAwaitTerminationSeconds(30);
		scheduler.setErrorHandler(t -> org.slf4j.LoggerFactory.getLogger(AsyncConfig.class)
				.error("BidAutoClose scheduler error: {}", t.getMessage(), t));
		scheduler.initialize();
		return scheduler;
	}
}
package in.BidPilots.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * AsyncConfig — Thread Pool Isolation
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 5 isolated thread pools — each pool owns one concern:
 *
 *  ┌─────────────────────────────────────────────────────────────────────┐
 *  │  Tomcat NIO pool (server.tomcat.threads.*)                          │
 *  │  ALL HTTP request threads — never touched by any pool below         │
 *  └─────────────────────────────────────────────────────────────────────┘
 *
 *  ┌──────────────────────────┐  ┌──────────────────────────┐
 *  │  emailTaskExecutor       │  │  scrapingTaskExecutor    │
 *  │  2–4 threads             │  │  1–2 threads (Chrome)    │
 *  │  OTP + welcome emails    │  │  GeMScrapingService      │
 *  │  Fire-and-forget @Async  │  │  CategoryScraping        │
 *  └──────────────────────────┘  └──────────────────────────┘
 *
 *  ┌──────────────────────────┐  ┌──────────────────────────┐
 *  │  matchingTaskExecutor    │  │  bidCloseTaskScheduler   │
 *  │  2–4 threads             │  │  1 thread                │
 *  │  UserBidMatchingService  │  │  BidAutoCloseService     │
 *  └──────────────────────────┘  └──────────────────────────┘
 *
 * @Async qualifier usage:
 *   EmailOTPService.sendOTPEmailAsync()  → @Async("emailTaskExecutor")
 *   EmailOTPService.sendWelcomeEmail()   → @Async("emailTaskExecutor")
 *   GeMScrapingService.*                 → @Async("scrapingTaskExecutor")
 *   UserBidMatchingService.*             → @Async("matchingTaskExecutor")
 *   BidAutoCloseService.*                → bidCloseTaskScheduler (scheduler bean)
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    // ── 1. Email pool — OTP + welcome emails (fire-and-forget) ───────────────
    //
    // Why isolated: SMTP connects to smtp.gmail.com:465 and can block for up
    // to 10 s per send. Isolating this means a slow SMTP server never steals
    // threads from scraping or matching work.

    @Bean(name = "emailTaskExecutor")
    public Executor emailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);          // always-on: handles concurrent OTP sends
        executor.setMaxPoolSize(4);           // burst capacity during registration spikes
        executor.setQueueCapacity(50);        // buffer: 50 pending emails before rejecting
        executor.setThreadNamePrefix("email-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler((r, pool) ->
            org.slf4j.LoggerFactory.getLogger(AsyncConfig.class)
                .warn("⚠️ Email pool saturated — OTP email task rejected. OTP still stored; user can retry via resend.")
        );
        executor.initialize();
        return executor;
    }

    // ── 2. Scraping pool — ALL Selenium / Chrome work ────────────────────────
    //
    // Each Chrome instance ≈ 400 MB RAM + 1 CPU core.
    // 2 threads = 2 concurrent Selenium sessions max.
    // GeMScrapingService spawns 2 internal threads (THREAD_POOL_SIZE=2) per
    // state, so 2 pool threads × 2 internal = up to 4 Chrome tabs at once.
    // Increase maxPoolSize to 3 only if server has ≥ 16 GB RAM.

    @Bean(name = "scrapingTaskExecutor")
    public Executor scrapingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);          // 1 always-on (scrape runs continuously)
        executor.setMaxPoolSize(2);           // 2 max (~800 MB Chrome RAM cap)
        executor.setQueueCapacity(3);         // small queue — reject fast if saturated
        executor.setThreadNamePrefix("gem-scraping-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120); // give in-flight Chrome time to close
        executor.setRejectedExecutionHandler((r, pool) ->
            org.slf4j.LoggerFactory.getLogger(AsyncConfig.class)
                .warn("⚠️ Scraping pool saturated — trigger ignored (already running)")
        );
        executor.initialize();
        return executor;
    }

    // ── 3. Bid-matching pool — UserBidMatchingService.runMatchingForFilter() ─
    //
    // CPU-light (in-memory set operations). 4 threads can serve concurrent
    // filter-save requests. Queue of 100 absorbs burst filter-save traffic.

    @Bean(name = "matchingTaskExecutor")
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

    // ── 4. Bid-close scheduler — BidAutoCloseService ─────────────────────────
    //
    // Runs every 5 minutes doing batch DB updates. Single thread is sufficient.

    @Bean(name = "bidCloseTaskScheduler")
    public ThreadPoolTaskScheduler bidCloseTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("bid-close-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(15);
        scheduler.setErrorHandler(t ->
            org.slf4j.LoggerFactory.getLogger(AsyncConfig.class)
                .error("BidAutoClose scheduler error: {}", t.getMessage(), t)
        );
        scheduler.initialize();
        return scheduler;
    }
}

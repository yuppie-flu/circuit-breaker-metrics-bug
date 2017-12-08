package com.github.yuppieflu;

import com.codahale.metrics.MetricRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.metrics.CircuitBreakerMetrics;
import io.vavr.control.Try;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

public class Resilience4JCircuitBreakerMetricsTest {

    private MetricRegistry metricRegistry = new MetricRegistry();
    private CircuitBreaker circuitBreaker;
    private int callsCounter = 0;

    @Before
    public void setup() throws Exception {
        CircuitBreakerConfig config =
                CircuitBreakerConfig.custom()
                                    .waitDurationInOpenState(Duration.ofSeconds(1))
                                    .failureRateThreshold(10)
                                    .ringBufferSizeInHalfOpenState(1)
                                    .ringBufferSizeInClosedState(2)
                                    .build();
        circuitBreaker = CircuitBreakerRegistry.ofDefaults().circuitBreaker("test", config);

        metricRegistry.registerAll(CircuitBreakerMetrics.ofCircuitBreaker(circuitBreaker));
    }

    @Test
    public void circuitBreakerMetricsUsesFirstStateObjectInstance() throws Exception {
        // first 2 failed calls
        runBackendMethodWithCircuitBreaker();
        runBackendMethodWithCircuitBreaker();

        assertThat(circuitBreaker.getState(), equalTo(CircuitBreaker.State.OPEN));
        assertThat(getFailedRequestsMetricCounter(), equalTo(2));
        assertThat(getSuccessfulRequestsMetricCounter(), equalTo(0));

        TimeUnit.SECONDS.sleep(1);

        // 1 successful call in HALF_CLOSE state to close circuit breaker
        runBackendMethodWithCircuitBreaker();
        assertThat(circuitBreaker.getState(), equalTo(CircuitBreaker.State.CLOSED));

        // another 2 successful calls
        runBackendMethodWithCircuitBreaker();
        runBackendMethodWithCircuitBreaker();

        // Here metric registry still uses old CircuitBreakerMetrics instance instead of fresh one
        assertThat(getFailedRequestsMetricCounter(), equalTo(0));
        assertThat(getSuccessfulRequestsMetricCounter(), equalTo(2));
    }

    private void runBackendMethodWithCircuitBreaker() {
        Try.runRunnable(CircuitBreaker.decorateRunnable(circuitBreaker, this::backendMethod))
           .recover(RuntimeException.class, (Void)null);
    }

    private int getFailedRequestsMetricCounter() {
        return (int)metricRegistry.getGauges().get("resilience4j.circuitbreaker.test.failed").getValue();
    }

    private int getSuccessfulRequestsMetricCounter() {
        return (int)metricRegistry.getGauges().get("resilience4j.circuitbreaker.test.successful").getValue();
    }

    private void backendMethod() {
       switch (callsCounter++) {
           case 0:
           case 1:
               throw new RuntimeException("error");
           default:
               // do nothing, successful call
       }
    }
}

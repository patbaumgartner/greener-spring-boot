package com.patbaumgartner.greener.simulation;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.time.Duration;

/**
 * Gatling simulation for Spring Petclinic energy measurement.
 *
 * Configuration is driven by environment variables set by the
 * greener-spring-boot
 * plugin:
 *
 * GATLING_APP_URL — base URL (default: http://localhost:8080) GATLING_RPS —
 * target
 * requests per second per user group (default: 10) GATLING_WARMUP_SECONDS —
 * warmup
 * duration in seconds (0 to skip) GATLING_MEASURE_SECONDS — measurement
 * duration in
 * seconds
 */
public class PetclinicSimulation extends Simulation {

    private final String baseUrl = System.getenv().getOrDefault("GATLING_APP_URL", "http://localhost:8080");

    private final int rps = Integer.parseInt(System.getenv().getOrDefault("GATLING_RPS", "10"));

    private final int warmupSec = Integer.parseInt(System.getenv().getOrDefault("GATLING_WARMUP_SECONDS", "30"));

    private final int measureSec = Integer.parseInt(System.getenv().getOrDefault("GATLING_MEASURE_SECONDS", "60"));

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(baseUrl)
            .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .acceptLanguageHeader("en-US,en;q=0.5")
            .acceptEncodingHeader("gzip, deflate")
            .userAgentHeader("greener-spring-boot/Gatling");

    // ── Scenarios ────────────────────────────────────────────────────────────

    private final ScenarioBuilder browseOwners = scenario("Browse Owners")
            .exec(http("home").get("/"))
            .pause(Duration.ofMillis(200))
            .exec(http("owners list").get("/owners?lastName="))
            .pause(Duration.ofMillis(200))
            .exec(http("owner detail").get("/owners/1"))
            .pause(Duration.ofMillis(200));

    private final ScenarioBuilder browseVets = scenario("Browse Vets")
            .exec(http("vets page").get("/vets.html"))
            .pause(Duration.ofMillis(300));

    private final ScenarioBuilder healthCheck = scenario("Health Check")
            .exec(http("health").get("/actuator/health"));

    // ── Injection profile
    // ──────────────────────────────────────────────────
    //
    // If GATLING_WARMUP_SECONDS > 0 this file is run twice by run.sh:
    // pass 1 — warmup phase (measureSec=0, no reports)
    // pass 2 — measure phase (warmupSec=0)
    //
    // This way Joular Core captures power only during the measurement pass.

    private final int durationSec = (measureSec > 0) ? measureSec : warmupSec;

    {
        setUp(browseOwners
                .injectOpen(constantUsersPerSec(rps * 0.6).during(Duration.ofSeconds(durationSec))),
                browseVets
                        .injectOpen(constantUsersPerSec(rps * 0.3).during(Duration.ofSeconds(durationSec))),
                healthCheck
                        .injectOpen(constantUsersPerSec(rps * 0.1).during(Duration.ofSeconds(durationSec))))
                .protocols(httpProtocol)
                .assertions(global().successfulRequests().percent().gte(95.0));
    }

}

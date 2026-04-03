package com.patbaumgartner.greener.simulation

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * Gatling simulation for Spring Petclinic energy measurement.
 *
 * Configuration is driven by environment variables set by the greener-spring-boot plugin:
 *
 *   GATLING_APP_URL        — base URL (default: http://localhost:8080)
 *   GATLING_RPS            — target requests per second per user group (default: 10)
 *   GATLING_WARMUP_SECONDS — warmup duration in seconds (0 to skip)
 *   GATLING_MEASURE_SECONDS— measurement duration in seconds
 *
 * Standalone execution (after placing this file in Gatling's user-files/simulations):
 *   GATLING_APP_URL=http://localhost:8080 \
 *   GATLING_RPS=10 \
 *   GATLING_WARMUP_SECONDS=30 \
 *   GATLING_MEASURE_SECONDS=60 \
 *   gatling.sh --simulation PetclinicSimulation
 */
class PetclinicSimulation extends Simulation {

  private val baseUrl        = sys.env.getOrElse("GATLING_APP_URL",         "http://localhost:8080")
  private val rps            = sys.env.getOrElse("GATLING_RPS",             "10").toInt
  private val warmupSec      = sys.env.getOrElse("GATLING_WARMUP_SECONDS",  "30").toInt
  private val measureSec     = sys.env.getOrElse("GATLING_MEASURE_SECONDS", "60").toInt

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    .acceptLanguageHeader("en-US,en;q=0.5")
    .acceptEncodingHeader("gzip, deflate")
    .userAgentHeader("greener-spring-boot/Gatling")

  // ── Scenarios ────────────────────────────────────────────────────────────

  private val browseOwners = scenario("Browse Owners")
    .exec(http("home").get("/"))
    .pause(200.milliseconds)
    .exec(http("owners list").get("/owners?lastName="))
    .pause(200.milliseconds)
    .exec(http("owner detail").get("/owners/1"))
    .pause(200.milliseconds)

  private val browseVets = scenario("Browse Vets")
    .exec(http("vets page").get("/vets.html"))
    .pause(300.milliseconds)

  private val healthCheck = scenario("Health Check")
    .exec(http("health").get("/actuator/health"))

  // ── Injection profile ─────────────────────────────────────────────────────
  //
  // If GATLING_WARMUP_SECONDS > 0 this file is run twice by run.sh:
  //   pass 1 — warmup phase  (measureSec=0, no reports)
  //   pass 2 — measure phase (warmupSec=0)
  //
  // This way Joular Core captures power only during the measurement pass.

  private val durationSec = if (measureSec > 0) measureSec else warmupSec

  setUp(
    browseOwners.inject(
      constantUsersPerSec(rps * 0.6).during(durationSec.seconds)
    ),
    browseVets.inject(
      constantUsersPerSec(rps * 0.3).during(durationSec.seconds)
    ),
    healthCheck.inject(
      constantUsersPerSec(rps * 0.1).during(durationSec.seconds)
    )
  )
    .protocols(httpProtocol)
    .assertions(
      global.successfulRequests.percent.gte(95)
    )
}

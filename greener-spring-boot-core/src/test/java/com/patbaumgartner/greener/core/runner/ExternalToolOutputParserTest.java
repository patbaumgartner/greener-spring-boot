package com.patbaumgartner.greener.core.runner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ExternalToolOutputParserTest {

	// ---- oha ----

	@Test
	void oha_realisticOutput_allSuccess() {
		String output = """
				Summary:
				  Success rate:	100.00%
				  Total:	10.0007 secs
				  Slowest:	0.0340 secs
				  Fastest:	0.0002 secs
				  Average:	0.0011 secs
				  Requests/sec:	999.93

				  Total data:	4.58 MiB
				  Size/request:	480 B
				  Size/sec:	468.72 KiB

				Response time histogram:
				  0.000 [7]    |
				  0.003 [9458] |■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■
				  0.007 [436]  |■
				  0.010 [60]   |
				  0.014 [21]   |
				  0.017 [8]    |
				  0.020 [5]    |
				  0.024 [1]    |
				  0.027 [1]    |
				  0.031 [2]    |
				  0.034 [1]    |

				Latency distribution:
				  10% in 0.0005 secs
				  25% in 0.0007 secs
				  50% in 0.0009 secs
				  75% in 0.0012 secs
				  90% in 0.0017 secs
				  95% in 0.0025 secs
				  99% in 0.0080 secs

				Details (average, fastest, slowest):
				  DNS+dialup:	0.0000 secs, 0.0000 secs, 0.0003 secs
				  DNS lookup:	0.0000 secs, 0.0000 secs, 0.0001 secs

				Status code distribution:
				  [200] 10000 responses
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("oha", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isEqualTo(10000);
		assertThat(parser.failedRequests()).isZero();
	}

	@Test
	void oha_realisticOutput_withErrorsAndMixedStatus() {
		String output = """
				Summary:
				  Success rate:	95.00%
				  Total:	30.0052 secs
				  Slowest:	2.1200 secs
				  Fastest:	0.0003 secs
				  Average:	0.0150 secs
				  Requests/sec:	166.64

				Response time histogram:
				  0.000 [235]  |■
				  0.212 [4510] |■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■
				  0.424 [120]  |
				  0.636 [45]   |
				  0.848 [30]   |
				  1.060 [20]   |
				  1.272 [15]   |
				  1.484 [10]   |
				  1.696 [8]    |
				  1.908 [4]    |
				  2.120 [3]    |

				Status code distribution:
				  [200] 4750 responses
				  [301] 100 responses
				  [404] 50 responses
				  [500] 30 responses
				  [503] 20 responses

				Error distribution:
				  [3] aborted due to deadline
				  [47] connection refused (os error 111)
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("oha", output);

		assertThat(parser.hasResults()).isTrue();
		// 4750 + 100 + 50 + 30 + 20 = 4950 (status code responses only)
		assertThat(parser.totalRequests()).isEqualTo(4950);
		// 404:50 + 500:30 + 503:20 = 100
		assertThat(parser.failedRequests()).isEqualTo(100);
	}

	// ---- wrk ----

	@Test
	void wrk_realisticOutput_allSuccess() {
		String output = """
				Running 30s test @ http://127.0.0.1:8080/index.html
				  12 threads and 400 connections
				  Thread Stats   Avg      Stdev     Max   +/- Stdev
				    Latency   635.91us    0.89ms  12.92ms   93.69%
				    Req/Sec    56.20k     8.07k   62.00k    86.54%
				  22464657 requests in 30.00s, 17.76GB read
				Requests/sec: 748868.53
				Transfer/sec:    606.33MB
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("wrk", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isEqualTo(22464657);
		assertThat(parser.failedRequests()).isZero();
	}

	@Test
	void wrk_realisticOutput_withNonSuccessResponses() {
		String output = """
				Running 30s test @ http://127.0.0.1:8080/api/data
				  8 threads and 200 connections
				  Thread Stats   Avg      Stdev     Max   +/- Stdev
				    Latency    12.34ms    5.67ms  123.45ms   78.90%
				    Req/Sec     1.23k   234.56     2.00k    65.43%
				  294567 requests in 30.01s, 123.45MB read
				  Socket errors: connect 0, read 3, write 0, timeout 12
				  Non-2xx or 3xx responses: 127
				Requests/sec:   9816.09
				Transfer/sec:      4.11MB
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("wrk", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isEqualTo(294567);
		assertThat(parser.failedRequests()).isEqualTo(127);
	}

	@Test
	void wrk2_realisticOutput_withLuaScript() {
		String output = """
				Running 60s test @ http://localhost:8080
				  4 threads and 100 connections
				  Thread calibration: mean lat.: 3.234ms, rate sampling interval: 10ms
				  Thread calibration: mean lat.: 3.456ms, rate sampling interval: 10ms
				  Thread calibration: mean lat.: 3.123ms, rate sampling interval: 10ms
				  Thread calibration: mean lat.: 3.567ms, rate sampling interval: 10ms
				  Thread Stats   Avg      Stdev     Max   +/- Stdev
				    Latency     3.35ms    1.23ms   45.67ms   89.12%
				    Req/Sec     1.05k   123.45     1.56k    78.90%
				  245678 requests in 60.00s, 98.76MB read
				Requests/sec:   4094.63
				Transfer/sec:      1.65MB
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("wrk2", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isEqualTo(245678);
		assertThat(parser.failedRequests()).isZero();
	}

	// ---- bombardier ----

	@Test
	void bombardier_realisticOutput_allSuccess() {
		String output = """
				Bombarding http://localhost:8080 for 10s using 125 connection(s)
				[==========================================================================] 10s
				Done!
				Statistics        Avg      Stdev        Max
				  Reqs/sec     42839.48    6587.09   56288.00
				  Latency        2.92ms     3.50ms    79.49ms
				  HTTP codes:
				    1xx - 0, 2xx - 428394, 3xx - 0, 4xx - 0, 5xx - 0
				    others - 0
				  Throughput:    38.18MB/s
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("bombardier", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isEqualTo(428394);
		assertThat(parser.failedRequests()).isZero();
	}

	@Test
	void bombardier_realisticOutput_withErrors() {
		String output = """
				Bombarding http://localhost:8080/api for 30s using 200 connection(s)
				[==========================================================================] 30s
				Done!
				Statistics        Avg      Stdev        Max
				  Reqs/sec     15234.56    2345.67   22345.00
				  Latency       13.12ms    15.67ms   345.89ms
				  HTTP codes:
				    1xx - 0, 2xx - 450000, 3xx - 5000, 4xx - 234, 5xx - 67
				    others - 12
				  Throughput:    12.34MB/s
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("bombardier", output);

		assertThat(parser.hasResults()).isTrue();
		// 0 + 450000 + 5000 + 234 + 67 = 455301
		assertThat(parser.totalRequests()).isEqualTo(455301);
		// 4xx + 5xx = 234 + 67 = 301
		assertThat(parser.failedRequests()).isEqualTo(301);
	}

	// ---- ab (Apache Bench) ----

	@Test
	void ab_realisticOutput_allSuccess() {
		String output = """
				This is ApacheBench, Version 2.3 <$Revision: 1903618 $>
				Copyright 1996 Adam Twiss, Zeus Technology Ltd, http://www.zeustech.net/
				Licensed to The Apache Software Foundation, http://www.apache.org/

				Benchmarking localhost (be patient)
				Completed 1000 requests
				Completed 2000 requests
				Completed 3000 requests
				Completed 4000 requests
				Completed 5000 requests
				Completed 6000 requests
				Completed 7000 requests
				Completed 8000 requests
				Completed 9000 requests
				Completed 10000 requests
				Finished 10000 requests


				Server Software:        Apache/2.4.54
				Server Hostname:        localhost
				Server Port:            8080

				Document Path:          /
				Document Length:        12345 bytes

				Concurrency Level:      100
				Time taken for tests:   5.234 seconds
				Complete requests:      10000
				Failed requests:        0
				Total transferred:      126780000 bytes
				HTML transferred:       123450000 bytes
				Requests per second:    1910.57 [#/sec] (mean)
				Time per request:       52.340 [ms] (mean)
				Time per request:       0.523 [ms] (mean, across all concurrent requests)
				Transfer rate:          23655.23 [Kbytes/sec] received

				Connection Times (ms)
				              min  mean[+/-sd] median   max
				Connect:        0    1   0.5      1       5
				Processing:     2   51  12.3     48     234
				Waiting:        1   49  11.8     46     230
				Total:          3   52  12.4     49     235

				Percentage of the requests served within a certain time (ms)
				  50%     49
				  66%     54
				  75%     58
				  80%     61
				  90%     68
				  95%     76
				  98%     89
				  99%    102
				 100%    235 (longest request)
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("ab", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isEqualTo(10000);
		assertThat(parser.failedRequests()).isZero();
	}

	@Test
	void ab_realisticOutput_withFailures() {
		String output = """
				Server Software:        nginx/1.24.0
				Server Hostname:        localhost
				Server Port:            8080

				Document Path:          /api/heavy
				Document Length:        Variable

				Concurrency Level:      200
				Time taken for tests:   12.567 seconds
				Complete requests:      50000
				Failed requests:        342
				   (Connect: 0, Receive: 0, Length: 342, Exceptions: 0)
				Non-2xx responses:      342
				Total transferred:      612345000 bytes
				HTML transferred:       598765000 bytes
				Requests per second:    3978.67 [#/sec] (mean)
				Time per request:       50.268 [ms] (mean)
				Time per request:       0.251 [ms] (mean, across all concurrent requests)
				Transfer rate:          47567.89 [Kbytes/sec] received
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("ab", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isEqualTo(50000);
		assertThat(parser.failedRequests()).isEqualTo(342);
	}

	// ---- k6 (legacy format with check marks) ----

	@Test
	void k6_realisticOutput_legacyFormat() {
		String output = """
				          /\\      |‾‾| /‾‾/   /‾‾/
				     /\\  /  \\     |  |/  /   /  /
				    /  \\/    \\    |     (   /   ‾‾\\
				   /          \\   |  |\\  \\ |  (‾)  |
				  / __________ \\  |__| \\__\\ \\_____/ .io

				  execution: local
				     script: test.js
				     output: -

				  scenarios: (100.00%) 1 scenario, 10 max VUs, 1m30s max duration (incl. graceful stop):
				           * default: 10 looping VUs for 1m0s (gracefulStop: 30s)


				running (1m00.0s), 00/10 VUs, 12345 complete and 0 interrupted iterations
				default ✓ [======================================] 10 VUs  1m0s

				     data_received..................: 15 MB  250 kB/s
				     data_sent......................: 1.2 MB 20 kB/s
				     http_req_blocked...............: avg=2.34ms   min=1µs      med=5µs      max=234.56ms p(90)=8µs      p(95)=12µs
				     http_req_connecting............: avg=1.23ms   min=0s       med=0s       max=123.45ms p(90)=0s       p(95)=0s
				     http_req_duration..............: avg=23.45ms  min=1.23ms   med=18.90ms  max=345.67ms p(90)=45.67ms  p(95)=67.89ms
				       { expected_response:true }...: avg=22.34ms  min=1.23ms   med=17.89ms  max=234.56ms p(90)=43.21ms  p(95)=56.78ms
				     http_req_failed................: 0.41%  ✓ 51          ✗ 12294
				     http_req_receiving.............: avg=123.45µs min=12µs     med=89µs     max=12.34ms  p(90)=234µs    p(95)=345µs
				     http_req_sending...............: avg=23.45µs  min=5µs      med=18µs     max=2.34ms   p(90)=34µs     p(95)=45µs
				     http_req_tls_handshaking.......: avg=0s       min=0s       med=0s       max=0s       p(90)=0s       p(95)=0s
				     http_req_waiting...............: avg=23.30ms  min=1.20ms   med=18.75ms  max=345.12ms p(90)=45.23ms  p(95)=67.45ms
				     http_reqs......................: 12345   205.75/s
				     iteration_duration.............: avg=1.02s    min=1.00s    med=1.01s    max=1.45s    p(90)=1.05s    p(95)=1.12s
				     iterations.....................: 12345   205.75/s
				     vus............................: 10      min=10         max=10
				     vus_max........................: 10      min=10         max=10
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("k6", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isEqualTo(12345);
		// k6 semantics: http_req_failed is a Rate metric where
		// ✓ = condition matched (request WAS failed) = 51 failed
		// ✗ = condition did not match (request was NOT failed) = 12294 ok
		assertThat(parser.failedRequests()).isEqualTo(51);
	}

	// ---- k6 (v1.x compact format) ----

	@Test
	void k6_realisticOutput_compactFormat() {
		String output = """
				     scenarios: (100.00%) 1 scenario, 10 max VUs, 1m30s max duration (incl. graceful stop):
				              * default: 10 looping VUs for 1m0s (gracefulStop: 30s)

				running (1m00.0s), 00/10 VUs, 45 complete and 0 interrupted iterations
				default ✓ [======================================] 10 VUs  1m0s

				     data_received..................: 1.5 MB 25 kB/s
				     data_sent......................: 120 kB 2.0 kB/s
				     http_req_blocked...............: avg=1.12ms  min=1µs    med=4µs    max=112.34ms p(90)=7µs    p(95)=10µs
				     http_req_connecting............: avg=0.56ms  min=0s     med=0s     max=56.78ms  p(90)=0s     p(95)=0s
				     http_req_duration..............: avg=45.67ms min=2.34ms med=34.56ms max=567.89ms p(90)=89.12ms p(95)=123.45ms
				     http_req_failed................: 0.00%  0 out of 45
				     http_req_receiving.............: avg=89.12µs min=10µs   med=67µs   max=5.67ms   p(90)=123µs  p(95)=234µs
				     http_req_sending...............: avg=18.90µs min=4µs    med=15µs   max=1.23ms   p(90)=28µs   p(95)=34µs
				     http_reqs......................: 45     0.750000/s
				     iteration_duration.............: avg=1.05s   min=1.00s  med=1.03s  max=1.67s    p(90)=1.12s  p(95)=1.23s
				     iterations.....................: 45     0.750000/s
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("k6", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isEqualTo(45);
		assertThat(parser.failedRequests()).isZero();
	}

	@Test
	void k6_compactFormat_withFailures() {
		String output = """
				     http_req_failed................: 6.67%  3 out of 45
				     http_reqs......................: 45     0.750000/s
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("k6", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isEqualTo(45);
		assertThat(parser.failedRequests()).isEqualTo(3);
	}

	// ---- gatling ----

	@Test
	void gatling_realisticOutput_withErrors() {
		String output = """
				Simulation computerdatabase.BasicSimulation completed in 60 seconds
				Parsing log file(s)...
				Parsing log file(s) done
				Generating reports...

				================================================================================
				---- Global Information --------------------------------------------------------
				> request count                                      12034 (OK=12000  KO=34    )
				> min response time                                      0 (OK=0      KO=12    )
				> max response time                                   1234 (OK=1234   KO=567   )
				> mean response time                                    23 (OK=22     KO=345   )
				> std deviation                                         45 (OK=43     KO=123   )
				> response time 50th percentile                         12 (OK=12     KO=234   )
				> response time 75th percentile                         23 (OK=23     KO=456   )
				> response time 95th percentile                         67 (OK=65     KO=567   )
				> response time 99th percentile                        123 (OK=120    KO=567   )
				> mean requests/sec                                 200.57 (OK=200.00 KO=0.57  )
				---- Response Time Distribution ------------------------------------------------
				> t < 800 ms                                         11900 ( 99%)
				> 800 ms <= t < 1200 ms                                 89 (  1%)
				> t >= 1200 ms                                          11 (  0%)
				> failed                                                34 (  0%)
				---- Errors -------------------------------------------------------------------
				> status.find.is(200), but actually found 500                        20 (58.82%)
				> status.find.is(200), but actually found 503                        14 (41.18%)
				================================================================================
				Reports generated in 0s.
				Please open the following file: /tmp/gatling/results/basicsimulation-20240101120000/index.html
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("gatling", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isEqualTo(12034);
		assertThat(parser.failedRequests()).isEqualTo(34);
	}

	@Test
	void gatling_realisticOutput_allOk() {
		String output = """
				Simulation computerdatabase.BasicSimulation completed in 30 seconds
				Parsing log file(s)...
				Parsing log file(s) done
				Generating reports...

				================================================================================
				---- Global Information --------------------------------------------------------
				> request count                                       5000 (OK=5000   KO=0     )
				> min response time                                      1 (OK=1      KO=-     )
				> max response time                                    456 (OK=456    KO=-     )
				> mean response time                                    12 (OK=12     KO=-     )
				> std deviation                                         23 (OK=23     KO=-     )
				> response time 50th percentile                          8 (OK=8      KO=-     )
				> response time 75th percentile                         14 (OK=14     KO=-     )
				> response time 95th percentile                         34 (OK=34     KO=-     )
				> response time 99th percentile                         78 (OK=78     KO=-     )
				> mean requests/sec                                 166.67 (OK=166.67 KO=-     )
				---- Response Time Distribution ------------------------------------------------
				> t < 800 ms                                          5000 (100%)
				> 800 ms <= t < 1200 ms                                  0 (  0%)
				> t >= 1200 ms                                           0 (  0%)
				> failed                                                 0 (  0%)
				================================================================================
				Reports generated in 0s.
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("gatling", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isEqualTo(5000);
		assertThat(parser.failedRequests()).isZero();
	}

	// ---- locust ----

	@Test
	void locust_realisticOutput_withFailures() {
		String output = """
				[2024-01-15 12:34:56,789] machine/INFO/locust.main: Run time limit set to 60 seconds
				[2024-01-15 12:34:56,790] machine/INFO/locust.main: Starting Locust 2.20.1
				[2024-01-15 12:34:56,791] machine/INFO/locust.runners: Ramping to 50 users at a rate of 10.00 per second
				[2024-01-15 12:35:06,792] machine/INFO/locust.runners: All users spawned: {"WebsiteUser": 50} (50 total users)
				[2024-01-15 12:35:56,793] machine/INFO/locust.main: --run-time limit reached, shutting down
				[2024-01-15 12:35:56,794] machine/INFO/locust.main: Shutting down (exit code 0)
				Type     Name                                                # reqs      # fails |    Avg     Min     Max    Med |   req/s  failures/s
				--------|-----------------------------------------------|-------|-------------|-------|-------|-------|-------|--------|-----------
				GET      /                                                     2345     5(0.21%) |     12       1      45     10 |   39.08        0.08
				GET      /owners                                               1890     3(0.16%) |     23       2     123     18 |   31.50        0.05
				GET      /owners/find                                          1567     0(0.00%) |     15       1      67     12 |   26.12        0.00
				GET      /vets.html                                            1200     2(0.17%) |     34       3     234     25 |   20.00        0.03
				POST     /owners/new                                            987     2(0.20%) |     45       5     345     35 |   16.45        0.03
				--------|-----------------------------------------------|-------|-------------|-------|-------|-------|-------|--------|-----------
				         Aggregated                                            7989    12(0.15%) |     23       1     345     15 |  133.15        0.20
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("locust", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isEqualTo(7989);
		assertThat(parser.failedRequests()).isEqualTo(12);
	}

	@Test
	void locust_realisticOutput_noFailures() {
		String output = """
				Type     Name                                                # reqs      # fails |    Avg     Min     Max    Med |   req/s  failures/s
				--------|-----------------------------------------------|-------|-------------|-------|-------|-------|-------|--------|-----------
				GET      /                                                     5000     0(0.00%) |      8       1      34      6 |   83.33        0.00
				GET      /api/data                                             3000     0(0.00%) |     12       2      56      9 |   50.00        0.00
				--------|-----------------------------------------------|-------|-------------|-------|-------|-------|-------|--------|-----------
				         Aggregated                                            8000     0(0.00%) |     10       1      56      7 |  133.33        0.00
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("locust", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isEqualTo(8000);
		assertThat(parser.failedRequests()).isZero();
	}

	@Test
	void locust_multipleAggregatedLines_usesLast() {
		// Locust prints periodic stats tables during the run (including an initial
		// empty table with 0 requests). The parser must pick the LAST Aggregated
		// line which contains the complete final summary.
		String output = """
				[2024-01-15 12:34:00,000] machine/INFO/locust.main: Starting Locust 2.24.0
				Type     Name                                                # reqs      # fails |    Avg     Min     Max    Med |   req/s  failures/s
				--------|-----------------------------------------------|-------|-------------|-------|-------|-------|-------|--------|-----------
				--------|-----------------------------------------------|-------|-------------|-------|-------|-------|-------|--------|-----------
				         Aggregated                                               0     0(0.00%) |      0       0       0      0 |    0.00        0.00

				Type     Name                                                # reqs      # fails |    Avg     Min     Max    Med |   req/s  failures/s
				--------|-----------------------------------------------|-------|-------------|-------|-------|-------|-------|--------|-----------
				GET      /                                                      150     0(0.00%) |     12       1      45     10 |    5.00        0.00
				--------|-----------------------------------------------|-------|-------------|-------|-------|-------|-------|--------|-----------
				         Aggregated                                             150     0(0.00%) |     12       1      45     10 |    5.00        0.00

				[2024-01-15 12:35:00,000] machine/INFO/locust.main: --run-time limit reached, shutting down
				Type     Name                                                # reqs      # fails |    Avg     Min     Max    Med |   req/s  failures/s
				--------|-----------------------------------------------|-------|-------------|-------|-------|-------|-------|--------|-----------
				GET      /                                                      620     2(0.32%) |     15       1      89     12 |   10.33        0.03
				GET      /owners?lastName=                                       410     0(0.00%) |     23       2     123     18 |    6.83        0.00
				--------|-----------------------------------------------|-------|-------------|-------|-------|-------|-------|--------|-----------
				         Aggregated                                            1030     2(0.19%) |     18       1     123     14 |   17.17        0.03
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("locust", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isEqualTo(1030);
		assertThat(parser.failedRequests()).isEqualTo(2);
	}

	// ---- edge cases ----

	@Test
	void unknownTool_triesAllParsers() {
		String output = """
				  [200] 500 responses
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("script", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isEqualTo(500);
	}

	@Test
	void emptyOutput_returnsUnknown() {
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("oha", "");

		assertThat(parser.hasResults()).isFalse();
		assertThat(parser.totalRequests()).isEqualTo(-1);
		assertThat(parser.failedRequests()).isEqualTo(-1);
	}

	@Test
	void nullOutput_returnsUnknown() {
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("oha", null);

		assertThat(parser.hasResults()).isFalse();
	}

	@Test
	void unrecognisedOutput_returnsUnknown() {
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("custom", "some random output with no recognisable patterns");

		assertThat(parser.hasResults()).isFalse();
	}

	// ---- negative / malformed input tests ----

	@Test
	void oha_partialOutput_noResponseLines() {
		String output = """
				Summary:
				  Success rate:	100.00%
				  Total:	10.0007 secs
				  Requests/sec:	999.93
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("oha", output);

		assertThat(parser.hasResults()).isFalse();
	}

	@Test
	void wrk_malformedRequestCount_doesNotThrow() {
		// Regex won't match non-digit text, so parser should not find results
		String output = """
				  abc requests in 60.00s
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("wrk", output);

		assertThat(parser.hasResults()).isFalse();
	}

	@Test
	void ab_missingCompleteRequests() {
		String output = """
				Failed requests:        10
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("ab", output);

		assertThat(parser.hasResults()).isFalse();
	}

	@Test
	void k6_missingHttpReqs() {
		String output = """
				     http_req_failed................: 0.41%  ✓ 51          ✗ 12294
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("k6", output);

		assertThat(parser.hasResults()).isFalse();
	}

	@Test
	void gatling_malformedRequestCount() {
		// KO line is present but request count line doesn't match pattern
		String output = """
				> request count                                      N/A (OK=N/A    KO=N/A   )
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("gatling", output);

		assertThat(parser.hasResults()).isFalse();
	}

	@Test
	void locust_missingAggregatedLine() {
		String output = """
				GET      /                                                     2345     5(0.21%)
				GET      /owners                                               1890     3(0.16%)
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("locust", output);

		assertThat(parser.hasResults()).isFalse();
	}

	@Test
	void bombardier_noStatusLines() {
		String output = """
				Bombarding http://localhost:8080/api for 30s using 200 connection(s)
				Done!
				Statistics        Avg      Stdev        Max
				  Reqs/sec     15234.56    2345.67   22345.00
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("bombardier", output);

		assertThat(parser.hasResults()).isFalse();
	}

	@Test
	void whitespaceOnlyOutput_returnsUnknown() {
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("oha", "   \n\t\n   ");

		assertThat(parser.hasResults()).isFalse();
	}

	@Test
	void oha_zeroResponses_parsedCorrectly() {
		String output = """
				  [200] 0 responses
				""";
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("oha", output);

		assertThat(parser.hasResults()).isTrue();
		assertThat(parser.totalRequests()).isZero();
		assertThat(parser.failedRequests()).isZero();
	}

}

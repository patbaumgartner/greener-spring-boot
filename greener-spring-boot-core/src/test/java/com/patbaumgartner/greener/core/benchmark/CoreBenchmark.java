package com.patbaumgartner.greener.core.benchmark;

import com.patbaumgartner.greener.core.comparator.EnergyComparator;
import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.EnergyBaseline;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.runner.ExternalToolOutputParser;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for performance-critical core paths.
 *
 * <p>
 * Run with:
 *
 * <pre>
 * mvn -pl greener-spring-boot-core test-compile exec:java \
 *     -Dexec.mainClass="com.patbaumgartner.greener.core.benchmark.CoreBenchmark" \
 *     -Dexec.classpathScope="test"
 * </pre>
 *
 * Or from the {@link #main(String[])} entry point.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class CoreBenchmark {

	private String ohaOutput;

	private String wrkOutput;

	private String k6Output;

	private EnergyReport report;

	private Optional<EnergyBaseline> baseline;

	private EnergyComparator comparator;

	@Setup
	public void setup() {
		ohaOutput = """
				Summary:
				  Success rate: 100.00%
				  Total:        10.0012 secs
				  Slowest:      0.0234 secs
				  Fastest:      0.0001 secs
				  Average:      0.0005 secs
				  Requests/sec: 9876.54

				  Total data:   12.34 MiB
				  Size/request: 123 B
				  Size/sec:     1.23 MiB

				Response time histogram:
				  0.001 [4321] |∎∎∎∎∎∎∎∎∎∎
				  0.002 [3210] |∎∎∎∎∎∎∎∎
				  0.005 [1234] |∎∎∎

				Status code distribution:
				  [200] 49820 responses
				  [201] 180 responses
				""";

		wrkOutput = """
				Running 60s test @ http://localhost:8080
				  2 threads and 10 connections
				  Thread Stats   Avg      Stdev     Max   +/- Stdev
				    Latency     1.23ms  456.78us   23.45ms   89.12%
				    Req/Sec     4.12k   123.45     4.56k    78.90%
				  24340 requests in 60.00s, 12.34MB read
				Requests/sec:    405.67
				Transfer/sec:    210.45KB
				""";

		k6Output = """
				          /\\      |‾‾| /‾‾/   /‾‾/
				     /\\  /  \\     |  |/  /   /  /
				    /  \\/    \\    |     (   /   ‾‾\\
				   /          \\   |  |\\  \\ |  (‾)  |
				  / __________ \\  |__| \\__\\ \\_____/ .io

				  execution: local
				     script: petclinic.js
				     output: -

				  scenarios: (100.00%) 1 scenario, 10 max VUs, 1m30s max duration
				           default: 10 looping VUs for 1m0s

				     data_received..................: 1.2 MB 20 kB/s
				     data_sent......................: 234 kB 3.9 kB/s
				     http_req_blocked...............: avg=1.23ms  min=0s    med=0s     max=123ms  p(90)=0s     p(95)=0s
				     http_req_connecting............: avg=0.56ms  min=0s    med=0s     max=56ms   p(90)=0s     p(95)=0s
				     http_req_duration..............: avg=12.34ms min=1.23ms med=10ms  max=234ms  p(90)=23ms   p(95)=45ms
				     http_req_failed................: 0.41%  ✓ 51       ✗ 12294
				     http_req_receiving.............: avg=0.12ms  min=0s    med=0s     max=12ms   p(90)=0s     p(95)=0.1ms
				     http_req_sending...............: avg=0.02ms  min=0s    med=0s     max=2ms    p(90)=0s     p(95)=0s
				     http_req_tls_handshaking.......: avg=0s      min=0s    med=0s     max=0s     p(90)=0s     p(95)=0s
				     http_req_waiting...............: avg=12.20ms min=1.2ms med=9.8ms  max=234ms  p(90)=22ms   p(95)=44ms
				     http_reqs......................: 12345  205.75/s
				     iteration_duration.............: avg=48.67ms min=5ms   med=42ms   max=890ms  p(90)=89ms   p(95)=123ms
				     iterations.....................: 12345  205.75/s
				     vus............................: 10     min=10     max=10
				     vus_max........................: 10     min=10     max=10
				""";

		report = EnergyReport.of("run-1", Instant.now(), 60, List.of(new EnergyMeasurement("petclinic", 123.45)));

		EnergyReport baselineReport = EnergyReport.of("baseline", Instant.now(), 60,
				List.of(new EnergyMeasurement("petclinic", 120.0)));
		baseline = Optional.of(EnergyBaseline.of(baselineReport, "abc123", "main"));

		comparator = new EnergyComparator();
	}

	@Benchmark
	public ExternalToolOutputParser parseOhaOutput() {
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("oha", ohaOutput);
		return parser;
	}

	@Benchmark
	public ExternalToolOutputParser parseWrkOutput() {
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("wrk", wrkOutput);
		return parser;
	}

	@Benchmark
	public ExternalToolOutputParser parseK6Output() {
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse("k6", k6Output);
		return parser;
	}

	@Benchmark
	public ComparisonResult compareReport() {
		return comparator.compare(report, baseline, 10.0);
	}

	public static void main(String[] args) throws Exception {
		Options opt = new OptionsBuilder().include(CoreBenchmark.class.getSimpleName())
			.forks(1)
			.warmupIterations(3)
			.measurementIterations(5)
			.build();
		new Runner(opt).run();
	}

}

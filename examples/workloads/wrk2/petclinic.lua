-- wrk2 Lua script for Spring Petclinic
--
-- Usage (via greener-spring-boot plugin):
--   <externalTrainingScriptFile>examples/workloads/wrk2/run.sh</externalTrainingScriptFile>
--
-- Usage (standalone):
--   wrk2 -t4 -c20 -d60s -R50 -s examples/workloads/wrk2/petclinic.lua http://localhost:8080
--
-- wrk2 uses a constant throughput model (-R) which is more suitable for
-- reproducible energy measurements than wrk's open-loop model.
--
-- Install: https://github.com/giltene/wrk2

local paths = {
    "/",
    "/owners",
    "/owners?lastName=",
    "/vets.html",
    "/actuator/health",
}

local counter = 0

request = function()
    counter = counter + 1
    local path = paths[(counter % #paths) + 1]
    return wrk.format("GET", path)
end

response = function(status, headers, body)
    if status >= 500 then
        io.write("5xx: " .. status .. "\n")
    end
end

done = function(summary, latency, requests)
    io.write("------------------------------------------------------------------------\n")
    io.write(string.format("Requests: %d  |  Errors: %d\n",
        summary.requests, summary.errors.status))
    io.write(string.format("Latency p50=%.2fms  p90=%.2fms  p99=%.2fms  p99.9=%.2fms\n",
        latency:percentile(50)   / 1000,
        latency:percentile(90)   / 1000,
        latency:percentile(99)   / 1000,
        latency:percentile(99.9) / 1000))
    io.write("------------------------------------------------------------------------\n")
end

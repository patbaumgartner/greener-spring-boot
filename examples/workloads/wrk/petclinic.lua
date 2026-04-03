-- wrk Lua script for Spring Petclinic
--
-- Usage (via greener-spring-boot plugin):
--   <externalTrainingScriptFile>examples/workloads/wrk/run.sh</externalTrainingScriptFile>
--
-- Usage (standalone):
--   wrk -t4 -c20 -d60s -s examples/workloads/wrk/petclinic.lua http://localhost:8080
--
-- Environment variables set by the plugin:
--   APP_URL        base URL (e.g. http://localhost:8080)
--   MEASURE_SECONDS duration of the measurement window

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
    io.write(string.format("Latency p50=%.2fms  p99=%.2fms  max=%.2fms\n",
        latency:percentile(50)  / 1000,
        latency:percentile(99)  / 1000,
        latency.max             / 1000))
    io.write("------------------------------------------------------------------------\n")
end

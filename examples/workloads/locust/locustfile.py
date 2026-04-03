"""
Locust scenario for Spring Petclinic

Locust is a Python-based load testing framework that lets you define user
behaviour as plain Python code.  It runs a distributed event-driven simulation
and exposes live metrics via a web UI (or headless with --headless).

Usage via greener-spring-boot:
  <externalTrainingScriptFile>examples/workloads/locust/run.sh</externalTrainingScriptFile>

Standalone (headless):
  APP_URL=http://localhost:8080 MEASURE_SECONDS=60 RPS=20 \\
    locust --headless --locustfile examples/workloads/locust/locustfile.py

Install locust:
  pip install locust
  Docs: https://locust.io
"""

import os
import time
from locust import HttpUser, task, between, constant_throughput


BASE_URL       = os.environ.get("APP_URL", "http://localhost:8080")
RPS            = int(os.environ.get("RPS", "20"))


class PetclinicUser(HttpUser):
    """
    Simulates a user browsing the Spring Petclinic application.

    The constant_throughput wait-time strategy keeps the overall request rate
    close to the configured RPS value regardless of server latency, making
    energy measurements more reproducible.
    """

    # Each user issues approximately 1 request per second; scale --users to hit RPS
    wait_time = constant_throughput(1)

    host = BASE_URL

    @task(3)
    def browse_home(self):
        self.client.get("/", name="home")

    @task(4)
    def list_owners(self):
        self.client.get("/owners?lastName=", name="owners list")

    @task(2)
    def list_vets(self):
        self.client.get("/vets.html", name="vets")

    @task(1)
    def health_check(self):
        self.client.get("/actuator/health", name="health")

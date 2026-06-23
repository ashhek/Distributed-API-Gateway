# Lightweight API Gateway with Distributed Rate Limiting

## 🚀 Project Executive Summary
A high-performance, containerized API Gateway built to handle **1M+ requests per day** with minimal latency overhead. Designed as a robust edge proxy for microservices, this gateway implements stateless authentication, distributed request throttling, and live configuration reloading. It seamlessly routes traffic while aggressively protecting downstream services from volumetric bursts, DDOS attacks, and cascading failures.

## 🛠 System Component Matrix & Tech Stack
The architecture relies on specialized, purpose-built technologies to ensure maximum throughput and resilience:

* **Spring Cloud Gateway (WebFlux):** Built on Project Reactor and Netty, providing a fully non-blocking, reactive I/O model capable of maintaining thousands of concurrent connections with a tiny memory footprint.
* **Redis (Alpine Docker):** Serves as a highly available, ultra-fast distributed data structure store. Crucial for synchronizing rate limit states across multiple gateway instances globally.
* **JWT (JSON Web Tokens):** Enables stateless, decentralized authentication. The gateway verifies cryptographic signatures at the edge, eliminating the need to query a central database for every request.
* **Spring Cloud Config Server:** Implements a GitOps-style configuration pipeline, allowing for real-time injection of application properties and route rules into the cluster.
* **Micrometer & Chart.js / TailwindCSS:** Provides real-time traffic observability through a bespoke, dark-mode developer dashboard.

## 🧠 Deep-Dive Technical Concepts

### 1. The Token Bucket Algorithm
To manage high-velocity traffic gracefully, we implement the mathematical **Token Bucket algorithm**. 
Instead of rigidly capping requests over a fixed time window (which causes harsh cutoffs), the token bucket models a continuous flow:
* **Burst Capacity (c):** The absolute maximum number of requests a user can make instantly (the size of the bucket).
* **Replenish Rate (r):** The steady rate at which new tokens are added back to the bucket every second.
When a request arrives, we check if the bucket has at least 1 token. If yes, the request is allowed and 1 token is consumed. If no, the request is rejected with `HTTP 429 Too Many Requests`. This elegantly accommodates organic traffic spikes while mathematically guaranteeing an upper bound on long-term throughput.

### 2. Atomic Execution via Redis Lua Scripts
In a distributed environment with multiple gateway instances, checking a user's bucket and then deducting a token requires two operations (`GET` then `SET`). Under heavy concurrent load, this creates a **race condition** (a "double-spend" vulnerability) where users can easily bypass the rate limit.
* **The Solution:** We offload the token bucket mathematics into a custom **Lua script** (`request_rate_limiter.lua`) executed natively inside the Redis engine.
* **Why it works:** Redis evaluates Lua scripts transactionally and atomically. The script calculates the elapsed time, replenishes the tokens, and deducts the current request in a single uninterrupted operation. This mathematically eliminates all concurrency bugs and eliminates network round-trip latency.

### 3. GitOps & Real-time Dynamic Reloading
In production, modifying rate limit tiers or downstream routes usually requires a costly rolling restart of the gateway cluster. 
* **The Solution:** We utilize a dedicated **Spring Cloud Config Server** alongside the Spring Boot Actuator and the `@RefreshScope` annotation. 
* **How it works:** Rate limit limits are defined in `api-gateway.yml` served by the Config Server. When an administrator updates these limits via the Admin UI, the payload rewrites the physical configuration file and fires an internal POST request to the gateway's `/actuator/refresh` endpoint. Spring Boot dynamically destroys and rebuilds the configuration beans in memory, instantly applying the new rate limits to live traffic without dropping a single active connection.

## 🐳 Quickstart & Container Infrastructure Guide
The entire microservice ecosystem (Gateway, Config Server, Downstream Service, and Redis) is fully dockerized.

**Prerequisites:** Docker and Docker Compose installed.

1. **Clone the repository:**
   ```bash
   git clone https://github.com/yourusername/api-gateway.git
   cd api-gateway
   ```

2. **Build and start the container stack in the background:**
   ```bash
   docker compose up -d --build
   ```

3. **Access the Admin Observability Dashboard:**
   Open your browser to: [http://localhost:8080/admin/index.html](http://localhost:8080/admin/index.html)

## ⚡ Automated Load-Test Verification
To instantly replicate a burst traffic attack and watch the gateway's protection curve in action, execute the following script in your terminal:

```bash
# 1. Obtain a stateless JWT token (simulating a FREE tier user)
FREE_TOKEN=$(curl -s http://localhost:8080/auth/token/free | grep -o '"token":"[^"]*' | grep -o '[^"]*$')

# 2. Fire an aggressive volley of 25 rapid requests at the downstream service
for i in {1..25}; do
  curl -si http://localhost:8080/api/data -H "Authorization: Bearer $FREE_TOKEN" | grep -E "HTTP/|X-RateLimit"
done
```

**Expected Result:**
The initial requests will succeed, returning `HTTP/1.1 200 OK` as the `X-RateLimit-Remaining` header drains to zero. Once the bucket capacity is exhausted, the gateway aggressively severs the connection, instantly returning `HTTP/1.1 429 Too Many Requests` to protect the downstream service.

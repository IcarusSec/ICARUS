# Rate Limit Tester Module

The `RateLimitModule` executes high-velocity request bursts to accurately detect, characterize, and attempt bypasses on API rate limiting implementations.

## Execution Phases

### Phase 1: Burst Detection
ICARUS strips mutable payloads from the request and fires `N` identical requests (configurable via the Settings panel) in highly concurrent threads. 
- It monitors the HTTP status codes returned (e.g., `429 Too Many Requests`).
- If no limits are hit, it logs a failure. If limits are hit, it proceeds to Phase 2.

### Phase 2: Characterization
Once a block occurs, the module analyzes the response to determine the enforcement mechanism.
- Was the threshold 10 requests? 50 requests?
- Does the server rely on standard headers (`X-RateLimit-Remaining`)?
- It calculates the precise Requests Per Second (RPS) achieved during the burst.

### Phase 3: Bypass Attempts
If rate limiting is confirmed, ICARUS automatically injects common IP spoofing headers to attempt to bypass the block:
- `X-Forwarded-For: 127.0.0.1`
- `X-Real-IP: 192.168.0.1`
- `Client-IP: 10.0.0.1`

It re-executes the burst. If the server accepts the requests with spoofed headers while the base IP remains blocked, a critical bypass vulnerability is flagged.

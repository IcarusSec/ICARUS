# JWT / Bearer Token Checker (`JWTChecker`)

The `JWTChecker` action is a comprehensive script for detecting and testing JSON Web Tokens (JWTs) and Bearer tokens for common security vulnerabilities directly from the Burp Repeater.

This custom action utilizes regex to identify multiple JWTs located in `Authorization` headers and `Cookie` headers, and automatically tests them for various tampering and misconfiguration flaws.

## Features

- **Automated Discovery**: Detects multiple JWTs using regex across standard headers.
- **Algorithm Analysis**: Detects weak configurations like missing `alg`, `alg=none`, or HMAC keys (suggesting offline brute-forcing possibilities).
- **Header Probes**: Checks for potentially vulnerable embedded claims such as `jwk`, `jku`, `kid`, `x5u`, and `x5c`. Tests potential SSRF and path traversal through `kid` and `jku`.
- **Payload Tampering**: Attempts automated privilege escalation by tampering with common claims (`admin`, `role`, `scope`, `permissions`) and resending the request without resigning.
- **Signature Removal**: Tests for improper signature validation by removing the signature or providing junk signatures.
- **Time-based Attacks**: Detects missing `exp`/`iat` claims, injects excessive expiration times, and manipulates `nbf` and `iat`.
- **Transport Security**: Warns if a JWT is transmitted over HTTP or without HSTS.

## Usage

1. Copy the contents of `JWTChecker.java`.
2. In Burp Suite Repeater, right-click and go to **Custom actions > New > Blank**.
3. Paste the script and run it against a request.
4. **Note:** This script only logs its findings to the Custom Action output tab; it does not generate new Repeater tabs.

## Output

The script categorizes findings into `[CRITICAL]`, `[HIGH]`, `[MEDIUM]`, and `[MANUAL]` review items. Review the Burp Suite extension output or the specific Custom Action log for detailed results of the probes.

<p align="center">
  <img src="./.images/banner.png" alt="ICARUS Banner">
</p>
<h1 align="center">ICARUS</h1>

<p align="center">
<img src="https://img.shields.io/badge/BurpSuite-Custom%20Actions-orange" alt="Burp Suite">
<img src="https://img.shields.io/badge/Security-Testing-blue" alt="Security">
<img src="https://img.shields.io/badge/Language-Java-red" alt="Java">
</p>

<div align="center">
  <a href="#included-custom-actions">Actions</a>
  <span>&nbsp;&nbsp;•&nbsp;&nbsp;</span>
  <a href="#usage">Usage</a>
  <span>&nbsp;&nbsp;•&nbsp;&nbsp;</span>
  <a href="#disclaimer">Disclaimer</a>
  <span>&nbsp;&nbsp;•&nbsp;&nbsp;</span>
  <a href="#license">License</a>
  <br />
</div>

### [Read the docs →](#included-custom-actions)

## What is ICARUS?

**ICARUS** is a collection of custom actions for Burp Suite, designed to automate and streamline specific API security testing tasks directly from the Burp Repeater.

These scripts leverage the **Custom Actions** feature in Burp Suite, allowing analysts to execute automated checks and mutations on selected requests without the need for complex, heavy extensions.

```bash
# Just copy & paste the Java files into Burp Repeater's Custom Actions!
```

Instead of managing bulky extension code, you only need these focused, self-contained Java snippets to run advanced checks directly on your target endpoints. They are significantly faster to set up and usable in existing testing workflows with little to no friction.

## Included Custom Actions

### JSON Input Validation (`ParamValidator`)

Located in the `ParamValidator` directory, this action focuses on testing JSON request parameter validation.

Instead of targeting specific vulnerabilities, it answers a simpler question: **Did the API accept input that should have been rejected?**

If a payload intentionally crafted to violate the expected contract is accepted (e.g., returns an `HTTP 2xx` response), it flags the request for manual investigation. 

**Key Features:**
- **Structural validation tests** (null values, removed fields, empty objects/arrays).
- **Type confusion tests** (e.g., string → number, boolean → string).
- **Boundary value tests** (e.g., empty string, very long strings, zero, negative numbers).
- **Injection payload tests** (SQLi, XSS, NoSQL, Path Traversal, etc.).
- **Fully self-contained JSON parser** with recursive traversal.

[View ParamValidator README →](ParamValidator/README.md)

### HTTP Verb Tester (`HTTPVerbFuzz`)

Located in the `HTTPVerbFuzz` directory, this action performs basic HTTP verb validation for API security testing.

It takes the current request and generates variations using alternate HTTP methods (such as `GET`, `HEAD`, `POST`, `OPTIONS`, `TRACE`, etc.) to identify if alternative methods are accepted by the server.

**Key Features:**
- **Configurable method list** to test against the endpoint.
- **Automatic handling of body content** based on the tested method (e.g., removing the body for `GET` or `HEAD`).
- **Support for `OPTIONS` / `Allow` header validation**.
- **TRACE reflection detection**.
- **Detailed configuration options** available directly within the `httpverbfuzz.java` script.

[View HTTPVerbFuzz README →](HTTPVerbFuzz/README.md)

### JWT / Bearer Token Checker (`JWTChecker`)

Located in the `JWTChecker` directory, this action is a comprehensive script for detecting and testing JSON Web Tokens (JWTs) and Bearer tokens for common security vulnerabilities directly from the Burp Repeater.

It automatically identifies JWTs using regex in `Authorization` headers and `Cookie` headers, and automatically tests them for various tampering and misconfiguration flaws.

**Key Features:**
- **Automated Discovery**: Detects multiple JWTs across standard headers.
- **Algorithm Analysis**: Detects weak configurations like `alg=none` and checks for potentially vulnerable embedded claims (`jwk`, `jku`, `kid`).
- **Payload Tampering**: Attempts automated privilege escalation by tampering with common claims (`admin`, `role`, `scope`) without resigning.
- **Signature Removal**: Tests for improper signature validation by removing the signature.
- **Time-based Attacks**: Detects missing `exp`/`iat` claims and injects excessive expiration times.

[View JWTChecker README →](JWTChecker/README.md)

### Export to Postman (`ExportToPostman`)

Located in the `ExportToPostman` directory, this action exports the current request in the Repeater to a Postman Collection JSON format.

Sometimes you want to quickly port your crafted request from Burp Repeater directly into Postman for team sharing, API documentation, or testing workflows. This Custom Action creates a complete, valid Postman Collection JSON for the active request directly in the Custom Actions log.

**Key Features:**
- **Postman Collection v2.1.0:** Generates fully compatible Postman Collection JSON objects.
- **Accurate Method & URL:** Extracts the exact HTTP method and parses the URL into Postman's `host`, `path`, and `query` block structures.
- **Header Preservation:** Preserves all HTTP headers accurately.
- **Body Preservation:** Preserves raw body payload securely with proper JSON escaping.

[View ExportToPostman README →](ExportToPostman/README.md)

## Usage

To use these custom actions in Burp Suite:

1. Open Burp Suite and navigate to **Repeater**.
2. Go to the **Custom actions** settings (or right-click a request and find the Custom actions menu).
3. Create a **New** action and select **Blank**.
4. Copy the contents of the desired `.java` script (e.g., `httpverbfuzz.java` or `paramvalidator.java`) and paste it into the script editor.
5. Review and adjust the user configuration section at the top of the script if necessary.
6. Run the action against your target requests.

## Disclaimer

These scripts are intended for:
- Security research
- Defensive security testing
- Authorized penetration testing
- Secure software development

> **Use only against systems you are authorized to test.**

## License

Refer to the License section for information about licensing. These actions are open-sourced under the MIT license.

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

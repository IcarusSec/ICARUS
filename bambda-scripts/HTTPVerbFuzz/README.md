# HTTP Verb Tester (`HTTPVerbFuzz`)

A Burp Suite Custom Action for performing basic HTTP verb validation during API security testing.

## Overview

The HTTP Verb Tester automatically generates variations of the current request using alternate HTTP methods (e.g., `GET`, `HEAD`, `POST`, `OPTIONS`, `TRACE`, etc.). It identifies if alternative methods are accepted by the server and surfaces potential issues for manual review.

**Note:** If an alternative method returns an HTTP `2xx` status, it does not confirm a vulnerability. It simply indicates that the method is accepted and should be reviewed.

## Features

- **Automated Method Testing:** Easily test multiple HTTP methods against a single endpoint.
- **Dynamic Body Handling:** Automatically removes the request body for methods like `GET`, `HEAD`, `OPTIONS`, and `TRACE`, while retaining it for `POST`, `PUT`, `PATCH`, and `DELETE`.
- **`Allow` Header Validation:** Validates `OPTIONS` responses to ensure the declared allowed methods match the server's actual behavior.
- **TRACE Reflection Detection:** When a `TRACE` request is accepted, it automatically checks if specific markers are reflected in the response body.
- **State Change Protection:** Safeguard controls to disable state-changing methods (`POST`, `PUT`, `PATCH`, `DELETE`) by default.
- **Integration:** Automatically sends accepted findings to Burp Repeater and excess requests to the Organizer (configurable).

## Configuration

The script contains a **User Configuration** section at the top of the file. You can customize the tool's behavior directly in the Java code before executing it in Burp Repeater.

Configurable options include:
- Which HTTP methods to test.
- Whether to skip the original method.
- Request submission mode (`SEQUENTIAL` vs `BATCH`).
- Body strategy (`AUTO`, `KEEP`, or `REMOVE`).
- Accepted status code ranges (default: 200-299).
- Behavior for `401`/`403` status codes, redirects, and server errors.
- Advanced `OPTIONS` and `TRACE` validation rules.

## Usage

1. Open Burp Suite and navigate to **Repeater**.
2. Create a **New** Custom action and select **Blank**.
3. Paste the contents of `httpverbfuzz.java`.
4. Run the action on any HTTP request to discover accepted alternative methods.

## Disclaimer

Use only against systems you are authorized to test. This tool is intended for authorized security testing and research purposes.

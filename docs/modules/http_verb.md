# HTTP Verb Tester Module

The `HttpVerbModule` automates the process of HTTP method tampering to uncover misconfigurations in routing, CORS, and endpoint security.

## Automated Mutation
When triggered, this module takes a baseline HTTP request and rapidly mutates it across all standard HTTP methods:
- `GET`, `HEAD`, `POST`, `PUT`, `DELETE`, `OPTIONS`, `TRACE`, `PATCH`

## Advanced Behaviors

- **Body Adjustments:** If the module mutates a `POST` request into a `GET`, it intelligently strips the body and attempts to migrate payload parameters into the URL query string.
- **OPTIONS & Allow Headers:** Deeply inspects `OPTIONS` responses, parsing the `Allow` and `Access-Control-Allow-Methods` headers to map the true attack surface of the endpoint.
- **TRACE Reflection:** Specifically targets the `TRACE` method. If the server reflects the request body or headers back in the response, ICARUS automatically flags this as a Cross-Site Tracing (XST) vulnerability.
- **Access Control Bypass:** Often, developers apply strict authorization checks to `POST` routes but neglect `PUT` or `PATCH` on the same URL path. This module attempts to exploit those exact discrepancies.

package icarus.core;

import burp.api.montoya.http.message.HttpRequestResponse;

import java.util.List;
import java.util.function.Consumer;

/**
 * Contract for every ICARUS security testing module.
 *
 * Modules are stateless — all mutable state lives in the
 * {@link ModuleConfig} they receive. This keeps them testable
 * and safe to reuse across concurrent scans.
 */
public interface IcarusModule {

    /** Human-readable module name shown in the UI and reports. */
    String name();

    /**
     * Run all tests in this module against the given request/response.
     *
     * @param requestResponse the target (must have been sent already)
     * @param config          module-specific configuration snapshot
     * @param logger          sink for SQLMap-style verbose progress lines, shown live to the user
     * @return list of findings (empty if nothing detected)
     */
    List<Finding> run(HttpRequestResponse requestResponse, ModuleConfig config, Consumer<String> logger);

    /**
     * Whether this module should run as part of "Run All Modules". Utility modules that
     * aren't security tests (e.g. exporting a request, rather than looking for a
     * vulnerability) should return false here — they stay available via their own
     * individual menu item, but don't fire implicitly on a bulk scan.
     */
    default boolean includeInBulkScan() {
        return true;
    }

    /**
     * Whether this module sends active/mutated payloads to the target (vs. only
     * inspecting the captured request/response). Drives the WAF Safe-Mode prompt:
     * a passive module like the JWT checker shouldn't trigger it. Default false.
     */
    default boolean sendsActivePayloads() {
        return false;
    }
}

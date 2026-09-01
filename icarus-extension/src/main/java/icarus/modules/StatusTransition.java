package icarus.modules;

import icarus.core.Category;

/**
 * Pure classifier for a baseline→mutation response-status change (§3 / §9.1 of the
 * ParamValidator "form-body + non-2xx baseline + status transitions" plan).
 *
 * <p>No Montoya, no I/O, no path state — the {@code (path, class)} dedupe {@code Set}
 * lives in {@link ParamValidatorModule#run}. This class only decides <em>what kind</em>
 * of transition a single mutation represents.
 */
public final class StatusTransition {

    private StatusTransition() {}

    public enum Transition { NONE, BYPASS, ERROR, SESSION_LOST }

    /**
     * @param baselineStatus       baseline HTTP status ({@code <= 0} / -1 = no usable baseline)
     * @param mutationStatus       the mutation's HTTP status
     * @param cat                  the mutation spec's {@link Category}
     * @param removeSpec           whether the spec removed the parameter
     * @param baselineStable       both baseline probes agreed (status + ~size)
     * @param flagOn               {@code pv.status_transition_detection}
     * @param behavioralAnalysis   {@code pv.behavioral_analysis}
     * @param bodyHasVerboseError  a VerboseErrorDetector match in the (5xx) body
     */
    public static Transition classifyTransition(int baselineStatus, int mutationStatus,
            Category cat, boolean removeSpec, boolean baselineStable,
            boolean flagOn, boolean behavioralAnalysis, boolean bodyHasVerboseError) {

        if (!flagOn || !baselineStable || baselineStatus <= 0 || mutationStatus == baselineStatus) {
            return Transition.NONE;
        }

        // Session died mid-scan: a 2xx baseline now redirecting. Scan-integrity event, not a finding.
        if (mutationStatus >= 300 && mutationStatus <= 399
                && baselineStatus >= 200 && baselineStatus <= 299) {
            return Transition.SESSION_LOST;
        }

        // ALLOWLIST: only categories that represent an actual attack getting past the gate.
        // INJECTION = injected payload slipped through; ACCESS_CONTROL = 403→200 authz/IDOR bypass.
        boolean bypassedRejection = baselineStatus >= 400
                && mutationStatus >= 200 && mutationStatus <= 299
                && !removeSpec
                && (cat == Category.INJECTION || cat == Category.ACCESS_CONTROL);
        if (bypassedRejection) return Transition.BYPASS;

        // Suppress ONLY when the verbose-error detector will actually take the finding
        // (BA on AND a body match). With BA off, a stack-trace 500 has no other home.
        boolean brokeBackend = mutationStatus >= 500 && baselineStatus < 500
                && !(behavioralAnalysis && bodyHasVerboseError);
        if (brokeBackend) return Transition.ERROR;

        return Transition.NONE;
    }

    // ── Self-check: one assertion per row of the §4 truth table ───────────────
    // Run with -ea:  java -ea -cp build_manual/classes icarus.modules.StatusTransition
    public static void main(String[] args) {
        // baseline 2xx, mutation 2xx → nothing
        check(Transition.NONE, 200, 200, Category.INJECTION, false, true, true, false, false);
        // 200 → 500, no signature, BA off → ERROR (both categories in table)
        check(Transition.ERROR, 200, 500, Category.INJECTION, false, true, true, false, false);
        check(Transition.ERROR, 200, 500, Category.BOUNDARY, false, true, true, false, false);
        // 200 → 500, stack trace in body, BA on → verbose-error owns it, NONE here
        check(Transition.NONE, 200, 500, Category.INJECTION, false, true, true, true, true);
        // 200 → 500, stack trace in body, BA off → ERROR (nothing else reports it)
        check(Transition.ERROR, 200, 500, Category.INJECTION, false, true, true, false, true);
        // 200 → 400 → drift stays excluded, no transition finding
        check(Transition.NONE, 200, 400, Category.INJECTION, false, true, true, false, false);
        // 400 → 200, INJECTION → BYPASS
        check(Transition.BYPASS, 400, 200, Category.INJECTION, false, true, true, false, false);
        // 403 → 200, ACCESS_CONTROL → BYPASS (authz/IDOR)
        check(Transition.BYPASS, 403, 200, Category.ACCESS_CONTROL, false, true, true, false, false);
        // 403 → 200, INJECTION → BYPASS
        check(Transition.BYPASS, 403, 200, Category.INJECTION, false, true, true, false, false);
        // 400 → 200 but a remove spec / non-allowlisted category → NONE
        check(Transition.NONE, 400, 200, Category.INJECTION, true, true, true, false, false);
        check(Transition.NONE, 400, 200, Category.BOUNDARY, false, true, true, false, false);
        check(Transition.NONE, 400, 200, Category.STRUCTURAL, false, true, true, false, false);
        check(Transition.NONE, 400, 200, Category.TYPE_CONFUSION, false, true, true, false, false);
        // 400 → 400 (different body) → NONE from §3 (equal status)
        check(Transition.NONE, 400, 400, Category.INJECTION, false, true, true, false, false);
        // 500 → 500 → NONE
        check(Transition.NONE, 500, 500, Category.INJECTION, false, true, true, false, false);
        // 200 → 302 → SESSION_LOST
        check(Transition.SESSION_LOST, 200, 302, Category.INJECTION, false, true, true, false, false);
        // baseline unstable → everything off
        check(Transition.NONE, 400, 200, Category.INJECTION, false, false, true, false, false);
        // baseline failed (status -1) → NONE (run() returns List.of() separately)
        check(Transition.NONE, -1, 200, Category.INJECTION, false, true, true, false, false);
        // flag off (default) → NONE
        check(Transition.NONE, 400, 200, Category.INJECTION, false, true, false, false, false);
        check(Transition.NONE, 200, 500, Category.INJECTION, false, true, false, false, false);

        // §9.1 dedupe harness — the Set<String> lives in run(), not here.
        java.util.Set<String> seen = new java.util.HashSet<>();
        assert seen.add("body:q|error");
        assert seen.add("body:q|bypass");
        assert !seen.add("body:q|error") : "second add of same (path,class) must return false";

        System.out.println("StatusTransition self-check: all assertions passed");
    }

    private static void check(Transition expected, int base, int mut, Category cat, boolean remove,
            boolean stable, boolean flagOn, boolean ba, boolean verbose) {
        Transition actual = classifyTransition(base, mut, cat, remove, stable, flagOn, ba, verbose);
        assert actual == expected
                : String.format("classifyTransition(%d,%d,%s,remove=%b,stable=%b,flag=%b,ba=%b,verbose=%b) = %s, expected %s",
                        base, mut, cat, remove, stable, flagOn, ba, verbose, actual, expected);
    }
}

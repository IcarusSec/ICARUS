package icarus.modules.ast;

import icarus.modules.PayloadRepository;
import icarus.modules.ast.mutators.*;

import java.util.ArrayList;
import java.util.List;

public class AstMutationGenerator {

    public static List<AstMutationResult> generateMutations(OffensiveAstRoot astRoot) {
        List<AstMutationResult> allResults = new ArrayList<>();

        // Generate mutations for all payloads in PayloadRepository
        List<String> payloads = new ArrayList<>();
        payloads.addAll(PayloadRepository.CANARY_PROBES);
        payloads.addAll(PayloadRepository.SQLI_TIME);
        payloads.addAll(PayloadRepository.XSS_POLYGLOT);
        payloads.addAll(PayloadRepository.PATH_TRAVERSAL);
        // Add a few structural payloads
        payloads.add("{ \"$ne\": null }"); // NoSQLi
        payloads.add("'%26%26%201%3D1--"); // WAF Bypass
        
        for (String payload : payloads) {
            // Type Confusion
            TypeConfusionMutator typeConfusion = new TypeConfusionMutator(astRoot, payload);
            typeConfusion.traverse(astRoot.getRootNode());
            allResults.addAll(typeConfusion.getMutatedRoots());

            // HPP
            HppMutator hpp = new HppMutator(astRoot, payload);
            hpp.traverse(astRoot.getRootNode());
            allResults.addAll(hpp.getMutatedRoots());

            // Raw Byte Boundary Breaking
            RawByteBoundaryMutator rawByte = new RawByteBoundaryMutator(astRoot, payload);
            rawByte.traverse(astRoot.getRootNode());
            allResults.addAll(rawByte.getMutatedRoots());
        }

        return allResults;
    }
}

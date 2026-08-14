package icarus.mcp;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;

import java.util.Map;

/**
 * Always-valid stub. ICARUS defines its own tool schemas and controls both sides of every
 * call, so real JSON Schema validation (com.networknt:json-schema-validator, which drags in
 * jackson-dataformat-yaml/snakeyaml/itu) isn't worth the extra jars for a first slice.
 * ponytail: add real validation if a future tool needs to reject malformed client arguments
 * rather than let the tool handler fail on bad input.
 */
public final class IcarusJsonSchemaValidator implements JsonSchemaValidator {
    @Override
    public ValidationResponse validate(Map<String, Object> schema, Object content) {
        return ValidationResponse.asValid(null);
    }
}

package icarus.evidence;

/** Masking survives the hard wrap the evidence editor applies to long header values. */
public final class SecretMaskerTest {

    public static void main(String[] args) {
        String token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV";
        // Bearer token cut in three, as wrapEvidenceText would leave it.
        String wrapped = "GET /api?password=hunter2 HTTP/1.1\n"
                + "Host: example.com\n"
                + "Authorization: Bearer " + token.substring(0, 20) + "\n"
                + token.substring(20, 50) + "\n"
                + token.substring(50) + "\n"
                + "Accept: */*\n"
                + "\n"
                + "{\"token\": \"abc\"}";
        String out = SecretMasker.mask(wrapped);

        for (String part : new String[] {token.substring(5, 20), token.substring(20, 50), token.substring(50), "hunter2", "\"abc\""}) {
            assert !out.contains(part) : "leaked " + part + " in:\n" + out;
        }
        assert out.contains("Authorization: Bearer ") : out;
        assert out.contains("Host: example.com\n") && out.contains("Accept: */*\n") : "non-secret headers changed:\n" + out;
        assert SecretMasker.mask("HTTP/1.1 200 OK\nX-Foo: bar\n\nhello").equals("HTTP/1.1 200 OK\nX-Foo: bar\n\nhello");
        System.out.println("SecretMaskerTest OK");
    }
}

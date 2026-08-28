import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.TemporaryFileContext;
import java.lang.reflect.Method;
public class TestMontoya {
    public static void main(String[] args) throws Exception {
        for (Method m : TemporaryFileContext.class.getMethods()) {
            System.out.println(m);
        }
    }
}

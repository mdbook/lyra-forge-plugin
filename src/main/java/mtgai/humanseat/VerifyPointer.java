package mtgai.humanseat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * A build job's check of a signed pointer before it trusts it: exits 0
 * if SIG (base64, as uploaded next to the pointer) is a valid Ed25519 signature over FILE's exact bytes for the public
 * key in PUB (base64 X.509 SubjectPublicKeyInfo DER: the launchers' update-signing.pub), else 1. The same check as
 * {@link UpdateCheck#verify}, standalone so it runs from source with nothing built:
 *
 * <pre>
 * java src/main/java/mtgai/humanseat/VerifyPointer.java --pub PUB FILE SIG
 * </pre>
 */
public final class VerifyPointer {
    private VerifyPointer() {
    }

    public static void main(String[] args) {
        if (args.length != 4 || !args[0].equals("--pub")) {
            System.err.println("usage: VerifyPointer --pub PUBLIC_KEY_BASE64 FILE SIG");
            System.exit(2);
        }
        final boolean ok = verify(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
        System.out.println((ok ? "signature ok: " : "BAD signature: ") + args[2]);
        System.exit(ok ? 0 : 1);
    }

    static boolean verify(Path pub, Path file, Path sig) {
        try {
            final Signature v = Signature.getInstance("Ed25519");
            v.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(Files.readString(pub, StandardCharsets.US_ASCII).trim()))));
            v.update(Files.readAllBytes(file));
            return v.verify(Base64.getDecoder().decode(Files.readString(sig, StandardCharsets.US_ASCII).trim()));
        } catch (Exception e) {
            return false;
        }
    }
}

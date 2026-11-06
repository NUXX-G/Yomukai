// Builds and signs the declarative sources bundle.
//   java scripts/SignSources.java [clave]
// Reads every sources/*.json, writes shared/src/main/resources/sources/bundle.json and bundle.json.sig.
// The private key (PKCS#8, Base64) comes from the argument, the SOURCES_SIGNING_KEY variable, or
// ~/.yomukai/sources-signing.key. It never goes into the repository.
// With --check it only verifies that the bundle matches the files (used by CI and the tests).

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.stream.*;

public class SignSources {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        Path src = root.resolve("sources");
        Path out = root.resolve("shared/src/main/resources/sources");
        List<Path> files;
        try (Stream<Path> s = Files.list(src)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().collect(Collectors.toList());
        }
        // Each file is kept as written (compact JSON, keys in order); the bundle is a plain JSON array of them.
        StringBuilder sb = new StringBuilder();
        String body = files.stream().map(p -> {
            try { return compact(Files.readString(p, StandardCharsets.UTF_8)); } catch (Exception e) { throw new RuntimeException(p + ": " + e); }
        }).collect(Collectors.joining(",\n    "));
        boolean check = Arrays.asList(args).contains("--check");
        Path bundleFile = out.resolve("bundle.json");
        String existing = Files.exists(bundleFile) ? Files.readString(bundleFile, StandardCharsets.UTF_8) : "";
        String existingSources = existing.contains("\"sources\"") ? existing.substring(existing.indexOf("\"sources\"")) : "";
        String newSources = "\"sources\": [\n    " + body + "\n  ]\n}\n";
        if (check) {
            if (!existingSources.equals(newSources)) {
                System.err.println("bundle.json no coincide con sources/*.json: ejecuta java scripts/SignSources.java");
                System.exit(1);
            }
            System.out.println("bundle.json al día");
            return;
        }
        if (existingSources.equals(newSources) && Files.exists(out.resolve("bundle.json.sig"))) {
            System.out.println("Sin cambios en las fuentes");
            return;
        }
        long version = System.currentTimeMillis() / 1000;
        sb.append("{\n  \"format\": 1,\n  \"version\": ").append(version).append(",\n  ").append(newSources);
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);

        String keyText = args.length > 0 && !args[0].startsWith("--") ? Files.readString(Paths.get(args[0])) : System.getenv("SOURCES_SIGNING_KEY");
        if (keyText == null || keyText.isBlank()) keyText = Files.readString(Paths.get(System.getProperty("user.home"), ".yomukai", "sources-signing.key"));
        PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyText.trim())));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(bytes);
        Files.createDirectories(out);
        Files.write(bundleFile, bytes);
        Files.writeString(out.resolve("bundle.json.sig"), Base64.getEncoder().encodeToString(signer.sign()) + "\n");
        System.out.println("Firmado: " + files.size() + " fuentes, versión " + version);
    }

    /** Collapses whitespace outside strings, so formatting changes in a file do not change the bundle twice. */
    static String compact(String json) {
        StringBuilder out = new StringBuilder();
        boolean inString = false, escaped = false;
        for (char c : json.trim().toCharArray()) {
            if (inString) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
                out.append(c);
            } else if (!Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}

package atom;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The email-ext atom, started the way the runner starts it.
 *
 * <p>The checks the old {@code tools/smoke.sh} made: the subject tokens expand from the build environment, the body
 * quotes the log tail and the failed test, and an empty recipient is refused.
 */
class MailExtIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("the subject tokens expand and the body carries the log tail and the failed test")
    void quotesTheBuild() throws Exception {
        AtomResult run = atom
                .file("target/surefire-reports/TEST-a.xml", "<testsuite tests=\"1\" failures=\"1\">"
                        + "<testcase classname=\"demo.A\" name=\"red\"><failure message=\"boom\"/></testcase>"
                        + "</testsuite>")
                .file("build.log", "line one\nERROR broke\n")
                .input("{\"to\": \"dev@example.com\", \"dryRun\": \"true\","
                        + " \"subject\": \"#$BUILD_NUMBER $BUILD_STATUS\"}")
                .input("atom_build_log", "build.log")
                .input("BUILD_NUMBER", "12")
                .input("BUILD_STATUS", "FAILURE")
                .input("JOB_NAME", "parcel")
                .run();

        run.assertOk();
        String eml = run.file("message.eml");
        assertNotNull(eml, "no message.eml");
        assertTrue(subject(eml).contains("#12 FAILURE"), "subject: " + subject(eml));
        String body = body(eml);
        assertTrue(body.contains("line one"), "the log excerpt is missing: " + body);
        assertTrue(body.contains("demo.A#red"), "the failed test is missing: " + body);
    }

    @Test
    @DisplayName("an empty recipient is refused by name")
    void refusesWithoutARecipient() throws Exception {
        AtomResult run = atom.input("{\"to\": \"\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no recipient"), run.file("output.json"));
    }

    /** The {@code Subject:} header, unfolded, the way a mail client reads it. */
    private static String subject(String eml) {
        for (String line : eml.split("\r\n")) {
            if (line.toLowerCase().startsWith("subject:")) {
                return line.substring("subject:".length()).trim();
            }
        }
        return "";
    }

    /** The decoded body: the atom writes UTF-8 base64, so the text has to be decoded before it is read. */
    private static String body(String eml) {
        int split = eml.indexOf("\r\n\r\n");
        String payload = split < 0 ? "" : eml.substring(split + 4);
        return new String(Base64.getMimeDecoder().decode(payload.strip()), StandardCharsets.UTF_8);
    }
}

package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // ------------------------------------------------------------------ tests added for the token/transport coverage

    @Test
    @DisplayName("an attachment turns the message into multipart/mixed with the file inside")
    void attachesAFile() throws Exception {
        AtomResult run = atom.file("reports/summary.txt", "one green, one red\n")
                .input("to", "dev@example.com")
                .input("dryRun", "true")
                .input("attachments", " reports/summary.txt ")
                .input("body", "see attached")
                .run();

        run.assertOk();
        String eml = run.file("message.eml");
        assertTrue(eml.contains("Content-Type: multipart/mixed; boundary=\""), eml);
        assertTrue(eml.contains("Content-Disposition: attachment; filename=\"summary.txt\""),
                "the attachment part is missing:\n" + eml);
        String encoded = Base64.getMimeEncoder().encodeToString(
                "one green, one red\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(eml.contains(encoded), "the attachment bytes are not in the message:\n" + eml);
        assertTrue(bodyPart(eml).contains("see attached"), bodyPart(eml));
        // the multipart message must be closed, or mail clients drop the attachment
        assertTrue(eml.stripTrailing().endsWith("--"), eml);
    }

    @Test
    @DisplayName("a missing attachment is refused by name, before anything is sent")
    void refusesAMissingAttachment() throws Exception {
        AtomResult run = atom.input("to", "dev@example.com")
                .input("dryRun", "true")
                .input("attachments", "nope.txt")
                .run();

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("attachment missing or outside the workspace: nope.txt"), output);
        assertTrue(run.file("message.eml") == null, "no message should be written for a refused run");
    }

    @Test
    @DisplayName("an attachment path that escapes the workspace is refused")
    void refusesAnAttachmentOutsideTheWorkspace() throws Exception {
        AtomResult run = atom.input("to", "dev@example.com")
                .input("dryRun", "true")
                .input("attachments", "../secret.txt")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("outside the workspace"), run.file("output.json"));
    }

    @Test
    @DisplayName("without dryRun an SMTP host is required")
    void refusesWithoutAnSmtpHost() throws Exception {
        AtomResult run = atom.input("to", "dev@example.com").input("dryRun", "false").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no SMTP host"), run.file("output.json"));
    }

    @Test
    @DisplayName("html=true and a non-ASCII subject: text/html and an RFC 2047 encoded header")
    void rendersHtmlAndEncodesTheSubject() throws Exception {
        AtomResult run = atom.input("to", "dev@example.com")
                .input("dryRun", "true")
                .input("html", "true")
                .input("subject", "构建 $BUILD_NUMBER 失败")
                .input("body", "<b>red</b>")
                .input("BUILD_NUMBER", "9")
                .run();

        run.assertOk();
        String eml = run.file("message.eml");
        assertTrue(eml.contains("Content-Type: text/html; charset=UTF-8"), eml);
        String subject = subject(eml);
        assertTrue(subject.startsWith("=?UTF-8?B?"), "subject is not encoded: " + subject);
        String decoded = new String(Base64.getDecoder().decode(
                subject.substring("=?UTF-8?B?".length(), subject.length() - 2)), StandardCharsets.UTF_8);
        assertEquals("构建 9 失败", decoded);
        assertTrue(bodyPart(eml).contains("<b>red</b>"), bodyPart(eml));
    }

    @Test
    @DisplayName("${BUILD_LOG_EXCERPT,2} quotes only the last two lines, and an unknown token stays as written")
    void honoursTheExcerptArgumentAndLeavesUnknownTokens() throws Exception {
        AtomResult run = atom.file("build.log", "first line\nsecond line\nERROR one\nERROR two\n")
                .input("to", "dev@example.com")
                .input("dryRun", "true")
                .input("body", "[${BUILD_LOG_EXCERPT,2}] $NOPE $CAUSE")
                .input("atom_build_log", "build.log")
                .input("BUILD_CAUSE", "Started by timer")
                .run();

        run.assertOk();
        String body = bodyPart(run.file("message.eml"));
        assertTrue(body.contains("ERROR one") && body.contains("ERROR two"), body);
        assertTrue(!body.contains("first line") && !body.contains("second line"),
                "the excerpt should be two lines only: " + body);
        assertTrue(body.contains("$NOPE"), "an unknown token must stay literal: " + body);
        assertTrue(body.contains("Started by timer"), "the CAUSE token was not expanded: " + body);
    }

    @Test
    @DisplayName("a build log that was not handed over says so instead of quoting nothing")
    void saysWhenThereIsNoBuildLog() throws Exception {
        AtomResult run = atom.input("to", "dev@example.com")
                .input("dryRun", "true")
                .input("body", "log: $BUILD_LOG_EXCERPT")
                .run();

        run.assertOk();
        assertTrue(bodyPart(run.file("message.eml")).contains("(no build log available)"),
                bodyPart(run.file("message.eml")));
    }

    @Test
    @DisplayName("FAILED_TESTS with no reports says so, and a non-numeric argument keeps the default")
    void saysWhenNoTestFailed() throws Exception {
        AtomResult run = atom.file("build.log", "a\nb\nc\n")
                .input("to", "dev@example.com")
                .input("dryRun", "true")
                .input("body", "$FAILED_TESTS|${BUILD_LOG_EXCERPT,not-a-number}")
                .input("atom_build_log", "build.log")
                .run();

        run.assertOk();
        String body = bodyPart(run.file("message.eml"));
        assertTrue(body.contains("(no failed tests found)"), body);
        assertTrue(body.contains("a\nb\nc\n"), "the default excerpt is the whole log: " + body);
    }

    @Test
    @DisplayName("more than twenty failed cases are cut off with an ellipsis")
    void capsTheFailedTestList() throws Exception {
        StringBuilder xml = new StringBuilder("<testsuite name=\"demo\" tests=\"21\" failures=\"21\">");
        for (int i = 0; i < 21; i++) {
            xml.append("<testcase classname=\"demo.Case").append(i).append("\" name=\"t").append(i)
                    .append("\"><failure message=\"boom\"/></testcase>");
        }
        xml.append("</testsuite>");

        AtomResult run = atom.file("reports/TEST-demo.xml", xml.toString())
                .input("to", "dev@example.com")
                .input("dryRun", "true")
                .input("body", "$FAILED_TESTS")
                .run();

        run.assertOk();
        String body = bodyPart(run.file("message.eml"));
        assertTrue(body.contains("- demo.Case19#t19"), body);
        assertTrue(!body.contains("Case20#t20"), "the list must stop at twenty: " + body);
        assertTrue(body.endsWith("…\n"), "the truncation marker is missing: " + body);
    }

    @Test
    @DisplayName("CHANGES asks git for the last commits and renders them with the plugin's format")
    void quotesTheChangesFromGit() throws Exception {
        AtomResult run = atom.bin("git", "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$GIT_ARGV\"\n"
                        + "echo '- fix the parser (ann)'\nexit 0\n")
                .env("GIT_ARGV", "git-argv.txt")
                .input("to", "dev@example.com")
                .input("dryRun", "true")
                .input("body", "$CHANGES")
                .run();

        run.assertOk();
        String body = bodyPart(run.file("message.eml"));
        assertTrue(body.contains("- fix the parser (ann)"), body);
        String argv = run.file("git-argv.txt");
        assertTrue(argv != null && argv.startsWith("log\n-10\n--pretty=- %s (%an)"),
                "git was not asked for ten commits in the plugin's format: " + argv);
    }

    @Test
    @DisplayName("CHANGES with git on no PATH says git is not available rather than failing the mail")
    void saysWhenGitIsUnavailable() throws Exception {
        AtomResult run = atom.env("PATH", "/nonexistent-bin")
                .input("to", "dev@example.com")
                .input("dryRun", "true")
                .input("body", "$CHANGES")
                .run();

        run.assertOk();
        assertTrue(bodyPart(run.file("message.eml")).contains("(git not available)"),
                bodyPart(run.file("message.eml")));
    }

    @Test
    @DisplayName("the delivery is curl with smtp(s), TLS, one --mail-rcpt per address and the message file")
    void handsTheMessageToCurl() throws Exception {
        AtomResult run = atom.bin("curl", "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$CURL_ARGV\"\nexit 0\n")
                .env("CURL_ARGV", "curl-argv.txt")
                .input("to", "dev@example.com, qa@example.com")
                .input("from", "ci@example.com")
                .input("smtpHost", "smtp.example.com")
                .input("smtpPort", "2525")
                .input("smtpUser", "jenkins")
                .input("smtpPassword", "hunter2")
                .input("subject", "done")
                .run();

        run.assertOk();
        String argv = run.file("curl-argv.txt");
        assertNotNull(argv);
        assertTrue(argv.contains("smtps://smtp.example.com:2525"), argv);
        assertTrue(argv.contains("--ssl-reqd"), argv);
        assertTrue(argv.contains("--user\njenkins:hunter2"), argv);
        assertTrue(argv.contains("--mail-from\nci@example.com"), argv);
        assertEquals(2, argv.split("--mail-rcpt").length - 1, "both recipients should get a --mail-rcpt:\n" + argv);
        assertTrue(argv.contains("--upload-file"), argv);
        assertTrue(argv.contains("message.eml"), "the message file is not what curl uploads: " + argv);
        assertEquals("dev@example.com, qa@example.com", run.field("recipients"));
        assertEquals("done", run.field("subject"));
    }

    @Test
    @DisplayName("tls=false drops --ssl-reqd and uses smtp://")
    void plainSmtpWhenTlsIsOff() throws Exception {
        AtomResult run = atom.bin("curl", "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$CURL_ARGV\"\nexit 0\n")
                .env("CURL_ARGV", "curl-argv.txt")
                .input("to", "dev@example.com")
                .input("smtpHost", "smtp.example.com")
                .input("smtpPort", "25")
                .input("tls", "false")
                .run();

        run.assertOk();
        String argv = run.file("curl-argv.txt");
        assertTrue(argv.contains("smtp://smtp.example.com:25"), argv);
        assertTrue(!argv.contains("--ssl-reqd"), argv);
        assertTrue(!argv.contains("--user"), "no user was set, so no --user may be sent: " + argv);
    }

    @Test
    @DisplayName("a non-zero curl exit is a third-party error carrying curl's own words")
    void reportsAFailedDelivery() throws Exception {
        AtomResult run = atom.bin("curl", "#!/bin/sh\necho 'Connection refused'\nexit 7\n")
                .input("to", "dev@example.com")
                .input("smtpHost", "smtp.example.com")
                .run();

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("smtp delivery failed (curl exit 7)"), output);
        assertTrue(output.contains("Connection refused"), output);
    }

    /** The first base64 body part of an eml (plain or the text part of a multipart message). */
    private static String bodyPart(String eml) {
        int marker = eml.indexOf("Content-Transfer-Encoding: base64\r\n\r\n");
        if (marker < 0) {
            return "";
        }
        String rest = eml.substring(marker + "Content-Transfer-Encoding: base64\r\n\r\n".length());
        int end = rest.indexOf("\r\n--");
        String payload = (end < 0 ? rest : rest.substring(0, end)).strip();
        return new String(Base64.getMimeDecoder().decode(payload), StandardCharsets.UTF_8);
    }
}

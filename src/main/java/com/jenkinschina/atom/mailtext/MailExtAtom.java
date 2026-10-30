package com.jenkinschina.atom.mailtext;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * email-ext as an atom: an email whose body can quote the build itself.
 *
 * <p>The plugin's value is not the SMTP call — it is the tokens: the tail of the console log, the failed tests, the
 * commits since the last build. Those are what people actually read in the mail, so they are implemented here.
 * Token syntax follows the plugin ({@code $TOKEN}, {@code ${TOKEN,arg}}), and unknown tokens stay as written instead of
 * silently turning into empty strings.
 */
public class MailExtAtom implements AtomTask {

    private static final Pattern TOKEN = Pattern.compile("\\$(?:\\{([A-Z_0-9]+)(?:,([^}]*))?}|([A-Z_0-9]{3,}))");

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String to = expand(context, workspace, context.getParam("to", ""));
        String from = expand(context, workspace, context.getParam("from", "jenkins@localhost"));
        String subject = expand(context, workspace,
                context.getParam("subject", "[Jenkins] $PROJECT_NAME #$BUILD_NUMBER - $BUILD_STATUS"));
        String body = expand(context, workspace, context.getParam("body",
                "Build: $BUILD_URL\nResult: $BUILD_STATUS\n\n$BUILD_LOG_EXCERPT\n\n$FAILED_TESTS"));
        String host = context.getParam("smtpHost", "");
        String port = context.getParam("smtpPort", "587");
        boolean tls = context.getBooleanParam("tls", true);
        String user = context.getParam("smtpUser", "");
        String password = context.getSecret("smtpPassword");
        if (password == null) {
            password = context.getParam("smtpPassword", "");
        }
        boolean dryRun = context.getBooleanParam("dryRun", false);
        boolean html = context.getBooleanParam("html", false);
        if (to.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001, "no recipient: set 'to'");
        }
        if (!dryRun && host.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40002, "no SMTP host: set 'smtpHost'");
        }

        List<Path> attachments = new ArrayList<>();
        for (String name : expand(context, workspace, context.getParam("attachments", "")).split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path file = workspace.resolve(trimmed).normalize();
            if (!file.startsWith(workspace) || !Files.isRegularFile(file)) {
                return AtomResult.error(ErrorType.USER, 40003, "attachment missing or outside the workspace: "
                        + trimmed);
            }
            attachments.add(file);
        }

        String eml = message(from, to, subject, body, html, attachments);
        Path emlFile = context.getDataDir().resolve("message.eml");
        Files.createDirectories(emlFile.getParent());
        Files.writeString(emlFile, eml, StandardCharsets.UTF_8);
        context.log("[mail-ext] to=" + to + " subject=" + subject + " attachments=" + attachments.size());

        if (!dryRun) {
            List<String> command = new ArrayList<>(List.of("curl", "-sS", "--url",
                    (tls ? "smtps" : "smtp") + "://" + host + ":" + port));
            if (tls) {
                command.add("--ssl-reqd");
            }
            if (!user.isBlank()) {
                command.add("--user");
                command.add(user + ":" + (password == null ? "" : password));
            }
            command.add("--mail-from");
            command.add(from);
            for (String recipient : recipients(to)) {
                command.add("--mail-rcpt");
                command.add(recipient);
            }
            command.add("--upload-file");
            command.add(emlFile.toString());
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = process.waitFor();
            if (exit != 0) {
                return AtomResult.error(ErrorType.THIRD_PART, 40005,
                        "smtp delivery failed (curl exit " + exit + "): " + output);
            }
        }
        return AtomResult.success()
                .data("recipients", to)
                .data("subject", subject)
                .artifact("emlFile", Files.exists(emlFile) ? emlFile.toString() : "");
    }

    /** Tokens the plugin is known for, computed from what the build left in the workspace. */
    private String expand(AtomContext context, Path workspace, String template) throws IOException {
        Matcher matcher = TOKEN.matcher(template == null ? "" : template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
            String argument = matcher.group(2);
            String value = switch (name) {
                case "PROJECT_NAME", "JOB_NAME" -> context.getParam("JOB_NAME", context.getParam("JOB_NAME", ""));
                case "BUILD_NUMBER" -> context.getParam("BUILD_NUMBER", "");
                case "BUILD_URL" -> context.getParam("BUILD_URL", "");
                case "BUILD_STATUS", "BUILD_RESULT" -> context.getParam("BUILD_STATUS", "");
                case "BUILD_LOG_EXCERPT" -> logExcerpt(context, argument);
                case "FAILED_TESTS" -> failedTests(workspace);
                case "CHANGES" -> changes(workspace, argument);
                case "CAUSE" -> context.getParam("BUILD_CAUSE", "");
                default -> null;
            };
            if (value == null) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(value));
            }
        }
        matcher.appendTail(out);
        // the plain $VAR form has no argument, so expand what the runner already knows
        return out.toString();
    }

    private static String logExcerpt(AtomContext context, String argument) {
        String text;
        try {
            text = context.hasBuildLog() ? context.buildLog() : "";
        } catch (Exception e) {
            return "(no build log available)";
        }
        if (text.isBlank()) {
            return "(no build log available)";
        }
        int lines = 50;
        if (argument != null && !argument.isBlank()) {
            try {
                lines = Integer.parseInt(argument.trim());
            } catch (NumberFormatException ignored) {
                // keep the default; a bad argument should not fail an email
            }
        }
        String[] all = text.split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, all.length - lines); i < all.length; i++) {
            out.append(all[i]).append('\n');
        }
        String excerpt = out.toString();
        return excerpt.length() > 8000 ? excerpt.substring(excerpt.length() - 8000) : excerpt;
    }

    /** The failed cases from the JUnit XML in the workspace, the way the plugin renders them. */
    private static String failedTests(Path workspace) throws IOException {
        List<PathMatcher> matchers = List.of(FileSystems.getDefault().getPathMatcher("glob:**/TEST-*.xml"));
        StringBuilder out = new StringBuilder();
        int shown = 0;
        try (var walk = Files.walk(workspace, 12)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(path -> matchers.get(0).matches(workspace.relativize(path)))
                    .sorted().limit(50).toList()) {
                for (String name : failing(file)) {
                    if (shown++ >= 20) {
                        return out.append("…\n").toString();
                    }
                    out.append("- ").append(name).append('\n');
                }
            }
        } catch (Exception ignored) {
            // no test reports is not an error for an email
        }
        return shown == 0 ? "(no failed tests found)" : out.toString();
    }

    private static List<String> failing(Path file) {
        List<String> names = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(file.toFile());
            NodeList cases = document.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element test = (Element) cases.item(i);
                if (test.getElementsByTagName("failure").getLength() > 0
                        || test.getElementsByTagName("error").getLength() > 0) {
                    names.add(test.getAttribute("classname") + "#" + test.getAttribute("name"));
                }
            }
        } catch (Exception ignored) {
            // unreadable report
        }
        return names;
    }

    private static String changes(Path workspace, String argument) {
        int count = 10;
        if (argument != null && !argument.isBlank()) {
            try {
                count = Integer.parseInt(argument.trim());
            } catch (NumberFormatException ignored) {
                // default
            }
        }
        try {
            ProcessBuilder builder = new ProcessBuilder("git", "log", "-" + Math.max(1, Math.min(count, 50)),
                    "--pretty=- %s (%an)").directory(workspace.toFile()).redirectErrorStream(true);
            Process process = builder.start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            process.waitFor();
            return out.isBlank() ? "(no commits)" : out;
        } catch (Exception e) {
            return "(git not available)";
        }
    }

    private static String message(String from, String to, String subject, String body, boolean html,
                                  List<Path> files) throws IOException {
        String boundary = "atom-" + Long.toHexString(System.nanoTime());
        StringBuilder out = new StringBuilder();
        out.append("From: ").append(from).append("\r\n");
        out.append("To: ").append(to).append("\r\n");
        out.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
        out.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())).append("\r\n");
        out.append("MIME-Version: 1.0\r\n");
        String type = html ? "text/html" : "text/plain";
        if (files.isEmpty()) {
            out.append("Content-Type: ").append(type).append("; charset=UTF-8\r\n");
            out.append("Content-Transfer-Encoding: base64\r\n\r\n");
            out.append(Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8))).append("\r\n");
            return out.toString();
        }
        out.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n");
        out.append("--").append(boundary).append("\r\n");
        out.append("Content-Type: ").append(type).append("; charset=UTF-8\r\n");
        out.append("Content-Transfer-Encoding: base64\r\n\r\n");
        out.append(Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8))).append("\r\n");
        for (Path file : files) {
            out.append("--").append(boundary).append("\r\n");
            out.append("Content-Type: application/octet-stream; name=\"").append(file.getFileName()).append("\"\r\n");
            out.append("Content-Disposition: attachment; filename=\"").append(file.getFileName()).append("\"\r\n");
            out.append("Content-Transfer-Encoding: base64\r\n\r\n");
            out.append(Base64.getMimeEncoder().encodeToString(Files.readAllBytes(file))).append("\r\n");
        }
        out.append("--").append(boundary).append("--\r\n");
        return out.toString();
    }

    private static String encodeHeader(String value) {
        if (value.chars().allMatch(c -> c < 128)) {
            return value;
        }
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static List<String> recipients(String value) {
        List<String> result = new ArrayList<>();
        for (String part : value.split("[,;\\s]+")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }
}

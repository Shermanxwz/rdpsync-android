package com.rdp.sync;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, content-minimal prerequisite evidence; consumes the entire command, never first-match. */
public final class FixtureDumpProjection {
    public static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_LINE_PREFIX = 8192;
    public static final int MAX_SELECTED_BYTES = 65536;
    private final String scope;
    private final MessageDigest digest;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final ByteArrayOutputStream selected = new ByteArrayOutputStream();
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private long sourceBytes, lines;
    private boolean longLine, inService, inSecure, closed;
    private int managerHeaders, clientHeaders, managerObservers, clientMethods;
    private String imeScope = "none";
    private int methodsInClient;
    private final java.util.Set<String> clientIdentities = new java.util.HashSet<>();
    private int headers, caches, navigation, selectedIds, boundIds, methodIds, secureSections, systemSections, rows, trailers;
    private String failure = "", rolling = "", lastLine = "", sourceHash = "";
    private int exitStatus = -1;
    private static final byte[] SETTING_NAME = " name:show_ime_with_hard_keyboard ".getBytes(StandardCharsets.US_ASCII);
    private int settingNameMatch;
    private boolean lineHasSettingName;

    public FixtureDumpProjection(String scope) {
        if (!scope.equals("ime") && !scope.equals("settings")) throw new IllegalArgumentException("Unknown scope");
        this.scope = scope;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    public static String shellScript(String command) {
        if (!command.equals("dumpsys -t 2 input_method") && !command.equals("dumpsys -t 2 settings"))
            throw new IllegalArgumentException("Unreviewed projection command");
        return "{ " + command + " 2>&1; status=$?; printf '\\nRDP_FIXTURE_DUMP_EXIT=%d\\n' \"$status\"; }\n";
    }

    public synchronized void accept(byte[] bytes, int length) {
        if (closed) throw new IllegalStateException("Projection already closed");
        if (length < 0 || length > bytes.length) throw new IllegalArgumentException("Invalid chunk");
        int retained = (int) Math.min(length, Math.max(0, MAX_SOURCE_BYTES + 1L - sourceBytes));
        digest.update(bytes, 0, length);
        sourceBytes += length;
        String scan = rolling + new String(bytes, 0, retained, StandardCharsets.ISO_8859_1);
        for (String marker : new String[]{"DUMP TIMEOUT", "Failed to dump", "Permission Denial", "Error dumping service"}) {
            if (scan.contains(marker)) fail("producer-error:" + marker);
        }
        rolling = scan.substring(Math.max(0, scan.length() - 64));
        for (int i = 0; i < retained; i++) {
            if (bytes[i] == SETTING_NAME[settingNameMatch]) {
                settingNameMatch++;
                if (settingNameMatch == SETTING_NAME.length) { lineHasSettingName = true; settingNameMatch = 0; }
            } else settingNameMatch = bytes[i] == SETTING_NAME[0] ? 1 : 0;
            if (bytes[i] == '\n') { consumeLine(); line.reset(); longLine = false; settingNameMatch = 0; lineHasSettingName = false; }
            else if (line.size() < MAX_LINE_PREFIX) line.write(bytes[i]);
            else longLine = true;
        }
        if (sourceBytes > MAX_SOURCE_BYTES) {
            fail("total-source-limit");
            throw new IllegalStateException(failure);
        }
    }

    private void fail(String reason) { if (failure.isEmpty()) failure = reason; }
    private void keep(String value) {
        byte[] raw = (value + "\n").getBytes(StandardCharsets.UTF_8);
        if (selected.size() + raw.length > MAX_SELECTED_BYTES) { fail("selected-output-limit"); return; }
        selected.write(raw, 0, raw.length);
    }
    private void consumeLine() {
        lines++;
        String raw = new String(line.toByteArray(), StandardCharsets.UTF_8);
        if (raw.endsWith("\r")) raw = raw.substring(0, raw.length()-1);
        String text = raw.trim();
        lastLine = text;
        if (longLine && text.isEmpty()) fail("ambiguous-long-whitespace-line");
        if (text.startsWith("RDP_FIXTURE_DUMP_EXIT=")) {
            trailers++;
            if (longLine || !text.matches("RDP_FIXTURE_DUMP_EXIT=[0-9]+")) fail("malformed-exit-trailer");
            else try { exitStatus = Integer.parseInt(text.substring(text.indexOf('=')+1)); }
            catch (NumberFormatException error) { fail("malformed-exit-trailer"); }
            return;
        }
        if (scope.equals("ime")) {
            boolean relevant = false;
            if (text.equals("Current Input Method Manager state:")) {
                if (!imeScope.equals("none")) fail("late-or-duplicate-manager-header");
                managerHeaders++; imeScope = "manager"; relevant = true;
            } else if (text.startsWith("Input method client state for ")) {
                if (managerHeaders != 1 || headers != 0 || inService) fail("client-header-out-of-order");
                if (imeScope.equals("client") && methodsInClient != 1) fail("incomplete-client-block");
                if (!clientIdentities.add(text)) fail("duplicate-client-header");
                clientHeaders++; methodsInClient = 0; imeScope = "client"; relevant = true;
            } else if (text.startsWith("Input method service state for ")) {
                if (managerHeaders != 1) fail("service-before-manager");
                if (imeScope.equals("client") && methodsInClient != 1) fail("incomplete-client-block");
                if (inService) fail("nested-service");
                headers++; inService = true; imeScope = "service"; relevant = true;
            } else if (text.startsWith("mCurMethodId=") || text.startsWith("mCurId=") || text.startsWith("mCurMethod=")) {
                if (imeScope.equals("manager")) {
                    if (text.startsWith("mCurMethodId=")) selectedIds++;
                    if (text.startsWith("mCurId=")) boundIds++;
                    if (text.startsWith("mCurMethod=")) methodIds++;
                    relevant = true;
                } else if (imeScope.equals("client") && text.startsWith("mCurMethod=")) {
                    // InputMethodManager client session proxy is not IMMS's bound method invoker.
                    clientMethods++; methodsInClient++;
                    if (methodsInClient > 1) fail("duplicate-client-method");
                } else fail("manager-identity-outside-manager");
            } else if (text.startsWith("mSettingsObserver=SettingsObserver{")) {
                if (imeScope.equals("manager") && text.startsWith("mSettingsObserver=SettingsObserver{mUserId=")) {
                    // IMMS settings observer is not InputMethodService's hardware-keyboard cache.
                    managerObservers++;
                } else if (imeScope.equals("service") && inService && text.startsWith("mSettingsObserver=SettingsObserver{mShowImeWithHardKeyboard=")) {
                    caches++; relevant = true;
                } else fail("observer-outside-service");
            } else if (text.startsWith("mNavigationBarController=")) {
                if (!inService || !imeScope.equals("service")) fail("completion-outside-service");
                navigation++; inService = false; relevant = true;
            }
            if (relevant) { if (longLine) fail("relevant-line-limit"); else keep(raw); }
        } else {
            if (text.matches("[A-Z]+ SETTINGS \\(user [0-9]+\\)")) {
                inSecure = text.equals("SECURE SETTINGS (user 0)");
                if (inSecure) { secureSections++; keep(raw); }
                if (text.equals("SYSTEM SETTINGS (user 0)")) { systemSections++; keep(raw); }
            } else if (inSecure && text.startsWith("_id:") && lineHasSettingName) {
                rows++;
                if (longLine) fail("relevant-line-limit"); else keep(raw);
            }
        }
    }

    public synchronized String finish(boolean eof) {
        if (!closed) {
            if (line.size() > 0 || longLine) consumeLine();
            closed = true;
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format("%02x", value & 255));
            sourceHash = hex.toString();
            if (!eof) fail("incomplete-producer");
            if (trailers != 1 || !lastLine.equals("RDP_FIXTURE_DUMP_EXIT=0") || exitStatus != 0) fail("missing-or-failed-exit-trailer");
            if (scope.equals("ime") && (managerHeaders != 1 || clientHeaders > 2 || managerObservers > 1 || clientMethods != clientHeaders || headers != 1 || caches != 1 || navigation != 1 || selectedIds != 1 || boundIds != 1 || methodIds != 1 || inService)) fail("nonunique-or-incomplete-ime");
            if (scope.equals("settings") && (secureSections != 1 || systemSections != 1 || rows != 1)) fail("nonunique-or-incomplete-settings");
            evidence.put("producer_eof", eof);
        }
        if (!failure.isEmpty()) throw new IllegalStateException(failure);
        return projection();
    }

    public synchronized String projection() { return new String(selected.toByteArray(), StandardCharsets.UTF_8); }
    public synchronized Map<String, Object> receipt() {
        Map<String, Object> result = new LinkedHashMap<>(evidence);
        result.put("scope", scope); result.put("source_bytes", sourceBytes); result.put("source_sha256", sourceHash);
        result.put("selected_bytes", selected.size()); result.put("lines", lines); result.put("exit_status", exitStatus);
        result.put("exit_trailers", trailers); result.put("source_limit", MAX_SOURCE_BYTES); result.put("line_prefix_limit", MAX_LINE_PREFIX);
        result.put("selected_limit", MAX_SELECTED_BYTES); result.put("service_headers", headers); result.put("observer_rows", caches);
        result.put("manager_headers", managerHeaders); result.put("client_headers", clientHeaders);
        result.put("manager_observer_rows", managerObservers); result.put("client_method_rows", clientMethods);
        result.put("service_completions", navigation); result.put("selected_ids", selectedIds); result.put("bound_ids", boundIds); result.put("method_ids", methodIds);
        result.put("secure_sections", secureSections); result.put("system_sections", systemSections); result.put("setting_rows", rows);
        result.put("complete", closed && failure.isEmpty()); result.put("failure", failure);
        return result;
    }
}

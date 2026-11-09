import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Yobareta Cloud-Tunnel & Local LAN Web Server.
 *
 * 1. Serves the Patient PWA static website and real-time Queue API on port 8080.
 * 2. Automatically opens a public HTTPS Cloud Tunnel (via localhost.run SSH) so QR codes
 *    generated inside the Android Emulator link to a real public `https://....lhr.life` website
 *    accessible from any smartphone (on Wi-Fi or 4G/5G mobile data).
 * 3. Exposes `/api/server-info` so the Android Emulator (`10.0.2.2:8080`) auto-discovers
 *    the public HTTPS URL without manual copy-pasting.
 */
public class LocalTestServer {

    static class PatientRecord {
        String sessionId;
        String name;
        String ticketNumber;
        String destination;
        List<String> readings = new ArrayList<>();
        String status = "waiting"; // waiting | called | recall
        long checkinTimeSec = System.currentTimeMillis() / 1000L;
        Long lastCalledTimeSec = null;
        int callCount = 0;
        String latestAlertJson = "null";

        String toSummaryJson() {
            long waitMin = Math.max(0L, ((System.currentTimeMillis() / 1000L) - checkinTimeSec) / 60L);
            return "{"
                + "\"session_id\":\"" + esc(sessionId) + "\","
                + "\"name\":\"" + esc(name) + "\","
                + "\"ticket_number\":\"" + esc(ticketNumber) + "\","
                + "\"status\":\"" + esc(status) + "\","
                + "\"destination\":\"" + esc(destination) + "\","
                + "\"call_count\":" + callCount + ","
                + "\"checkin_time\":" + checkinTimeSec + ","
                + "\"last_called_time\":" + (lastCalledTimeSec == null ? "null" : lastCalledTimeSec) + ","
                + "\"wait_minutes\":" + waitMin + ","
                + "\"latest_alert\":" + latestAlertJson
                + "}";
        }
    }

    private static final Map<String, PatientRecord> QUEUE = new ConcurrentHashMap<>();
    private static File staticDir;
    private static volatile String publicCloudUrl = "";
    private static volatile String primaryLanUrl = "http://localhost:8080";

    public static void main(String[] args) throws Exception {
        int port = 8080;
        boolean enableCloudTunnel = true;
        for (String arg : args) {
            if ("--no-tunnel".equalsIgnoreCase(arg)) {
                enableCloudTunnel = false;
            } else {
                try {
                    port = Integer.parseInt(arg);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        File candidate1 = new File("backend/static");
        File candidate2 = new File("static");
        staticDir = candidate1.exists() ? candidate1 : candidate2;

        List<String> lanIps = getLanIps();
        if (!lanIps.isEmpty()) {
            primaryLanUrl = "http://" + lanIps.get(0) + ":" + port;
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/healthz", ex -> sendJson(ex, 200, "{\"status\":\"ok\"}"));
        server.createContext("/warm", ex -> sendJson(ex, 200, "{\"status\":\"warm\",\"sudachi_ready\":true}"));
        server.createContext("/api/server-info", ex -> {
            String activeUrl = !publicCloudUrl.isEmpty() ? publicCloudUrl : primaryLanUrl;
            String json = "{"
                + "\"public_url\":\"" + esc(publicCloudUrl) + "\","
                + "\"lan_url\":\"" + esc(primaryLanUrl) + "\","
                + "\"recommended_qr_base_url\":\"" + esc(activeUrl) + "\""
                + "}";
            sendJson(ex, 200, json);
        });
        server.createContext("/api/patient/checkin", new CheckinHandler());
        server.createContext("/api/patient/", new PatientStatusHandler());
        server.createContext("/api/queue", new QueueListHandler());
        server.createContext("/api/nurse/simulate", new SimulateCallHandler());
        server.createContext("/api/nurse/announce", new AnnounceAudioHandler());
        server.createContext("/", new StaticFileHandler());

        server.start();

        System.out.println("==================================================================");
        System.out.println("  Yobareta Web Server is RUNNING on port " + port);
        System.out.println("  Local Wi-Fi URLs:");
        for (String ip : lanIps) {
            System.out.println("    -> http://" + ip + ":" + port);
        }
        System.out.println("==================================================================");

        if (enableCloudTunnel) {
            startPublicCloudTunnel(port);
        }
    }

    private static void startPublicCloudTunnel(int port) {
        Thread tunnelThread = new Thread(() -> {
            System.out.println("[CLOUD TUNNEL] Starting public HTTPS tunnel so any phone can scan QR codes...");
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    "ssh",
                    "-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null",
                    "-o", "ServerAliveInterval=30",
                    "-R", "80:localhost:" + port,
                    "nokey@localhost.run"
                );
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                Pattern urlPattern = Pattern.compile("(https://[a-zA-Z0-9.-]+\\.lhr\\.life)");

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Matcher m = urlPattern.matcher(line);
                        if (m.find()) {
                            String foundUrl = m.group(1);
                            if (!foundUrl.equals(publicCloudUrl)) {
                                publicCloudUrl = foundUrl;
                                System.out.println("\n******************************************************************");
                                System.out.println("  PUBLIC CLOUD WEBSITE URL IS LIVE:");
                                System.out.println("  >>>  " + publicCloudUrl + "  <<<");
                                System.out.println("  (The Android Emulator app will auto-detect this URL for QR codes!)");
                                System.out.println("******************************************************************\n");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("[CLOUD TUNNEL] Note: Public SSH tunnel unavailable (" + e.getMessage() + "). Using Local Wi-Fi URL: " + primaryLanUrl);
            }
        });
        tunnelThread.setDaemon(true);
        tunnelThread.start();
    }

    static class CheckinHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 200, "{}");
                return;
            }
            String body = readBody(ex);
            String sid = extractJsonString(body, "session_id");
            if (sid == null || sid.isEmpty()) {
                sid = UUID.randomUUID().toString().substring(0, 8);
            }
            String name = extractJsonString(body, "name");
            String ticket = extractJsonString(body, "ticket_number");
            String dest = extractJsonString(body, "destination");
            List<String> readings = extractJsonArray(body, "full_name_readings");

            PatientRecord rec = QUEUE.get(sid);
            if (rec == null) {
                rec = new PatientRecord();
                rec.sessionId = sid;
                rec.name = (name != null && !name.isEmpty()) ? name : ("Customer #" + (ticket != null ? ticket : ""));
                rec.ticketNumber = ticket != null ? ticket : "";
                rec.destination = (dest != null && !dest.isEmpty()) ? dest : "3番診察室 (Exam Room 3)";
                rec.readings = readings;
                QUEUE.put(sid, rec);
                System.out.println("[CHECK-IN] Registered customer: #" + rec.ticketNumber + " " + rec.name + " (sid=" + sid + ")");
            } else {
                if (name != null && !name.isEmpty()) rec.name = name;
                if (ticket != null && !ticket.isEmpty()) rec.ticketNumber = ticket;
                if (dest != null && !dest.isEmpty()) rec.destination = dest;
                if (!readings.isEmpty()) rec.readings = readings;
                System.out.println("[QR-SCAN] Customer phone connected: #" + rec.ticketNumber + " " + rec.name + " (sid=" + sid + ")");
            }

            sendJson(ex, 200, rec.toSummaryJson());
        }
    }

    static class PatientStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 200, "{}");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String sid = path.substring("/api/patient/".length()).replaceAll("/.*$", "").trim();

            if ("DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
                boolean removed = QUEUE.remove(sid) != null;
                sendJson(ex, 200, "{\"status\":\"ok\",\"removed\":" + removed + "}");
                return;
            }

            PatientRecord rec = QUEUE.get(sid);
            if (rec == null) {
                sendJson(ex, 404, "{\"detail\":\"Session not found\"}");
                return;
            }
            sendJson(ex, 200, rec.toSummaryJson());
        }
    }

    static class QueueListHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (PatientRecord r : QUEUE.values()) {
                if (!first) sb.append(",");
                sb.append(r.toSummaryJson());
                first = false;
            }
            sb.append("]");
            sendJson(ex, 200, sb.toString());
        }
    }

    static class SimulateCallHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 200, "{}");
                return;
            }
            String body = readBody(ex);
            String transcript = extractJsonString(body, "transcript");
            if (transcript == null) transcript = "";

            String responseJson = evaluateAndNotifyMatches(transcript);
            sendJson(ex, 200, responseJson);
        }
    }

    static class AnnounceAudioHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 200, "{}");
                return;
            }
            String transcript = "音声呼出 (Voice Call)";
            for (PatientRecord r : QUEUE.values()) {
                if ("waiting".equals(r.status)) {
                    transcript = r.ticketNumber + "番 " + r.name + "様、" + r.destination + "へお入りください";
                    break;
                }
            }
            String responseJson = evaluateAndNotifyMatches(transcript);
            sendJson(ex, 200, responseJson);
        }
    }

    private static String evaluateAndNotifyMatches(String transcript) {
        String normTranscript = transcript.replaceAll("[\\s　]+", "").toLowerCase();
        List<PatientRecord> matched = new ArrayList<>();

        for (PatientRecord r : QUEUE.values()) {
            boolean hit = false;
            String normName = r.name.replaceAll("[\\s　]+", "").toLowerCase();
            if (!normName.isEmpty() && normTranscript.contains(normName)) {
                hit = true;
            }
            String[] parts = r.name.trim().split("[\\s　]+");
            if (parts.length > 0 && parts[0].length() >= 2 && normTranscript.contains(parts[0].toLowerCase())) {
                hit = true;
            }
            if (!r.ticketNumber.isEmpty() && normTranscript.contains(r.ticketNumber.toLowerCase())) {
                hit = true;
            }
            for (String rd : r.readings) {
                String cleanRd = rd.replaceAll("[\\s　]+", "").toLowerCase();
                if (!cleanRd.isEmpty() && normTranscript.contains(cleanRd)) {
                    hit = true;
                    break;
                }
            }

            if (hit) {
                r.status = r.callCount > 0 ? "recall" : "called";
                r.callCount += 1;
                r.lastCalledTimeSec = System.currentTimeMillis() / 1000L;
                String dest = extractDestination(transcript, r.destination);
                r.destination = dest;
                String instr = "You were called (" + r.name + ") → " + dest;

                r.latestAlertJson = "{"
                    + "\"type\":\"alert\","
                    + "\"tier\":\"" + esc(r.status) + "\","
                    + "\"score\":0.98,"
                    + "\"matched_trigger\":\"" + esc(r.name) + "\","
                    + "\"destination\":\"" + esc(dest) + "\","
                    + "\"instruction_plain\":\"" + esc(instr) + "\","
                    + "\"call_count\":" + r.callCount + ","
                    + "\"timestamp\":" + r.lastCalledTimeSec
                    + "}";

                matched.add(r);
                System.out.println("[ALERT DISPATCHED] -> #" + r.ticketNumber + " " + r.name + " (" + r.status.toUpperCase() + ") to " + dest);
            }
        }

        StringBuilder matchesJson = new StringBuilder("[");
        for (int i = 0; i < matched.size(); i++) {
            PatientRecord m = matched.get(i);
            if (i > 0) matchesJson.append(",");
            matchesJson.append("{")
                .append("\"session_id\":\"").append(esc(m.sessionId)).append("\",")
                .append("\"name\":\"").append(esc(m.name)).append("\",")
                .append("\"ticket\":\"").append(esc(m.ticketNumber)).append("\",")
                .append("\"tier\":\"").append(esc(m.status)).append("\",")
                .append("\"destination\":\"").append(esc(m.destination)).append("\",")
                .append("\"instruction\":\"You were called → ").append(esc(m.destination)).append("\"")
                .append("}");
        }
        matchesJson.append("]");

        return "{"
            + "\"transcript\":\"" + esc(transcript) + "\","
            + "\"matches_count\":" + matched.size() + ","
            + "\"matches\":" + matchesJson
            + "}";
    }

    private static String extractDestination(String transcript, String fallback) {
        String[] rooms = {"1番診察室", "2番診察室", "3番診察室", "4番診察室", "5番診察室", "1番窓口", "2番窓口", "会計窓口", "お薬窓口"};
        for (String rm : rooms) {
            if (transcript.contains(rm)) {
                return rm;
            }
        }
        return (fallback != null && !fallback.isEmpty()) ? fallback : "3番診察室 (Exam Room 3)";
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                path = "/index.html";
            }

            File target = new File(staticDir, path.substring(1));
            if (!target.exists() || target.isDirectory()) {
                target = new File(staticDir, "index.html");
            }

            if (!target.exists()) {
                sendJson(ex, 404, "{\"detail\":\"File not found\"}");
                return;
            }

            String mime = getMimeType(target.getName());
            byte[] bytes = Files.readAllBytes(target.toPath());
            ex.getResponseHeaders().set("Content-Type", mime);
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static String getMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Firebase-AppCheck, X-Yobareta-Secret");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    private static String extractJsonString(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static List<String> extractJsonArray(String json, String key) {
        List<String> list = new ArrayList<>();
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (m.find()) {
            String inner = m.group(1);
            Matcher itemM = Pattern.compile("\"([^\"]+)\"").matcher(inner);
            while (itemM.find()) {
                list.add(itemM.group(1));
            }
        }
        return list;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static List<String> getLanIps() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface netint : Collections.list(nets)) {
                if (!netint.isUp() || netint.isLoopback()) continue;
                for (InetAddress inetAddress : Collections.list(netint.getInetAddresses())) {
                    if (inetAddress instanceof Inet4Address && !inetAddress.isLoopbackAddress()) {
                        ips.add(inetAddress.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return ips;
    }
}

package com.transcoder;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    static final int PORT = 8080;
    static final File UPLOAD_DIR = new File("uploads");
    static final Map<String, UploadSession> sessions = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        if (!UPLOAD_DIR.exists()) {
            UPLOAD_DIR.mkdirs();
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/upload/initiate", new InitiateHandler());
        server.createContext("/upload", new UploadHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("✅ Server started at http://localhost:" + PORT);
        System.out.println("POST /upload/initiate → create upload session");
        System.out.println("PUT  /upload/{uploadId} → upload chunk");
        System.out.println("POST /upload/{uploadId}/complete → finalize upload");
    }

    // ---------------- Initiate ----------------
    static class InitiateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String fileName = extractString(body, "fileName");
            Long fileSize = extractLong(body, "fileSize");
            String contentType = extractString(body, "contentType");

            if (fileName == null || fileSize == null) {
                sendJson(exchange, 400, "{\"error\":\"Missing required fields\"}");
                return;
            }

            String uploadId = UUID.randomUUID().toString();
            File partFile = new File(UPLOAD_DIR, uploadId + ".part");
            new RandomAccessFile(partFile, "rw").setLength(0);

            UploadSession session = new UploadSession(uploadId, fileName, fileSize,
                    contentType == null ? "application/octet-stream" : contentType,
                    partFile.getAbsolutePath());
            sessions.put(uploadId, session);

            String resp = String.format(
                    "{\"uploadId\":\"%s\",\"status\":\"UPLOADING\",\"uploadedBytes\":0,\"targetPath\":\"%s\"}",
                    uploadId, escapeJson(partFile.getPath())
            );
            sendJson(exchange, 200, resp);
        }
    }

    // ---------------- Upload & Complete ----------------
    static class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");

            // /upload/{uploadId}
            if (parts.length == 3 && "PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleChunkUpload(exchange, parts[2]);
                return;
            }

            // /upload/{uploadId}/complete
            if (parts.length == 4 && "complete".equals(parts[3]) &&
                    "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleComplete(exchange, parts[2]);
                return;
            }

            sendJson(exchange, 404, "{\"error\":\"Invalid path or method\"}");
        }

        private void handleChunkUpload(HttpExchange exchange, String uploadId) throws IOException {
            UploadSession session = sessions.get(uploadId);
            if (session == null) {
                sendJson(exchange, 404, "{\"error\":\"Upload session not found\"}");
                return;
            }

            String contentRange = exchange.getRequestHeaders().getFirst("Content-Range");
            if (contentRange == null || !contentRange.startsWith("bytes")) {
                sendJson(exchange, 411, "{\"error\":\"Missing Content-Range header\"}");
                return;
            }

            // Parse Content-Range: "bytes 0-5242879/10485760"
            long startByte, endByte, totalSize;
            try {
                String[] parts1 = contentRange.split(" ");
                String[] rangeAndTotal = parts1[1].split("/");
                String[] range = rangeAndTotal[0].split("-");
                startByte = Long.parseLong(range[0]);
                endByte = Long.parseLong(range[1]);
                totalSize = Long.parseLong(rangeAndTotal[1]);
            } catch (Exception e) {
                sendJson(exchange, 400, "{\"error\":\"Invalid Content-Range format\"}");
                return;
            }

            if (totalSize != session.fileSize) {
                sendJson(exchange, 400, "{\"error\":\"File size mismatch\"}");
                return;
            }

            byte[] chunk = exchange.getRequestBody().readAllBytes();
            long expectedLength = (endByte - startByte + 1);
            if (chunk.length != expectedLength) {
                sendJson(exchange, 400,
                        String.format("{\"error\":\"Chunk size mismatch. Expected %d, got %d\"}", expectedLength, chunk.length));
                return;
            }

            try (RandomAccessFile raf = new RandomAccessFile(session.partFilePath, "rw")) {
                raf.seek(startByte);
                raf.write(chunk);
            }

            session.uploadedBytes = Math.max(session.uploadedBytes, endByte + 1);

            String resp = String.format(
                    "{\"uploadId\":\"%s\",\"status\":\"IN_PROGRESS\",\"uploadedBytes\":%d,\"nextExpectedByte\":%d}",
                    session.uploadId, session.uploadedBytes, session.uploadedBytes
            );
            sendJson(exchange, 200, resp);
        }

        private void handleComplete(HttpExchange exchange, String uploadId) throws IOException {
            UploadSession session = sessions.get(uploadId);
            if (session == null) {
                sendJson(exchange, 404, "{\"error\":\"Upload session not found\"}");
                return;
            }

            File partFile = new File(session.partFilePath);
            if (!partFile.exists()) {
                sendJson(exchange, 404, "{\"error\":\"Part file not found\"}");
                return;
            }

            long actualSize = partFile.length();
            if (actualSize != session.fileSize) {
                sendJson(exchange, 400,
                        String.format("{\"error\":\"File size mismatch. Expected %d, got %d\"}", session.fileSize, actualSize));
                return;
            }

            File finalFile = new File(UPLOAD_DIR, uploadId + ".mp4");
            if (!partFile.renameTo(finalFile)) {
                sendJson(exchange, 500, "{\"error\":\"Failed to finalize file\"}");
                return;
            }

            session.uploadedBytes = actualSize;

            String resp = String.format(
                    "{\"uploadId\":\"%s\",\"status\":\"UPLOADED\",\"finalPath\":\"%s\",\"fileSize\":%d}",
                    uploadId, escapeJson(finalFile.getPath()), actualSize
            );
            sendJson(exchange, 200, resp);
        }
    }

    // ---------------- Helpers ----------------
    static void sendJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String extractString(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    static Long extractLong(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        if (m.find()) return Long.parseLong(m.group(1));
        return null;
    }

    static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static class UploadSession {
        final String uploadId;
        final String fileName;
        final long fileSize;
        final String contentType;
        final String partFilePath;
        volatile long uploadedBytes;
        final long createdAt;

        UploadSession(String uploadId, String fileName, long fileSize,
                      String contentType, String partFilePath) {
            this.uploadId = uploadId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.contentType = contentType;
            this.partFilePath = partFilePath;
            this.uploadedBytes = 0L;
            this.createdAt = System.currentTimeMillis();
        }
    }
}

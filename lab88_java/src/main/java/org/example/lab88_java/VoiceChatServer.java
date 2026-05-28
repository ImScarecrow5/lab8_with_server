package org.example.lab88_java;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class VoiceChatServer {
    private static final int PORT = 7777;
    private static final long CLIENT_TIMEOUT_MS = 120_000; // 2 минуты таймаут

    private static final ConcurrentHashMap<String, ClientInfo> clients = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private ServerSocket serverSocket;

    private static class ClientInfo {
        final String nickname, publicIp;
        final int tcpPort, udpPort;
        volatile long lastActivity;

        ClientInfo(String nick, String ip, int tcp, int udp) {
            this.nickname = nick; this.publicIp = ip;
            this.tcpPort = tcp; this.udpPort = udp;
            this.lastActivity = System.currentTimeMillis();
        }
        void ping() { lastActivity = System.currentTimeMillis(); }
        boolean isAlive() { return System.currentTimeMillis() - lastActivity < CLIENT_TIMEOUT_MS; }
    }

    public static void main(String[] args) { new VoiceChatServer().start(); }

    public void start() {
        running = true;
        System.out.println("🚀 VoiceChat Server: порт " + PORT);

        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleAtFixedRate(() -> {
            clients.entrySet().removeIf(e -> !e.getValue().isAlive());
            System.out.println("🧹 Онлайн: " + clients.size());
        }, 30, 30, TimeUnit.SECONDS);

        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("✅ Готов к подключениям");
            while (running) {
                Socket client = serverSocket.accept();
                final String clientAddr = client.getInetAddress().getHostAddress();
                System.out.println("🔌 Подключение: " + clientAddr);
                new Thread(() -> handleClient(client, clientAddr), "Handler").start();
            }
        } catch (IOException e) { if (running) System.err.println(" Ошибка: " + e.getMessage()); }
        finally { cleaner.shutdown(); stop(); }
    }

    private void handleClient(Socket client, String clientPublicIp) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {

            String line;
            while ((line = in.readLine()) != null) {
                final String[] parts = line.split("\\|", 4);
                if (parts.length < 1) continue;

                final String cmd = parts[0].trim().toUpperCase();
                final ClientInfo info = clients.get(parts.length > 1 ? parts[1] : "");

                switch (cmd) {
                    case "REGISTER" -> handleRegister(out, parts, clientPublicIp);
                    case "LOOKUP" -> handleLookup(out, parts);
                    case "CALL" -> handleCall(out, parts, info);
                    case "CALL_ACCEPT" -> handleCallAccept(out, parts);
                    case "PING" -> { if (info != null) info.ping(); out.println("PONG"); }
                    case "UNREGISTER" -> clients.remove(parts[1]);
                }
            }
        } catch (IOException e) { System.out.println("⚠️ Клиент отключился: " + clientPublicIp); }
        finally {
            clients.values().removeIf(c -> c.publicIp.equals(clientPublicIp));
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private void handleRegister(PrintWriter out, String[] parts, String clientPublicIp) {
        if (parts.length < 4) { out.println("ERROR|REGISTER|Nick|TcpPort|UdpPort"); return; }
        try {
            final String nick = parts[1].trim();
            final int tcp = Integer.parseInt(parts[2].trim());
            final int udp = Integer.parseInt(parts[3].trim());

            if (clients.containsKey(nick) && clients.get(nick).isAlive()) {
                out.println("ERROR|Ник занят"); return;
            }
            clients.put(nick, new ClientInfo(nick, clientPublicIp, tcp, udp));
            System.out.println("✅ REGISTER: " + nick + " @ " + clientPublicIp);
            out.println("OK|Зарегистрирован");
        } catch (NumberFormatException e) { out.println("ERROR|Неверный порт"); }
    }

    private void handleLookup(PrintWriter out, String[] parts) {
        if (parts.length < 2) { out.println("ERROR|LOOKUP|Nick"); return; }
        final String targetNick = parts[1].trim();
        final ClientInfo target = clients.get(targetNick);

        if (target != null && target.isAlive()) {
            out.println("FOUND|" + target.publicIp + "|" + target.tcpPort + "|" + target.udpPort);
            System.out.println("🔍 LOOKUP: " + targetNick + " → найден");
        } else {
            out.println("NOT_FOUND");
            System.out.println(" LOOKUP: " + targetNick + " → не найден");
        }
    }

    private void handleCall(PrintWriter out, String[] parts, ClientInfo caller) {
        if (parts.length < 3 || caller == null) { out.println("ERROR|CALL|Params"); return; }
        final String targetNick = parts[1].trim();
        final int callerUdp = Integer.parseInt(parts[2].trim());
        final ClientInfo target = clients.get(targetNick);

        if (target == null || !target.isAlive()) { out.println("ERROR|Оффлайн"); return; }
        out.println("CALL_DATA|" + target.publicIp + "|" + target.tcpPort + "|" + target.udpPort + "|" + callerUdp);
        System.out.println("📞 CALL: " + caller.nickname + " → " + targetNick);
    }

    private void handleCallAccept(PrintWriter out, String[] parts) {
        if (parts.length < 3) { out.println("ERROR|CALL_ACCEPT|Params"); return; }
        out.println("CALL_ACCEPTED|" + parts[2].trim());
        System.out.println("✅ CALL_ACCEPT: " + parts[1]);
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        clients.clear();
    }
}
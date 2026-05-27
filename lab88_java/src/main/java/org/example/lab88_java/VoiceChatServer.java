package org.example.lab88_java;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class VoiceChatServer {
    private static final int PORT = 7777;
    private static final long CLIENT_TIMEOUT_MS = 60_000;

    private static final ConcurrentHashMap<String, ClientInfo> clients = new ConcurrentHashMap<>();

    private volatile boolean running = false;
    private ServerSocket serverSocket;

    private static class ClientInfo {
        final String nickname;
        final String publicIp;
        final int tcpPort;
        final int udpPort;
        volatile long lastActivity;

        ClientInfo(String nick, String ip, int tcp, int udp) {
            this.nickname = nick;
            this.publicIp = ip;
            this.tcpPort = tcp;
            this.udpPort = udp;
            this.lastActivity = System.currentTimeMillis();
        }

        void ping() { lastActivity = System.currentTimeMillis(); }
        boolean isAlive() {
            return System.currentTimeMillis() - lastActivity < CLIENT_TIMEOUT_MS;
        }
    }

    public static void main(String[] args) {
        new VoiceChatServer().start();
    }

    public void start() {
        running = true;
        System.out.println("VoiceChat Server: port " + PORT);
        System.out.println("Public IP: " + getPublicIpHint());

        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleAtFixedRate(() -> {
            clients.entrySet().removeIf(e -> !e.getValue().isAlive());
            System.out.println("Online: " + clients.size());
        }, 30, 30, TimeUnit.SECONDS);

        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("Server ready");

            while (running) {
                Socket client = serverSocket.accept();
                String clientAddr = client.getInetAddress().getHostAddress();
                System.out.println("Connect: " + clientAddr);
                new Thread(() -> handleClient(client, clientAddr), "Handler").start();
            }
        } catch (IOException e) {
            if (running) System.err.println("Error: " + e.getMessage());
        } finally {
            cleaner.shutdown();
            stop();
        }
    }

    private void handleClient(Socket client, String clientPublicIp) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {

            String line;
            while ((line = in.readLine()) != null) {
                String[] parts = line.split("\\|", 4);
                if (parts.length < 1) continue;

                String cmd = parts[0].trim().toUpperCase();
                ClientInfo info = clients.get(parts.length > 1 ? parts[1] : "");

                switch (cmd) {
                    case "REGISTER" -> handleRegister(out, parts, clientPublicIp);
                    case "LOOKUP" -> handleLookup(out, parts);
                    case "CALL" -> handleCall(out, parts, info);
                    case "CALL_ACCEPT" -> handleCallAccept(out, parts);
                    case "PING" -> {
                        if (info != null) info.ping();
                        out.println("PONG");
                    }
                    case "UNREGISTER" -> handleUnregister(out, parts);
                    default -> out.println("ERROR|Unknown command");
                }
            }
        } catch (IOException e) {
            System.out.println("Client disconnected: " + clientPublicIp);
        } finally {
            clients.values().removeIf(c -> c.publicIp.equals(clientPublicIp));
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private void handleRegister(PrintWriter out, String[] parts, String clientPublicIp) {
        if (parts.length < 4) {
            out.println("ERROR|Need: Nick|TcpPort|UdpPort");
            return;
        }
        try {
            String nick = parts[1];
            int tcpPort = Integer.parseInt(parts[2]);
            int udpPort = Integer.parseInt(parts[3]);

            if (clients.containsKey(nick) && clients.get(nick).isAlive()) {
                out.println("ERROR|Nick taken");
                return;
            }

            clients.put(nick, new ClientInfo(nick, clientPublicIp, tcpPort, udpPort));
            System.out.println("REGISTER: " + nick + " @ " + clientPublicIp);
            out.println("OK|Registered as " + nick);
        } catch (NumberFormatException e) {
            out.println("ERROR|Invalid port");
        }
    }

    private void handleLookup(PrintWriter out, String[] parts) {
        if (parts.length < 2) {
            out.println("ERROR|Need: Nick");
            return;
        }
        String targetNick = parts[1];
        ClientInfo target = clients.get(targetNick);

        if (target != null && target.isAlive()) {
            out.println("FOUND|" + target.publicIp + "|" + target.tcpPort + "|" + target.udpPort);
            System.out.println("LOOKUP: " + targetNick + " -> found");
        } else {
            out.println("NOT_FOUND");
        }
    }

    private void handleCall(PrintWriter out, String[] parts, ClientInfo caller) {
        if (parts.length < 3 || caller == null) {
            out.println("ERROR|Need: TargetNick|CallerUdp");
            return;
        }
        String targetNick = parts[1];
        int callerUdp = Integer.parseInt(parts[2]);
        ClientInfo target = clients.get(targetNick);

        if (target == null || !target.isAlive()) {
            out.println("ERROR|User offline");
            return;
        }
        out.println("CALL_DATA|" + target.publicIp + "|" + target.tcpPort + "|" + target.udpPort + "|" + callerUdp);
    }

    private void handleCallAccept(PrintWriter out, String[] parts) {
        if (parts.length < 3) {
            out.println("ERROR|Need: TargetNick|TargetUdp");
            return;
        }
        String targetNick = parts[1];
        int targetUdp = Integer.parseInt(parts[2]);
        out.println("CALL_ACCEPTED|" + targetUdp);
        System.out.println("CALL_ACCEPT: " + targetNick);
    }

    private void handleUnregister(PrintWriter out, String[] parts) {
        if (parts.length < 2) {
            out.println("ERROR|Need: Nick");
            return;
        }
        clients.remove(parts[1]);
        System.out.println("UNREGISTER: " + parts[1]);
        out.println("OK|Removed");
    }

    private String getPublicIpHint() {
        try {
            URL url = new URL("https://api.ipify.org");
            BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()));
            String ip = in.readLine();
            in.close();
            if (ip != null && !ip.isEmpty()) return ip;
        } catch (Exception ignored) {}
        return "Check 2ip.ru";
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        clients.clear();
    }
}
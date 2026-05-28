package org.example.lab88_java;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class VoiceChatServer {
    private static final int TCP_PORT = 7777;
    private static final long CLIENT_TIMEOUT_MS = 60_000;

    private final ConcurrentHashMap<String, ClientInfo> clients = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private DatagramSocket udpRelaySocket;

    private static class ClientInfo {
        final String nickname;
        final String publicIp;
        final int tcpPort;
        final int udpPort;
        final PrintWriter out;
        volatile long lastActivity;
        volatile InetSocketAddress udpEndpoint; // запоминаем, с какого адреса клиент шлёт UDP

        ClientInfo(String nick, String ip, int tcp, int udp, PrintWriter out) {
            this.nickname = nick; this.publicIp = ip;
            this.tcpPort = tcp; this.udpPort = udp;
            this.out = out;
            this.lastActivity = System.currentTimeMillis();
        }
        void ping() { lastActivity = System.currentTimeMillis(); }
        boolean isAlive() { return System.currentTimeMillis() - lastActivity < CLIENT_TIMEOUT_MS; }
    }

    public static void main(String[] args) { new VoiceChatServer().start(); }

    public void start() {
        running = true;
        System.out.println("VoiceChat Relay Server запущен на TCP порту " + TCP_PORT);
        System.out.println("   Публичный IP: " + getPublicIpHint());

        // Запускаем UDP‑ретранслятор на том же порту, что и TCP (или можно отдельный)
        try {
            udpRelaySocket = new DatagramSocket(TCP_PORT);
            new Thread(this::udpRelayLoop, "UDP‑Relay").start();
        } catch (SocketException e) {
            System.err.println("Не удалось открыть UDP сокет: " + e.getMessage());
            return;
        }

        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleAtFixedRate(() -> {
            clients.entrySet().removeIf(e -> !e.getValue().isAlive());
            System.out.println("Очистка: онлайн " + clients.size() + " клиентов");
        }, 30, 30, TimeUnit.SECONDS);

        try {
            serverSocket = new ServerSocket(TCP_PORT);
            System.out.println("Сервер готов к подключениям");
            while (running) {
                Socket client = serverSocket.accept();
                String clientAddr = client.getInetAddress().getHostAddress();
                System.out.println("TCP подключение от: " + clientAddr);
                new Thread(() -> handleClient(client, clientAddr), "TCPHandler").start();
            }
        } catch (IOException e) {
            if (running) System.err.println("Ошибка сервера: " + e.getMessage());
        } finally {
            cleaner.shutdown();
            stop();
        }
    }

    // Пересылает UDP пакет от одного клиента другому
    private void udpRelayLoop() {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                udpRelaySocket.receive(packet);
                // Определяем, от кого пришёл пакет
                InetSocketAddress senderAddr = new InetSocketAddress(packet.getAddress(), packet.getPort());
                ClientInfo sender = findClientByUdpEndpoint(senderAddr);
                if (sender == null) {
                    // возможно, клиент ещё не зарегистрировал UDP endpoint – игнорируем
                    continue;
                }
                // Ищем активный вызов для этого отправителя
                ClientInfo target = getCallTarget(sender.nickname);
                if (target != null && target.udpEndpoint != null) {
                    // Пересылаем пакет целевому клиенту
                    DatagramPacket forward = new DatagramPacket(
                            packet.getData(), packet.getLength(),
                            target.udpEndpoint.getAddress(), target.udpEndpoint.getPort()
                    );
                    udpRelaySocket.send(forward);
                }
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }
    }

    private ClientInfo findClientByUdpEndpoint(InetSocketAddress endpoint) {
        for (ClientInfo c : clients.values()) {
            if (endpoint.equals(c.udpEndpoint)) return c;
        }
        return null;
    }

    // Временное хранилище активных вызовов: caller -> callee
    private final ConcurrentHashMap<String, String> activeCalls = new ConcurrentHashMap<>();

    private ClientInfo getCallTarget(String callerNick) {
        String targetNick = activeCalls.get(callerNick);
        if (targetNick == null) return null;
        return clients.get(targetNick);
    }

    private void handleClient(Socket client, String clientPublicIp) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {

            String line;
            while ((line = in.readLine()) != null) {
                String[] parts = line.split("\\|", 4);
                if (parts.length < 1) continue;
                String cmd = parts[0].trim().toUpperCase();

                switch (cmd) {
                    case "REGISTER" -> handleRegister(out, parts, clientPublicIp);
                    case "LOOKUP" -> handleLookup(out, parts);
                    case "CALL" -> handleCall(out, parts);
                    case "CALL_ACCEPT" -> handleCallAccept(parts);
                    case "CALL_REJECT" -> handleCallReject(parts);
                    case "PING" -> {
                        ClientInfo info = clients.get(parts[1]);
                        if (info != null) info.ping();
                        out.println("PONG");
                    }
                    case "UNREGISTER" -> clients.remove(parts[1]);
                    case "SET_UDP_ENDPOINT" -> handleSetUdpEndpoint(parts, client.getInetAddress(), client.getPort());
                    default -> out.println("ERROR|Неизвестная команда");
                }
            }
        } catch (IOException e) {
            System.out.println("Клиент отключился: " + clientPublicIp);
        } finally {
            clients.values().removeIf(c -> c.publicIp.equals(clientPublicIp));
        }
    }

    private void handleRegister(PrintWriter out, String[] parts, String clientPublicIp) {
        if (parts.length < 4) { out.println("ERROR|Нужно: Nick|TcpPort|UdpPort"); return; }
        try {
            String nick = parts[1];
            int tcpPort = Integer.parseInt(parts[2]);
            int udpPort = Integer.parseInt(parts[3]);
            if (clients.containsKey(nick) && clients.get(nick).isAlive()) {
                out.println("ERROR|Ник уже занят"); return;
            }
            ClientInfo ci = new ClientInfo(nick, clientPublicIp, tcpPort, udpPort, out);
            clients.put(nick, ci);
            System.out.println("REGISTER: " + nick + " @ " + clientPublicIp);
            out.println("OK|Зарегистрирован как " + nick);
        } catch (NumberFormatException e) { out.println("ERROR|Неверный формат порта"); }
    }

    private void handleLookup(PrintWriter out, String[] parts) {
        if (parts.length < 2) { out.println("ERROR|Нужно: Nick"); return; }
        String targetNick = parts[1];
        ClientInfo target = clients.get(targetNick);
        if (target != null && target.isAlive()) {
            out.println("FOUND|" + target.publicIp + "|" + target.tcpPort + "|" + target.udpPort);
            System.out.println("LOOKUP: " + targetNick + " -> найден");
        } else { out.println("NOT_FOUND"); }
    }

    private void handleCall(PrintWriter out, String[] parts) {
        if (parts.length < 3) { out.println("ERROR|Неверные параметры"); return; }
        String callerNick = parts[1];
        int callerUdp = Integer.parseInt(parts[2]); // не используется напрямую, но клиент передаёт
        ClientInfo caller = clients.get(callerNick);
        if (caller == null || !caller.isAlive()) {
            out.println("ERROR|Вы не зарегистрированы"); return;
        }
        String targetNick = parts.length > 3 ? parts[3] : null; // если передали
        if (targetNick == null) {
            out.println("ERROR|Не указан целевой ник"); return;
        }
        ClientInfo target = clients.get(targetNick);
        if (target == null || !target.isAlive()) {
            out.println("ERROR|Пользователь не в сети"); return;
        }

        // Запоминаем, что вызов начат (caller -> target)
        activeCalls.put(callerNick, targetNick);
        // Уведомляем вызываемого клиента о входящем вызове
        target.out.println("INCOMING_CALL|" + caller.nickname + "|" + caller.publicIp + "|" + caller.tcpPort + "|" + callerUdp);
        out.println("CALL_DATA|" + target.publicIp + "|" + target.tcpPort + "|" + target.udpPort + "|" + callerUdp);
        System.out.println("CALL: " + caller.nickname + " -> " + targetNick);
    }

    private void handleCallAccept(String[] parts) {
        if (parts.length < 3) return;
        String targetNick = parts[1]; // принимающий
        String callerNick = parts[2]; // звонящий
        ClientInfo caller = clients.get(callerNick);
        if (caller != null) {
            caller.out.println("CALL_ACCEPTED|READY");
        }
        // Добавляем обратную связь, чтобы оба могли отправлять аудио
        activeCalls.put(targetNick, callerNick);
    }

    private void handleCallReject(String[] parts) {
        if (parts.length < 2) return;
        String targetNick = parts[1];
        ClientInfo caller = clients.get(targetNick);
        if (caller != null) caller.out.println("CALL_REJECTED");
        activeCalls.remove(targetNick);
    }

    private void handleSetUdpEndpoint(String[] parts, InetAddress clientAddr, int clientPort) {
        if (parts.length < 2) return;
        String nick = parts[1];
        ClientInfo ci = clients.get(nick);
        if (ci != null) {
            ci.udpEndpoint = new InetSocketAddress(clientAddr, clientPort);
            System.out.println("UDP endpoint для " + nick + " = " + ci.udpEndpoint);
        }
    }

    private String getPublicIpHint() {
        try (java.util.Scanner s = new java.util.Scanner(new java.net.URL("https://api.ipify.org").openStream(), "UTF-8").useDelimiter("\\A")) {
            String ip = s.hasNext() ? s.next() : null;
            return ip != null && !ip.isEmpty() ? ip : "Не удалось определить";
        } catch (Exception ignored) { return "Не удалось определить"; }
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (udpRelaySocket != null) udpRelaySocket.close();
        clients.clear();
        System.out.println("Сервер остановлен");
    }
}
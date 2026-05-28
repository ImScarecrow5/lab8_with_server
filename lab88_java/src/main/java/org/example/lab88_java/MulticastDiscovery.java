package org.example.lab88_java;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;

public class MulticastDiscovery {
    private static final String GROUP = "230.0.0.1";
    private static final int PORT = 8888;

    private final String myNickname, myIp;
    private final int myTcpPort;
    private final ConcurrentMap<String, PeerInfo> peers = new ConcurrentHashMap<>();
    private MulticastSocket socket;
    private volatile boolean running = false;
    private ScheduledExecutorService scheduler;

    public MulticastDiscovery(String nickname, int tcpPort) throws IOException {
        this.myNickname = nickname;
        this.myTcpPort = tcpPort;
        this.myIp = InetAddress.getLocalHost().getHostAddress();
        this.socket = new MulticastSocket(PORT);
        this.socket.joinGroup(InetAddress.getByName(GROUP));
        this.socket.setSoTimeout(1000);
    }

    public void start() {
        running = true;
        new Thread(this::listenLoop, "MulticastListener").start();

        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::broadcastHello, 0, 3, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::checkHeartbeat, 5, 5, TimeUnit.SECONDS);
    }

    private void listenLoop() {
        byte[] buf = new byte[1024];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                String msg = new String(p.getData(), 0, p.getLength()).trim();

                if (msg.startsWith("HELLO|")) {
                    String[] parts = msg.split("\\|");
                    if (parts.length == 4) {
                        String nick = parts[1];
                        String ip = parts[2];
                        int port = Integer.parseInt(parts[3]);

                        peers.put(nick, new PeerInfo(nick, ip, port, (int) System.currentTimeMillis()));
                    }
                }
            } catch (IOException ignored) {}
        }
    }

    private void broadcastHello() {
        try {
            String msg = "HELLO|" + myNickname + "|" + myIp + "|" + myTcpPort;
            socket.send(new DatagramPacket(msg.getBytes(), msg.length(), InetAddress.getByName(GROUP), PORT));
        } catch (IOException ignored) {}
    }

    private void checkHeartbeat() {
        long now = System.currentTimeMillis();
        peers.entrySet().removeIf(e -> now - e.getValue().lastSeen > 15000);
    }

    public ConcurrentMap<String, PeerInfo> getPeers() {
        return peers;
    }

    public void stop() {
        running = false;
        if (scheduler != null) scheduler.shutdownNow();
        socket.close();
    }
}
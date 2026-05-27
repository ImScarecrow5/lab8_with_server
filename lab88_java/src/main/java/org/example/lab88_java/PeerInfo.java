package org.example.lab88_java;

public class PeerInfo {
    public final String nickname;
    public final String ip;
    public final int tcpPort;
    public final int udpPort;
    public volatile long lastSeen;

    public PeerInfo(String nickname, String ip, int tcpPort, int udpPort) {
        this.nickname = nickname;
        this.ip = ip;
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;
        this.lastSeen = System.currentTimeMillis();
    }

    public void refresh() {
        lastSeen = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return nickname + " (" + ip + ":" + tcpPort + ")";
    }
}
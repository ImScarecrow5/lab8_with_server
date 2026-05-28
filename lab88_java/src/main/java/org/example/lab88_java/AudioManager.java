package org.example.lab88_java;

import javax.sound.sampled.*;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioManager {
    private final AudioFormat FORMAT = new AudioFormat(8000f, 16, 1, true, false);
    private final int udpPort;
    private DatagramSocket socket;
    private TargetDataLine mic;
    private SourceDataLine speaker;
    private volatile boolean running = false;
    private final AtomicBoolean isTalking = new AtomicBoolean(false);
    private InetAddress remoteIp;
    private int remoteUdpPort;
    private InetAddress serverIp;
    private int serverUdpPort;

    // Флаг успешной инициализации
    private boolean initialized = false;

    public void setServerTarget(InetAddress ip, int port) {
        this.serverIp = ip;
        this.serverUdpPort = port;
    }


    public AudioManager(int udpPort) {
        this.udpPort = udpPort;
        try {
            this.socket = new DatagramSocket(udpPort);
            this.mic = AudioSystem.getTargetDataLine(FORMAT);
            this.speaker = AudioSystem.getSourceDataLine(FORMAT);
            this.initialized = true;
        } catch (LineUnavailableException e) {
            System.err.println("❌ Аудиоустройство недоступно: " + e.getMessage());
        } catch (SocketException e) {
            System.err.println("❌ Ошибка сокета: " + e.getMessage());
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void startCall(InetAddress remoteIp, int remoteUdp) throws LineUnavailableException {
        if (!initialized) {
            System.err.println("❌ AudioManager не инициализирован");
            return;
        }
        this.remoteIp = remoteIp;
        this.remoteUdpPort = remoteUdp;
        this.running = true;
        mic.open(FORMAT);
        mic.start();
        speaker.open(FORMAT);
        speaker.start();
        new Thread(this::captureAndSend).start();
        new Thread(this::receiveAndPlay).start();
    }

    public void stopCall() {
        this.running = false;
        if (mic != null && mic.isOpen()) {
            mic.stop();
            mic.close();
        }
        if (speaker != null && speaker.isOpen()) {
            speaker.stop();
            speaker.close();
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    private void captureAndSend() {
        byte[] buf = new byte[1024];
        while (running) {
            if (isTalking.get()) {
                int count = mic.read(buf, 0, buf.length);
                if (count > 0) {
                    try {
                        InetAddress targetIp = (serverIp != null) ? serverIp : remoteIp;
                        int targetPort = (serverIp != null) ? serverUdpPort : remoteUdpPort;
                        socket.send(new DatagramPacket(buf, count, targetIp, targetPort));
                    } catch (IOException e) {
                        break;
                    }
                }
            } else {
                try { Thread.sleep(10); } catch (InterruptedException e) { break; }
            }
        }
    }

    private void receiveAndPlay() {
        byte[] buf = new byte[1024];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                speaker.write(p.getData(), 0, p.getLength());
            } catch (IOException e) {
                break;
            }
        }
    }

    public void setTalking(boolean t) {
        isTalking.set(t);
    }

    public int getUdpPort() {
        return udpPort;
    }
}
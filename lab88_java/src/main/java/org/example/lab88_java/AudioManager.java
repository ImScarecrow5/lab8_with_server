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
    private boolean initialized = false;

    private InetAddress serverIp;
    private int serverPort;

    public AudioManager(int udpPort) {
        this.udpPort = udpPort;
        try {
            this.socket = new DatagramSocket(udpPort);
            System.out.println("AudioManager: запрошен порт " + udpPort + ", реальный порт: " + socket.getLocalPort());
            if (socket.getLocalPort() != udpPort) {
                System.err.println("ВНИМАНИЕ: не удалось открыть порт " + udpPort + ", используется " + socket.getLocalPort());
            }
            this.mic = AudioSystem.getTargetDataLine(FORMAT);
            this.speaker = AudioSystem.getSourceDataLine(FORMAT);
            this.initialized = true;
            System.out.println("AudioManager инициализирован на порту " + udpPort);
        } catch (LineUnavailableException e) {
            System.err.println("❌ Аудиоустройство недоступно: " + e.getMessage());
        } catch (SocketException e) {
            System.err.println("❌ Ошибка сокета: " + e.getMessage());
        }
    }

    public boolean isInitialized() { return initialized; }
    public int getUdpPort() { return udpPort; }

    public void setServerTarget(InetAddress ip, int port) {
        this.serverIp = ip;
        this.serverPort = port;
        System.out.println("AudioManager настроен на сервер " + ip + ":" + port);
    }

    public void sendDummyPacket(InetAddress targetIp, int targetPort) throws IOException {
        byte[] dummy = new byte[0];
        DatagramPacket p = new DatagramPacket(dummy, 0, targetIp, targetPort);
        socket.send(p);
        System.out.println("Дамми-пакет отправлен на " + targetIp + ":" + targetPort);
    }

    public void startRelay() throws LineUnavailableException {
        if (!initialized) {
            System.err.println("AudioManager не инициализирован");
            return;
        }
        if (running) {
            System.out.println("AudioManager уже работает");
            return;
        }
        running = true;
        mic.open(FORMAT);
        mic.start();
        speaker.open(FORMAT);
        speaker.start();
        new Thread(this::captureAndSend).start();
        new Thread(this::receiveAndPlay).start();
        System.out.println("Audio relay started");
    }

    public void startCall(InetAddress remoteIp, int remoteUdp) throws LineUnavailableException {
        if (!initialized) return;
        if (running) stopCall();
        this.remoteIp = remoteIp;
        this.remoteUdpPort = remoteUdp;
        this.running = true;
        mic.open(FORMAT);
        mic.start();
        speaker.open(FORMAT);
        speaker.start();
        new Thread(this::captureAndSend).start();
        new Thread(this::receiveAndPlay).start();
        System.out.println("Direct call started to " + remoteIp + ":" + remoteUdp);
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
        System.out.println("Audio call stopped");
    }

    private void captureAndSend() {
        byte[] buf = new byte[1024];
        long lastLog = System.currentTimeMillis();
        int totalSent = 0;
        while (running) {
            if (isTalking.get()) {
                int count = mic.read(buf, 0, buf.length);
                if (count > 0) {
                    try {
                        InetAddress targetIp;
                        int targetPort;
                        if (serverIp != null) {
                            targetIp = serverIp;
                            targetPort = serverPort;
                        } else {
                            targetIp = remoteIp;
                            targetPort = remoteUdpPort;
                        }
                        socket.send(new DatagramPacket(buf, count, targetIp, targetPort));
                        totalSent += count;
                        if (System.currentTimeMillis() - lastLog > 2000) {
                            System.out.println("Отправлено байт за сек: " + totalSent);
                            totalSent = 0;
                            lastLog = System.currentTimeMillis();
                        }
                    } catch (IOException e) {
                        System.err.println("Ошибка отправки: " + e);
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
        long lastLog = System.currentTimeMillis();
        int totalReceived = 0;
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                speaker.write(p.getData(), 0, p.getLength());
                totalReceived += p.getLength();
                if (System.currentTimeMillis() - lastLog > 2000) {
                    System.out.println("Получено байт за сек: " + totalReceived);
                    totalReceived = 0;
                    lastLog = System.currentTimeMillis();
                }
            } catch (IOException e) {
                if (running) System.err.println("Ошибка приёма: " + e);
                break;
            }
        }
    }

    public void setTalking(boolean t) {
        isTalking.set(t);
    }
}
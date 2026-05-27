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

    public AudioManager(int udpPort) throws LineUnavailableException, SocketException {
        this.udpPort = udpPort;
        this.socket = new DatagramSocket(udpPort);
        this.mic = AudioSystem.getTargetDataLine(FORMAT);
        this.speaker = AudioSystem.getSourceDataLine(FORMAT);
    }

    public void startCall(InetAddress remoteIp, int remoteUdp) throws LineUnavailableException {
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
        if (mic.isOpen()) { mic.stop(); mic.close(); }
        if (speaker.isOpen()) { speaker.stop(); speaker.close(); }
    }

    private void captureAndSend() {
        byte[] buf = new byte[1024];
        while (running) {
            if (isTalking.get()) {
                int count = mic.read(buf, 0, buf.length);
                if (count > 0) {
                    try {
                        socket.send(new DatagramPacket(buf, count, remoteIp, remoteUdpPort));
                    } catch (IOException e) {
                        break;
                    }
                }
            } else {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
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
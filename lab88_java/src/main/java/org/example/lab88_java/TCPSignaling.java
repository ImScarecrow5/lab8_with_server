package org.example.lab88_java;

import java.io.*;
import java.net.*;
import java.util.function.Consumer;

public class TCPSignaling {
    private ServerSocket serverSocket;
    private Socket activeSocket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean listening = true;
    private final Consumer<String> onMessage;
    private final int port;

    public TCPSignaling(int port, Consumer<String> onMessage) {
        this.port = port;
        this.onMessage = onMessage;
    }

    public void startServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                while (listening) {
                    Socket client = serverSocket.accept();
                    handleConnection(client);
                }
            } catch (IOException ignored) {}
        }).start();
    }
    
    public boolean connect(String ip, int port) {
        try {
            activeSocket = new Socket();
            activeSocket.connect(new InetSocketAddress(ip, port), 5000); // 5 сек таймаут
            activeSocket.setSoTimeout(10000);

            out = new PrintWriter(activeSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(activeSocket.getInputStream()));

            new Thread(this::readLoop).start();
            return true;
        } catch (IOException e) {
            return false; // Вернёт false при timeout или отказе
        }
    }

    private void handleConnection(Socket socket) {
        try {
            activeSocket = socket;
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            readLoop();
        } catch (IOException ignored) {}
    }

    private void readLoop() {
        try {
            String line;
            while (listening && (line = in.readLine()) != null) {
                onMessage.accept(line);
            }
        } catch (IOException ignored) {}
    }

    public void send(String msg) {
        if (out != null) out.println(msg);
    }

    public void stop() {
        listening = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }
}
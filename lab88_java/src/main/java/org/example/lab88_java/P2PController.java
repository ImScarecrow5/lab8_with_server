package org.example.lab88_java;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.*;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class P2PController {

    @FXML private TextField txtNick, txtTcp, txtUdp, txtStatus;
    @FXML private ComboBox<String> cmbMode;
    @FXML private TextField txtServerIp, txtServerPort, txtSearchNick;
    @FXML private ListView<String> listPeers;
    @FXML private Button btnStart, btnStop, btnCall, btnEnd, btnPushToTalk, btnSearch;
    @FXML private Label lblServerIp, lblServerPort, lblSearch;

    private final ObservableList<String> peerList = FXCollections.observableArrayList();
    private MulticastDiscovery discovery;
    private TCPSignaling signaling;
    private AudioManager audio;
    private PeerInfo selectedPeer;
    private PeerInfo foundServerPeer;
    private volatile boolean isCallActive = false;
    private ScheduledExecutorService scheduler;
    private String currentMode = "LAN";

    private Socket serverSocket;
    private PrintWriter serverOut;
    private BufferedReader serverIn;
    private volatile boolean isRegistered = false;

    @FXML
    public void initialize() {
        listPeers.setItems(peerList);
        cmbMode.getItems().addAll("LAN", "Сервер");
        cmbMode.setValue("LAN");

        cmbMode.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) return;
            currentMode = newVal;
            boolean isServer = "Сервер".equals(currentMode);
            lblServerIp.setVisible(isServer); txtServerIp.setVisible(isServer);
            lblServerPort.setVisible(isServer); txtServerPort.setVisible(isServer);
            lblSearch.setVisible(isServer); txtSearchNick.setVisible(isServer); btnSearch.setVisible(isServer);
            btnSearch.setDisable(!isServer);
            updateStatus("Режим: " + currentMode);
        });

        listPeers.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && discovery != null) {
                String nick = newVal.split(" \\(")[0];
                selectedPeer = discovery.getPeers().get(nick);
                btnCall.setDisable(selectedPeer == null);
            }
        });

        updateUIState(false);
        updateStatus("Готов к запуску");
    }

    @FXML
    private void handleStart() {
        try {
            int tcp = Integer.parseInt(txtTcp.getText().trim());
            int udp = Integer.parseInt(txtUdp.getText().trim());
            String nick = txtNick.getText().trim().isEmpty() ? "User" : txtNick.getText().trim();

            if ("Сервер".equals(currentMode)) {
                connectToServer(nick, tcp, udp);
            } else {
                startLanMode(nick, tcp, udp);
            }
            updateUIState(true);
            log("Запущен. Ник: " + nick + " (" + currentMode + ")");
        } catch (Exception e) {
            showAlert("Ошибка", "Не удалось запустить: " + e.getMessage());
        }
    }

    private void startLanMode(String nick, int tcp, int udp) throws Exception {
        discovery = new MulticastDiscovery(nick, tcp);
        discovery.start();
        signaling = new TCPSignaling(tcp, this::onSignal);
        signaling.startServer();
        audio = new AudioManager(udp);
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> Platform.runLater(this::updatePeerList), 0, 3, TimeUnit.SECONDS);
    }

    private void connectToServer(String nick, int tcp, int udp) {
        new Thread(() -> {
            try {
                // 1. Сначала создаём AudioManager (он откроет сокет на нужном UDP порту)
                audio = new AudioManager(udp);
                if (!audio.isInitialized()) {
                    Platform.runLater(() -> showAlert("Ошибка", "Не удалось открыть UDP порт " + udp));
                    handleStop();
                    return;
                }

                // 2. TCP соединение с сервером
                serverSocket = new Socket(txtServerIp.getText(), Integer.parseInt(txtServerPort.getText()));
                serverOut = new PrintWriter(serverSocket.getOutputStream(), true);
                serverIn = new BufferedReader(new InputStreamReader(serverSocket.getInputStream()));

                // 3. Регистрация на сервере
                serverOut.println("REGISTER|" + nick + "|" + tcp + "|" + udp);
                String resp = serverIn.readLine();
                if (resp == null || !resp.startsWith("OK")) {
                    String err = (resp != null) ? resp : "Нет ответа от сервера";
                    Platform.runLater(() -> showAlert("Ошибка регистрации", err));
                    handleStop();
                    return;
                }
                final String finalNick = nick;
                Platform.runLater(() -> {
                    isRegistered = true;
                    btnSearch.setDisable(false);
                    updateStatus("Подключен к серверу");
                    log("Регистрация успешна");
                });

                // 4. Настраиваем AudioManager на отправку на сервер
                InetAddress serverAddr = InetAddress.getByName(txtServerIp.getText());
                int serverPort = Integer.parseInt(txtServerPort.getText());
                audio.setServerTarget(serverAddr, serverPort);

                // 5. Отправляем дамми-пакет через тот же сокет AudioManager, чтобы сервер запомнил реальный порт
                audio.sendDummyPacket(serverAddr, serverPort);
                serverOut.println("SET_UDP_ENDPOINT|" + finalNick);
                log("UDP endpoint отправлен на сервер через AudioManager");

                // 6. Запускаем TCP сигналинг (для LAN режима, не критично)
                signaling = new TCPSignaling(tcp, this::onSignal);
                signaling.startServer();

                // 7. Цикл приёма сообщений от сервера
                String line;
                while ((line = serverIn.readLine()) != null) {
                    final String serverMsg = line;
                    Platform.runLater(() -> handleServerResponse(serverMsg));
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showAlert("Ошибка подключения к серверу", e.getMessage());
                    handleStop();
                });
            }
        }).start();
    }

    private void handleServerResponse(String msg) {
        if (msg.startsWith("FOUND|")) {
            String[] p = msg.substring(6).split("\\|");
            if (p.length >= 3) {
                foundServerPeer = new PeerInfo(txtSearchNick.getText(), p[0],
                        Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                log("Найден: " + foundServerPeer);
                btnCall.setDisable(false);
                updateStatus("Собеседник найден. Нажмите 'Позвонить'.");
            }
        } else if (msg.equals("NOT_FOUND")) {
            log("Пользователь не найден");
            updateStatus("Не найден в сети");
        } else if (msg.startsWith("CALL_DATA|")) {
            startRelayCall();
        } else if (msg.startsWith("INCOMING_CALL|")) {
            handleIncomingCall(msg);
        } else if (msg.startsWith("CALL_ACCEPTED|")) {
            startRelayCall();
        } else if (msg.equals("CALL_REJECTED")) {
            updateStatus("Вызов отклонён");
            btnCall.setDisable(false);
        }
    }

    private void handleIncomingCall(String msg) {
        String[] parts = msg.split("\\|");
        if (parts.length >= 5) {
            String callerNick = parts[1];
            String callerIp = parts[2];
            int callerTcpPort = Integer.parseInt(parts[3]);
            int callerUdpPort = Integer.parseInt(parts[4]);

            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Входящий звонок");
                alert.setHeaderText(null);
                alert.setContentText("Звонит: " + callerNick + "\nIP: " + callerIp + "\nПринять вызов?");
                alert.showAndWait().ifPresent(response -> {
                    if (response == ButtonType.OK) {
                        if (serverOut != null) {
                            serverOut.println("CALL_ACCEPT|" + txtNick.getText() + "|" + callerNick);
                            serverOut.flush();
                        }
                        startRelayCall();
                        updateStatus("Вызов принят. Разговор через сервер.");
                        log("✅ Принят звонок от " + callerNick);
                    } else {
                        if (serverOut != null) {
                            serverOut.println("CALL_REJECT|" + callerNick);
                        }
                        updateStatus("Звонок отклонён");
                    }
                });
            });
        }
    }

    private void startRelayCall() {
        if (audio == null) {
            log("AudioManager не инициализирован");
            return;
        }
        if (isCallActive) {
            log("Вызов уже активен");
            return;
        }
        try {
            audio.startRelay();
            isCallActive = true;
            Platform.runLater(() -> {
                btnCall.setDisable(true);
                btnEnd.setDisable(false);
                btnPushToTalk.setDisable(false);
                updateStatus("💬 Разговор идёт через сервер");
            });
            log("Аудиоретрансляция запущена");
        } catch (Exception e) {
            log("Ошибка запуска аудио: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @FXML
    private void handleSearch() {
        if (!isRegistered || serverOut == null) {
            updateStatus("Дождитесь регистрации на сервере");
            return;
        }
        String target = txtSearchNick.getText().trim();
        if (target.isEmpty()) return;
        serverOut.println("LOOKUP|" + target);
        log("Поиск: " + target);
    }

    @FXML
    private void handleCall() {
        PeerInfo target = "Сервер".equals(currentMode) ? foundServerPeer : selectedPeer;
        if (target == null) {
            showAlert("Ошибка", "Собеседник не выбран или не найден");
            return;
        }

        if ("Сервер".equals(currentMode)) {
            serverOut.println("CALL|" + txtNick.getText() + "|" + audio.getUdpPort() + "|" + target.nickname);
            updateStatus("Вызов инициирован через сервер...");
            log("Инициация вызова через ретранслятор");
        } else {
            new Thread(() -> {
                boolean connected = signaling.connect(target.ip, target.tcpPort);
                Platform.runLater(() -> {
                    if (connected) {
                        signaling.send("CALL_START|" + audio.getUdpPort());
                    } else {
                        updateStatus("Не удалось подключиться напрямую");
                    }
                });
            }).start();
        }
    }

    @FXML
    private void handleEnd() {
        if ("Сервер".equals(currentMode) && serverOut != null) {
            serverOut.println("CALL_END|" + txtNick.getText());
        } else if (signaling != null) {
            signaling.send("CALL_END");
        }
        stopCall();
        log("Звонок завершён");
    }

    @FXML
    private void handlePushStart() {
        if (audio != null && isCallActive) audio.setTalking(true);
    }

    @FXML
    private void handlePushEnd() {
        if (audio != null) audio.setTalking(false);
    }

    @FXML
    private void handleStop() {
        try {
            if (serverOut != null && isRegistered) {
                serverOut.println("UNREGISTER|" + txtNick.getText());
            }
            if (audio != null) audio.stopCall();
            if (signaling != null) signaling.stop();
            if (discovery != null) discovery.stop();
            if (scheduler != null) scheduler.shutdownNow();
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (Exception e) {
            log("Ошибка при остановке: " + e.getMessage());
        }
        isCallActive = false;
        isRegistered = false;
        updateUIState(false);
        peerList.clear();
        selectedPeer = null;
        foundServerPeer = null;
        updateStatus("Остановлено");
        log("Узел остановлен");
    }

    private void onSignal(String msg) {
        Platform.runLater(() -> {
            if (msg.startsWith("CALL_START|")) {
                try {
                    int remoteUdp = Integer.parseInt(msg.split("\\|")[1]);
                    if (signaling != null) signaling.send("CALL_ACCEPTED|" + audio.getUdpPort());
                    startAudioStream(selectedPeer != null ? selectedPeer.ip : "127.0.0.1", remoteUdp);
                    log("Входящий звонок (LAN)");
                } catch (Exception e) { log("Ошибка вызова"); }
            } else if (msg.startsWith("CALL_ACCEPTED|")) {
                try {
                    int remoteUdp = Integer.parseInt(msg.split("\\|")[1]);
                    String ip = selectedPeer != null ? selectedPeer.ip : (foundServerPeer != null ? foundServerPeer.ip : "127.0.0.1");
                    startAudioStream(ip, remoteUdp);
                    log("Разговор начался (LAN)");
                } catch (Exception e) { log("Ошибка аудио"); }
            } else if (msg.equals("CALL_END")) {
                handleEnd();
            }
        });
    }

    private void startAudioStream(String ip, int udp) {
        if (audio == null) return;
        try {
            audio.startCall(InetAddress.getByName(ip), udp);
            isCallActive = true;
            btnCall.setDisable(true);
            btnEnd.setDisable(false);
            btnPushToTalk.setDisable(false);
            updateStatus("Разговор идёт (P2P)");
        } catch (Exception e) { updateStatus("Ошибка аудио"); }
    }

    private void stopCall() {
        if (audio != null) audio.stopCall();
        isCallActive = false;
        btnCall.setDisable(selectedPeer == null && foundServerPeer == null);
        btnEnd.setDisable(true);
        btnPushToTalk.setDisable(true);
        updateStatus("Готов");
    }

    private void updatePeerList() {
        peerList.clear();
        if (discovery != null) {
            for (Map.Entry<String, PeerInfo> e : discovery.getPeers().entrySet()) {
                peerList.add(e.getKey() + " (" + e.getValue().ip + ")");
            }
        }
    }

    private void updateUIState(boolean running) {
        btnStart.setDisable(running);
        btnStop.setDisable(!running);
        cmbMode.setDisable(running);
        txtSearchNick.setDisable(!running || !"Сервер".equals(currentMode));
    }

    private void updateStatus(String s) { Platform.runLater(() -> txtStatus.setText(s)); }
    private void log(String m) { Platform.runLater(() -> System.out.println("[LOG] " + m)); }
    private void showAlert(String t, String m) {
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.INFORMATION);
            a.setTitle(t); a.setContentText(m); a.showAndWait();
        });
    }

    public void cleanup() { handleStop(); }
}
package org.example.lab88_java;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
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
                final String nick = newVal.split(" \\(")[0];
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
            final int tcp = Integer.parseInt(txtTcp.getText().trim());
            final int udp = Integer.parseInt(txtUdp.getText().trim());
            final String nick = txtNick.getText().trim().isEmpty() ? "User" : txtNick.getText().trim();

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
                serverSocket = new Socket(txtServerIp.getText(), Integer.parseInt(txtServerPort.getText()));
                serverOut = new PrintWriter(serverSocket.getOutputStream(), true);
                serverIn = new BufferedReader(new InputStreamReader(serverSocket.getInputStream()));

                serverOut.println("REGISTER|" + nick + "|" + tcp + "|" + udp);
                final String resp = serverIn.readLine();

                Platform.runLater(() -> {
                    if (resp != null && resp.startsWith("OK")) {
                        isRegistered = true;
                        btnSearch.setDisable(false);
                        updateStatus("Подключен к серверу");
                        log("Регистрация успешна");
                    } else {
                        showAlert("Ошибка сервера", resp != null ? resp : "Нет ответа");
                        handleStop();
                    }
                });

                String line;
                while ((line = serverIn.readLine()) != null) {
                    final String serverMsg = line;
                    Platform.runLater(() -> handleServerResponse(serverMsg));
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showAlert("Ошибка подключения", e.getMessage());
                    handleStop();
                });
            }
        }).start();

        signaling = new TCPSignaling(tcp, this::onSignal);
        signaling.startServer();
        audio = new AudioManager(udp);
    }

    @FXML
    private void handleSearch() {
        if (!isRegistered || serverOut == null) {
            updateStatus("Дождитесь регистрации на сервере");
            return;
        }
        final String target = txtSearchNick.getText().trim();
        if (target.isEmpty()) return;
        serverOut.println("LOOKUP|" + target);
        log("Поиск: " + target);
    }

    private void handleServerResponse(String msg) {
        if (msg.startsWith("FOUND|")) {
            final String[] p = msg.substring(6).split("\\|");
            if (p.length >= 3) {
                foundServerPeer = new PeerInfo(txtSearchNick.getText(), p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                log("Найден: " + foundServerPeer);
                btnCall.setDisable(false);
                updateStatus("Собеседник найден. Нажмите 'Позвонить'.");
            }
        } else if (msg.equals("NOT_FOUND")) {
            log("Пользователь не найден");
            updateStatus("Не найден в сети");
        } else if (msg.startsWith("CALL_DATA|")) {
            handleCallData(msg);
        } else if (msg.startsWith("INCOMING_CALL|")) {
            handleIncomingCall(msg);
        }
    }

    private void handleIncomingCall(String msg) {
        final String[] p = msg.split("\\|");
        if (p.length >= 3) {
            final String callerNick = p[1];
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Входящий звонок");
                alert.setHeaderText(null);
                alert.setContentText("Звонит: " + callerNick + "\nПринять вызов?");
                alert.showAndWait().ifPresent(response -> {
                    if (response == ButtonType.OK) {
                        serverOut.println("CALL_ACCEPT|" + callerNick + "|" + audio.getUdpPort());
                        updateStatus("Принят звонок от " + callerNick);
                        log("Входящий звонок принят");
                    } else {
                        serverOut.println("CALL_REJECT|" + callerNick);
                        updateStatus("Отклонён звонок");
                    }
                });
            });
        }
    }

    private void handleCallData(String msg) {
        final String[] p = msg.substring(10).split("\\|");
        if (p.length >= 4) {
            final String ip = p[0];
            final int tcp = Integer.parseInt(p[1]);
            final int udp = Integer.parseInt(p[3]);

            // Запускаем подключение в фоне, чтобы не висло приложение
            new Thread(() -> {
                boolean connected = signaling.connect(ip, tcp);
                Platform.runLater(() -> {
                    if (connected) {
                        signaling.send("CALL_ACCEPTED|" + (audio != null ? audio.getUdpPort() : 0));
                        startAudioStream(ip, udp);
                    } else {
                        updateStatus("Не удалось подключиться к собеседнику");
                        btnCall.setDisable(false);
                    }
                });
            }).start();
        }
    }

    @FXML
    private void handleCall() {
        final PeerInfo target = "Сервер".equals(currentMode) ? foundServerPeer : selectedPeer;
        if (target == null) {
            showAlert("Ошибка", "Собеседник не выбран или не найден");
            return;
        }

        if ("Сервер".equals(currentMode)) {
            serverOut.println("CALL|" + target.nickname + "|" + (audio != null ? audio.getUdpPort() : 0));
            log("Инициация вызова через сервер...");
            updateStatus("Ожидание ответа собеседника...");
            return;
        }

        // LAN режим
        new Thread(() -> {
            boolean connected = signaling.connect(target.ip, target.tcpPort);
            Platform.runLater(() -> {
                if (connected) {
                    signaling.send("CALL_START|" + (audio != null ? audio.getUdpPort() : 0));
                    log("Исходящий вызов -> " + target.ip);
                } else {
                    updateStatus("Не удалось подключиться");
                    btnCall.setDisable(false);
                }
            });
        }).start();
    }

    @FXML
    private void handleEnd() {
        if (signaling != null) signaling.send("CALL_END");
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
            if (serverOut != null && isRegistered) serverOut.println("UNREGISTER|" + txtNick.getText());
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
                    final int remoteUdp = Integer.parseInt(msg.split("\\|")[1]);
                    if (signaling != null) signaling.send("CALL_ACCEPTED|" + (audio != null ? audio.getUdpPort() : 0));
                    startAudioStream(selectedPeer != null ? selectedPeer.ip : "127.0.0.1", remoteUdp);
                    log("Входящий звонок (LAN)");
                } catch (Exception e) { log("Ошибка вызова"); }
            } else if (msg.startsWith("CALL_ACCEPTED|")) {
                try {
                    final int remoteUdp = Integer.parseInt(msg.split("\\|")[1]);
                    final String ip = selectedPeer != null ? selectedPeer.ip : (foundServerPeer != null ? foundServerPeer.ip : "127.0.0.1");
                    startAudioStream(ip, remoteUdp);
                    log("Разговор начался");
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
            updateStatus("Разговор идёт");
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

    public void cleanup() {
        handleStop();
    }
}
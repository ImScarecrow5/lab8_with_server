package org.example.lab88_java;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class P2PController {

    // FXML привязки
    @FXML private TextField txtNick, txtTcp, txtUdp, txtStatus;
    @FXML private TextField txtTargetIp, txtTargetPort;
    @FXML private ComboBox<String> cmbMode;
    @FXML private ListView<String> listPeers;
    @FXML private Button btnStart, btnStop, btnCall, btnEnd, btnPushToTalk;

    // Логика
    private final ObservableList<String> peerList = FXCollections.observableArrayList();
    private MulticastDiscovery discovery;
    private TCPSignaling signaling;
    private AudioManager audio;
    private PeerInfo selectedPeer;
    private volatile boolean isCallActive = false;
    private ScheduledExecutorService scheduler;
    private String currentMode = "LAN (Multicast)";

    @FXML
    public void initialize() {
        listPeers.setItems(peerList);

        // Инициализация ComboBox режимов
        cmbMode.getItems().addAll("LAN (Multicast)", "Прямое IP");
        cmbMode.setValue("LAN (Multicast)");

        // Обработка смены режима
        cmbMode.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            currentMode = newVal;
            boolean isLan = "LAN (Multicast)".equals(currentMode);

            txtTargetIp.setDisable(isLan);
            txtTargetPort.setDisable(isLan);
            btnCall.setDisable(isLan); // В LAN режиме кнопка активна только при выборе пира
            updateStatus("Режим: " + currentMode);
        });

        // Выбор пира из списка (для LAN)
        listPeers.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && discovery != null) {
                String nickname = newVal.split(" \\(")[0];
                selectedPeer = discovery.getPeers().get(nickname);
                btnCall.setDisable(selectedPeer == null);
            }
        });

        // Начальное состояние UI
        btnStop.setDisable(true);
        btnCall.setDisable(true);
        btnEnd.setDisable(true);
        btnPushToTalk.setDisable(true);
        txtTargetIp.setDisable(true);
        txtTargetPort.setDisable(true);
        updateStatus("Готов к запуску");
    }

    @FXML
    private void handleStart() {
        try {
            int tcp = Integer.parseInt(txtTcp.getText().trim());
            int udp = Integer.parseInt(txtUdp.getText().trim());
            String nick = txtNick.getText().trim();
            if (nick.isEmpty()) nick = "User";

            // Запуск локального сервера сигнализации и аудио
            signaling = new TCPSignaling(tcp, this::onSignal);
            signaling.startServer();
            audio = new AudioManager(udp);

            if ("LAN (Multicast)".equals(currentMode)) {
                discovery = new MulticastDiscovery(nick, tcp);
                discovery.start();
                startPeerListUpdater();
            } else {
                updateStatus("Прямое подключение. Введите IP и порт цели.");
                btnCall.setDisable(false);
            }

            // Обновление UI
            btnStart.setDisable(true);
            btnStop.setDisable(false);
            cmbMode.setDisable(true);
            log("Запущен. Ник: " + nick + " | Режим: " + currentMode);

        } catch (Exception e) {
            showAlert("Ошибка", "Не удалось запустить: " + e.getMessage());
        }
    }

    @FXML
    private void handleStop() {
        if (signaling != null) signaling.send("CALL_END");
        if (audio != null) audio.stopCall();
        if (discovery != null) discovery.stop();
        if (signaling != null) signaling.stop();
        if (scheduler != null) scheduler.shutdownNow();

        isCallActive = false;
        btnStart.setDisable(false);
        btnStop.setDisable(true);
        btnCall.setDisable(true);
        btnEnd.setDisable(true);
        btnPushToTalk.setDisable(true);
        cmbMode.setDisable(false);
        peerList.clear();
        selectedPeer = null;
        updateStatus("Остановлено");
        log("Узел остановлен");
    }

    @FXML
    private void handleCall() {
        String targetIp;
        int targetTcp;

        if ("LAN (Multicast)".equals(currentMode)) {
            if (selectedPeer == null) {
                showAlert("Ошибка", "Выберите собеседника из списка");
                return;
            }
            targetIp = selectedPeer.ip;
            targetTcp = selectedPeer.tcpPort;
        } else {
            // Режим прямого подключения
            targetIp = txtTargetIp.getText().trim();
            String portStr = txtTargetPort.getText().trim();
            if (targetIp.isEmpty() || portStr.isEmpty()) {
                showAlert("Ошибка", "Введите IP и порт собеседника");
                return;
            }
            try {
                targetTcp = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                showAlert("Ошибка", "Неверный формат порта");
                return;
            }
        }

        boolean connected = signaling.connect(targetIp, targetTcp);
        if (connected) {
            signaling.send("CALL_START|" + (audio != null ? audio.getUdpPort() : 0));
            updateStatus("Вызов инициирован...");
            log("Исходящий вызов -> " + targetIp + ":" + targetTcp);

            new Thread(() -> {
                try {
                    Thread.sleep(5000);
                    if (!isCallActive) {
                        Platform.runLater(() -> {
                            updateStatus("Таймаут! Собеседник не ответил");
                            btnCall.setDisable(false);
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        } else {
            updateStatus("Не удалось подключиться");
            btnCall.setDisable(false);
        }
    }

    @FXML
    private void handleEnd() {
        if (signaling != null) signaling.send("CALL_END");
        if (audio != null) audio.stopCall();
        isCallActive = false;
        btnEnd.setDisable(true);
        btnPushToTalk.setDisable(true);
        btnCall.setDisable(false);
        updateStatus("Звонок завершён");
        log("Звонок завершён");
    }

    @FXML
    private void handlePushStart() {
        if (audio != null && isCallActive) {
            audio.setTalking(true);
        }
    }

    @FXML
    private void handlePushEnd() {
        if (audio != null) {
            audio.setTalking(false);
        }
    }

    private void startPeerListUpdater() {
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            if (discovery != null) {
                Platform.runLater(this::updatePeerList);
            }
        }, 0, 7, TimeUnit.SECONDS);
    }

    private void updatePeerList() {
        peerList.clear();
        if (discovery != null) {
            for (Map.Entry<String, PeerInfo> entry : discovery.getPeers().entrySet()) {
                peerList.add(entry.getKey() + " (" + entry.getValue().ip + ")");
            }
        }
    }

    private void onSignal(String msg) {
        Platform.runLater(() -> {
            if (msg.startsWith("CALL_START|")) {
                try {
                    int remoteUdp = Integer.parseInt(msg.split("\\|")[1]);
                    signaling.send("CALL_ACCEPTED|" + (audio != null ? audio.getUdpPort() : 0));
                    updateStatus("Входящий звонок");
                    log("Входящий вызов");
                    startAudioStream("127.0.0.1", remoteUdp); // IP уточняется в реальном P2P, здесь fallback
                } catch (Exception e) {
                    updateStatus("Ошибка обработки вызова");
                }
            } else if (msg.startsWith("CALL_ACCEPTED|")) {
                try {
                    int remoteUdp = Integer.parseInt(msg.split("\\|")[1]);
                    updateStatus("Разговор идёт");
                    startAudioStream(selectedPeer != null ? selectedPeer.ip : txtTargetIp.getText(), remoteUdp);
                    log("Собеседник принял вызов");
                } catch (Exception e) {
                    updateStatus("Ошибка аудио");
                }
            } else if (msg.equals("CALL_END")) {
                handleEnd();
            }
        });
    }

    private void startAudioStream(String ip, int udp) {
        if (audio == null) {
            updateStatus("Аудио не доступно");
            return;
        }
        try {
            audio.startCall(InetAddress.getByName(ip), udp);
            isCallActive = true;
            btnCall.setDisable(true);
            btnEnd.setDisable(false);
            btnPushToTalk.setDisable(false);
            updateStatus("Разговор идёт");
            log("Аудио запущено: " + ip + ":" + udp);
        } catch (Exception e) {
            updateStatus("Ошибка аудио");
        }
    }

    private void updateStatus(String status) {
        Platform.runLater(() -> txtStatus.setText(status));
    }

    private void log(String message) {
        System.out.println("[LOG] " + message);
    }

    private void showAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    public void cleanup() {
        handleStop();
    }
}
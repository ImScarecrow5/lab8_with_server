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
    @FXML private TextField txtNick;
    @FXML private TextField txtTcp;
    @FXML private TextField txtUdp;
    @FXML private TextField txtStatus;
    @FXML private ListView<String> listPeers;
    @FXML private Button btnStart;
    @FXML private Button btnCall;
    @FXML private Button btnEnd;
    @FXML private Button btnPushToTalk;
    @FXML private ComboBox<String> connectionMode;
    @FXML private TextField serverIpField;
    @FXML private TextField serverPortField;

    private ObservableList<String> peerList = FXCollections.observableArrayList();
    private MulticastDiscovery discovery;
    private TCPSignaling signaling;
    private AudioManager audio;
    private PeerInfo selectedPeer;
    private volatile boolean isCallActive = false;
    private ScheduledExecutorService scheduler;

    // Режим подключения
    private enum Mode { LAN, SERVER }
    private Mode currentMode = Mode.LAN;

    @FXML
    public void initialize() {
        listPeers.setItems(peerList);

        // Настройка ComboBox режимов
        connectionMode.getItems().addAll("LAN (одна сеть)", "Сервер (интернет)");
        connectionMode.setValue("LAN (одна сеть)");
        connectionMode.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldVal, newVal) -> {
                    currentMode = "LAN (одна сеть)".equals(newVal) ? Mode.LAN : Mode.SERVER;
                    updateStatus("Режим: " + newVal);
                });

        // Настройка полей сервера
        serverIpField.setText("localhost");
        serverPortField.setText("7777");

        listPeers.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && discovery != null) {
                String nickname = newVal.split(" \\(")[0];
                selectedPeer = discovery.getPeers().get(nickname);
                btnCall.setDisable(selectedPeer == null);
            }
        });
    }

    @FXML
    private void handleStart() {
        try {
            int tcp = Integer.parseInt(txtTcp.getText());
            int udp = Integer.parseInt(txtUdp.getText());
            String nick = txtNick.getText();

            // Запускаем discovery (LAN)
            discovery = new MulticastDiscovery(nick, tcp);
            discovery.setOnPeerUpdate(peer -> {
                Platform.runLater(() -> {
                    if (!peerList.contains(peer.toString())) {
                        peerList.add(peer.toString());
                    }
                });
            });
            discovery.start();

            // Запускаем TCP signaling
            signaling = new TCPSignaling(tcp, this::onSignal);
            signaling.startServer();

            // Инициализируем аудио
            audio = new AudioManager(udp);

            // Если режим сервера - регистрируемся
            if (currentMode == Mode.SERVER) {
                registerWithServer(nick, tcp, udp);
            }

            btnStart.setDisable(true);
            updateStatus("Узел запущен. Режим: " +
                    (currentMode == Mode.LAN ? "LAN" : "Сервер"));
            startPeerListUpdater();

        } catch (Exception e) {
            showAlert("Ошибка", "Не удалось запустить узел: " + e.getMessage());
        }
    }

    private void registerWithServer(String nick, int tcp, int udp) {
        new Thread(() -> {
            try {
                String serverIp = serverIpField.getText();
                int serverPort = Integer.parseInt(serverPortField.getText());

                Socket sock = new Socket(serverIp, serverPort);
                PrintWriter out = new PrintWriter(sock.getOutputStream(), true);

                // REGISTER|Nick|TcpPort|UdpPort
                out.println("REGISTER|" + nick + "|" + tcp + "|" + udp);

                // Читаем ответ
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(sock.getInputStream()));
                String response = in.readLine();

                Platform.runLater(() -> {
                    if (response != null && response.startsWith("OK")) {
                        updateStatus("Зарегистрирован на сервере");
                    }
                });

                // Держим соединение для PING
                while (!Thread.currentThread().isInterrupted()) {
                    out.println("PING|" + nick);
                    in.readLine(); // PONG
                    Thread.sleep(30000);
                }

                sock.close();
            } catch (Exception e) {
                Platform.runLater(() -> updateStatus("Ошибка сервера: " + e.getMessage()));
            }
        }).start();
    }

    @FXML
    private void handleCall() {
        if (selectedPeer == null) return;

        boolean connected = signaling.connect(selectedPeer.ip, selectedPeer.tcpPort);
        if (connected) {
            signaling.send("CALL_START|" + audio.getUdpPort());
            updateStatus("Вызов инициирован");

            // Таймаут ожидания ответа
            new Thread(() -> {
                try {
                    Thread.sleep(5000);
                    if (!isCallActive) {
                        Platform.runLater(() -> {
                            updateStatus("Таймаут! Собеседник не ответил");
                            btnCall.setDisable(false);
                        });
                    }
                } catch (InterruptedException e) {}
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
        btnCall.setDisable(selectedPeer == null);
        updateStatus("Звонок завершён");
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
        for (Map.Entry<String, PeerInfo> entry : discovery.getPeers().entrySet()) {
            peerList.add(entry.getKey() + " (" + entry.getValue().ip + ")");
        }
    }

    private void onSignal(String msg) {
        Platform.runLater(() -> {
            if (msg.startsWith("CALL_START|")) {
                try {
                    int remoteUdp = Integer.parseInt(msg.split("\\|")[1]);
                    signaling.send("CALL_ACCEPTED|" + audio.getUdpPort());
                    updateStatus("Входящий звонок");
                    startAudioStream(selectedPeer != null ? selectedPeer.ip : "unknown", remoteUdp);
                } catch (Exception e) {
                    updateStatus("Ошибка обработки вызова");
                }
            } else if (msg.startsWith("CALL_ACCEPTED|")) {
                try {
                    int remoteUdp = Integer.parseInt(msg.split("\\|")[1]);
                    updateStatus("Разговор идёт");
                    startAudioStream(selectedPeer.ip, remoteUdp);
                } catch (Exception e) {
                    updateStatus("Ошибка");
                }
            } else if (msg.equals("CALL_END")) {
                handleEnd();
            }
        });
    }

    private void startAudioStream(String ip, int udp) {
        try {
            audio.startCall(InetAddress.getByName(ip), udp);
            isCallActive = true;
            btnCall.setDisable(true);
            btnEnd.setDisable(false);
            btnPushToTalk.setDisable(false);
            updateStatus("Разговор идёт");
        } catch (Exception e) {
            updateStatus("Ошибка аудио");
        }
    }

    private void updateStatus(String status) {
        Platform.runLater(() -> txtStatus.setText(status));
    }

    private void showAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    public void cleanup() {
        if (scheduler != null) scheduler.shutdown();
        if (discovery != null) discovery.stop();
        if (signaling != null) signaling.stop();
        if (audio != null) audio.stopCall();
    }
}
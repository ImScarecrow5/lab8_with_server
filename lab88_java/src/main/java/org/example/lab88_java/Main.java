package org.example.lab88_java;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class Main extends Application {
    private P2PController controller;

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("hello-view.fxml"));
        Parent root = loader.load();

        controller = loader.getController();

        Scene scene = new Scene(root, 600, 450);
        primaryStage.setTitle("P2P");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.cleanup();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
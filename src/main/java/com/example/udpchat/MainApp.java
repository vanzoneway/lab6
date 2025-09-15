package com.example.udpchat;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/example/udpchat/main-view.fxml"));
        Parent root = loader.load();
        Scene scene = new Scene(root, 980, 640);
        scene.getStylesheets().add(getClass().getResource("/com/example/udpchat/application.css").toExternalForm());
        stage.setTitle("UDP P2P Chat (Broadcast & Multicast) — JavaFX");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

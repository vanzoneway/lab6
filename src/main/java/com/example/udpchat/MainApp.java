// MainApp.java
package com.example.udpchat;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;

public class MainApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        // Load custom font
        Font.loadFont(getClass().getResourceAsStream("/fonts/Inter-Regular.ttf"), 14);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/example/udpchat/main-view.fxml"));
        Parent root = loader.load();
        Scene scene = new Scene(root, 1024, 768);

        // Link stylesheet
        scene.getStylesheets().add(getClass().getResource("/com/example/udpchat/application.css").toExternalForm());

        stage.setTitle("UDP P2P Chat (Broadcast & Multicast)");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
// ChatCell.java
package com.example.udpchat;

import javafx.animation.FadeTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.util.Duration;

public class ChatCell extends ListCell<ChatMessage> {
    private final VBox container = new VBox();
    private final Label authorLabel = new Label();
    private final Text messageText = new Text();
    private final Label timeLabel = new Label();

    public ChatCell() {
        container.setSpacing(4);
        messageText.setWrappingWidth(350); // Limit the wrapping width of the text
        container.getChildren().addAll(authorLabel, messageText, timeLabel);
    }

    @Override
    protected void updateItem(ChatMessage item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setGraphic(null);
        } else {
            authorLabel.setText(item.author() + " @" + item.ip());
            messageText.setText(item.text());
            timeLabel.setText(item.timestamp());

            if (item.isSelf()) {
                container.setAlignment(Pos.CENTER_RIGHT);
                authorLabel.setAlignment(Pos.CENTER_RIGHT);
                timeLabel.setAlignment(Pos.CENTER_RIGHT);
                // A style could be added here, but we'll do it via CSS in the ListView
            } else {
                container.setAlignment(Pos.CENTER_LEFT);
                authorLabel.setAlignment(Pos.CENTER_LEFT);
                timeLabel.setAlignment(Pos.CENTER_LEFT);
            }
            setGraphic(container);

            // Animate the appearance of a new message
            FadeTransition ft = new FadeTransition(Duration.millis(500), container);
            ft.setFromValue(0.0);
            ft.setToValue(1.0);
            ft.play();
        }
    }
}
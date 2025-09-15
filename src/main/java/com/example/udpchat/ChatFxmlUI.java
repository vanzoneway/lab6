// ChatController.java
package com.example.udpchat;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.util.Duration;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main controller for the UDP Chat application UI.
 * This class handles user interactions, manages network services, and updates the UI accordingly.
 */
public class ChatFxmlUI implements UdpMessageListener {

    // ... (все поля остаются без изменений) ...
    // --- FXML UI Fields ---
    @FXML private ListView<ChatMessage> broadcastChatListView;
    @FXML private ListView<ChatMessage> multicastChatListView;
    @FXML private TextField broadcastInputField;
    @FXML private Button sendBroadcastButton;
    @FXML private TextField multicastInputField;
    @FXML private Button sendMulticastButton;
    @FXML private Label mutedStatusLabel;
    @FXML private TabPane modeTabPane;
    @FXML private Tab broadcastTab;
    @FXML private Tab multicastTab;
    @FXML private ChoiceBox<String> modeSelectionBox;
    @FXML private ComboBox<NetworkUtils.InterfaceInfo> networkInterfaceComboBox;
    @FXML private TextField multicastGroupField;
    @FXML private TextField portField;
    @FXML private Button joinGroupButton;
    @FXML private Button leaveGroupButton;
    @FXML private CheckBox isHostCheckBox;
    @FXML private Label localIpLabel;
    @FXML private Label broadcastAddressLabel;
    @FXML private ListView<String> participantsListView;
    @FXML private Button refreshParticipantsButton;
    @FXML private ListView<String> bannedUsersListView;
    @FXML private TextField nicknameField;

    // --- Data Lists for UI ---
    private final ObservableList<ChatMessage> broadcastMessages = FXCollections.observableArrayList();
    private final ObservableList<ChatMessage> multicastMessages = FXCollections.observableArrayList();
    private final ObservableList<String> participants = FXCollections.observableArrayList();
    private final ObservableList<String> bannedIpList = FXCollections.observableArrayList();

    // --- Network & Service Components ---
    private final Map<String, String> nicknameByIpMap = new ConcurrentHashMap<>();
    private final BlocklistManager localBlocklist = new BlocklistManager();
    private final RecentMessageCache duplicateFilter = new RecentMessageCache(4096, 30_000);
    private NetworkUtils.InterfaceInfo currentNetworkInterface;
    private UdpBroadcastService broadcastService;
    private UdpMulticastService multicastService;
    private PeerDiscoveryService peerDiscoveryService;

    // --- Chat State ---
    private final AtomicBoolean isJoinedToGroup = new AtomicBoolean(false);
    private String currentMulticastGroupAddress = null;
    private final Set<String> groupBannedIpSet = ConcurrentHashMap.newKeySet();
    private volatile boolean isMutedByHost = false;
    private volatile String currentGroupHostIp = null;

    // --- Constants ---
    private static final int DEFAULT_LISTENING_PORT = 50000;
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public void onMessageReceived(final UdpTransport transport, final InetAddress source, final MessageProtocol.DecodedMessage message, final InetAddress group) {
        final String sourceIp = source.getHostAddress();
        if (currentNetworkInterface != null && sourceIp.equals(currentNetworkInterface.address().getHostAddress())) {
            return; // Ignore messages from self
        }
        if (duplicateFilter.isDuplicateAndRecord(message.headers.get("id"))) {
            return; // Ignore duplicate messages
        }

        peerDiscoveryService.recordPeerActivity(transport, source);
        final String nicknameHeader = message.headers.get("nick");
        if (nicknameHeader != null && !nicknameHeader.isBlank()) {
            nicknameByIpMap.put(sourceIp, nicknameHeader);
        }

        // Filter messages based on current mode
        if (isCurrentModeMulticast() ? transport == UdpTransport.BROADCAST : transport == UdpTransport.MULTICAST) return;
        final String groupHeader = message.headers.get("grp");
        if (transport == UdpTransport.MULTICAST && currentMulticastGroupAddress != null && !currentMulticastGroupAddress.equals(groupHeader)) return;

        // Host discovery
        if ("1".equals(message.headers.get("host")) && currentGroupHostIp == null) {
            currentGroupHostIp = sourceIp;
            appendSystemMessage("Group host identified: " + formatPeerForDisplay(currentGroupHostIp));
        }

        // --- ИЗМЕНЕНИЕ 1 ---
        // Local blocklist check
        if (localBlocklist.isIpBlocked(source.getHostAddress()) && MessageProtocol.CMD_POST_USER_MESSAGE.equals(message.type)) return;

        // Process message based on type
        switch (message.type) {
            // --- ИЗМЕНЕНИЕ 2 ---
            case MessageProtocol.CMD_POST_USER_MESSAGE -> processChatMessage(message, sourceIp, transport);
            // --- ИЗМЕНЕНИЕ 3 ---
            case MessageProtocol.CMD_GROUP_HOST_ADD_BAN, MessageProtocol.CMD_GROUP_HOST_REMOVE_BAN -> processBanCommand(message, sourceIp);
            default -> {} // HELLO is handled by discovery, other types are ignored
        }
    }

    private void processBanCommand(final MessageProtocol.DecodedMessage message, final String sourceIp) {
        if (currentGroupHostIp == null || !currentGroupHostIp.equals(sourceIp)) return; // Only host can ban

        final String targetIp = message.headers.get("target");
        if (targetIp == null || targetIp.isBlank()) return;

        // --- ИЗМЕНЕНИЕ 4 ---
        final boolean isBanAction = message.type.equals(MessageProtocol.CMD_GROUP_HOST_ADD_BAN);

        if (isBanAction) {
            groupBannedIpSet.add(targetIp);
        } else {
            groupBannedIpSet.remove(targetIp);
        }
        Platform.runLater(this::synchronizeBannedListView);

        final String actionText = isBanAction ? "banned" : "unbanned";
        if (currentNetworkInterface != null && targetIp.equals(currentNetworkInterface.address().getHostAddress())) {
            isMutedByHost = isBanAction;
            final String selfStatusMessage = isBanAction ? "You have been banned by the host" : "The host has unbanned you";
            appendSystemMessage(selfStatusMessage);
            updateUIForCurrentMode();
        } else {
            appendSystemMessage("Host " + actionText + " " + formatPeerForDisplay(targetIp));
        }
    }

    private void sendMessage(final UdpTransport transport) {
        final TextField inputField = (transport == UdpTransport.MULTICAST) ? multicastInputField : broadcastInputField;
        final String text = inputField.getText();
        if (text == null || text.isBlank()) return;

        final Map<String, String> headers = new HashMap<>();
        headers.put("id", MessageIds.next());
        final long timestamp = System.currentTimeMillis();
        headers.put("ts", Long.toString(timestamp));
        final String nickname = nicknameField.getText();
        if (nickname != null && !nickname.isBlank()) headers.put("nick", nickname);

        try {
            if (transport == UdpTransport.BROADCAST) {
                // --- ИЗМЕНЕНИЕ 5 ---
                broadcastService.send(MessageProtocol.CMD_POST_USER_MESSAGE, headers, text);
            } else {
                if (!isJoinedToGroup.get()) { displayErrorAlert("Multicast: Must join a group first."); return; }
                if (isMutedByHost) { displayErrorAlert("You are banned by the host and cannot send messages."); return; }
                // --- ИЗМЕНЕНИЕ 6 ---
                multicastService.send(MessageProtocol.CMD_POST_USER_MESSAGE, headers, text);
            }

            final String selfIp = (currentNetworkInterface != null) ? currentNetworkInterface.address().getHostAddress() : "local";
            final ChatMessage selfMessage = new ChatMessage((nickname.isBlank() ? "You" : nickname), selfIp, text, formatTimestamp(Long.toString(timestamp)), true);

            addChatMessageToView(selfMessage, transport);
            inputField.clear();
        } catch (IOException e) {
            e.printStackTrace();
            displayErrorAlert("Send error: " + e.getMessage());
        }
    }

    private void executeHostBanAction(final boolean isBan, final String ip) {
        if (!isCurrentModeMulticast() || !isJoinedToGroup.get() || !isHostCheckBox.isSelected()) {
            displayErrorAlert("This function is only available to the host of an active Multicast group.");
            return;
        }
        final String selfIp = (currentNetworkInterface != null) ? currentNetworkInterface.address().getHostAddress() : null;
        if (ip.equals(selfIp)) {
            displayErrorAlert("You cannot ban yourself.");
            return;
        }
        try {
            final Map<String, String> headers = new HashMap<>();
            headers.put("id", MessageIds.next());
            headers.put("target", ip);
            // --- ИЗМЕНЕНИЕ 7 ---
            multicastService.send(isBan ? MessageProtocol.CMD_GROUP_HOST_ADD_BAN : MessageProtocol.CMD_GROUP_HOST_REMOVE_BAN, headers, "");

            final String action = isBan ? "banned" : "unbanned";
            if (isBan) groupBannedIpSet.add(ip); else groupBannedIpSet.remove(ip);
            synchronizeBannedListView();
            appendSystemMessage("Host: " + action + " " + formatPeerForDisplay(ip));
        } catch (IOException ex) {
            ex.printStackTrace();
            displayErrorAlert("Error sending ban/unban command: " + ex.getMessage());
        }
    }

    // ... все остальные методы в ChatController остаются без изменений ...
    // ... (initialize, initializeUIControls, etc.) ...

    // --- Оставшаяся часть класса ChatController ---
    @FXML
    public void initialize() {
        initializeUIControls();
        initializeNetworkInterfaces();
        bindUIActions();
        updateUIForCurrentMode();
    }

    private void initializeUIControls() {
        modeSelectionBox.getItems().addAll("Broadcast", "Multicast");
        modeSelectionBox.setValue("Broadcast");
        multicastGroupField.setText("239.255.0.1");
        portField.setText(String.valueOf(DEFAULT_LISTENING_PORT));
        nicknameField.setText("user-" + (int) (Math.random() * 1000));

        setupChatListView(broadcastChatListView, broadcastMessages);
        setupChatListView(multicastChatListView, multicastMessages);
        participantsListView.setItems(participants);
        setupParticipantsListCellFactory();
        setupParticipantsListContextMenu();
        bannedUsersListView.setItems(bannedIpList);
        setupBannedUsersListContextMenu();
    }

    private void initializeNetworkInterfaces() {
        try {
            final List<NetworkUtils.InterfaceInfo> interfaces = NetworkUtils.getActiveIPv4Interfaces();
            if (interfaces.isEmpty()) {
                displayErrorAlert("No active IPv4 network interfaces found.");
                return;
            }
            networkInterfaceComboBox.getItems().addAll(interfaces);
            networkInterfaceComboBox.getSelectionModel().select(0);
            handleInterfaceSelection();
        } catch (Exception e) {
            e.printStackTrace();
            displayErrorAlert("Error detecting network interfaces: " + e.getMessage());
        }
    }

    private void bindUIActions() {
        sendBroadcastButton.setOnAction(e -> sendMessage(UdpTransport.BROADCAST));
        broadcastInputField.setOnAction(e -> sendMessage(UdpTransport.BROADCAST));
        sendMulticastButton.setOnAction(e -> sendMessage(UdpTransport.MULTICAST));
        multicastInputField.setOnAction(e -> sendMessage(UdpTransport.MULTICAST));

        joinGroupButton.setOnAction(e -> executeJoinGroupAction());
        leaveGroupButton.setOnAction(e -> executeLeaveGroupAction());
        networkInterfaceComboBox.setOnAction(e -> handleInterfaceSelection());
        isHostCheckBox.setOnAction(e -> {
            if (multicastService != null) multicastService.setHostStatus(isHostCheckBox.isSelected());
        });
        modeSelectionBox.setOnAction(e -> handleModeChange());
        refreshParticipantsButton.setOnAction(e -> executeRefreshParticipantsAction());
    }

    private void executeJoinGroupAction() {
        try {
            final InetAddress newGroup = InetAddress.getByName(multicastGroupField.getText());
            if (multicastService == null) return;

            multicastService.joinOrSwitchGroup(newGroup);
            isJoinedToGroup.set(true);
            currentMulticastGroupAddress = newGroup.getHostAddress();

            // Reset group state
            groupBannedIpSet.clear();
            bannedIpList.clear();
            isMutedByHost = false;
            currentGroupHostIp = isHostCheckBox.isSelected() && currentNetworkInterface != null ? currentNetworkInterface.address().getHostAddress() : null;

            appendSystemMessage("Joined group " + currentMulticastGroupAddress + ":" + portField.getText()
                    + (isHostCheckBox.isSelected() ? " (as host)" : ""));

            updateUIForCurrentMode();
        } catch (Exception e) {
            e.printStackTrace();
            displayErrorAlert("Failed to join group: " + e.getMessage());
        }
    }

    private void executeLeaveGroupAction() {
        try {
            if (multicastService != null && multicastService.isJoined()) {
                multicastService.leaveGroup();
                appendSystemMessage("Left group " + currentMulticastGroupAddress);
            }
            // Reset all group-related state
            isJoinedToGroup.set(false);
            currentMulticastGroupAddress = null;
            groupBannedIpSet.clear();
            bannedIpList.clear();
            isMutedByHost = false;
            currentGroupHostIp = null;

            updateUIForCurrentMode();
        } catch (Exception e) {
            e.printStackTrace();
            displayErrorAlert("Error leaving group: " + e.getMessage());
        }
    }

    private void reinitializeNetworkServices() {
        shutdownNetworkServices();
        try {
            final int port = Integer.parseInt(portField.getText());

            broadcastService = new UdpBroadcastService(port, currentNetworkInterface, this);
            broadcastService.start();

            final InetAddress group = InetAddress.getByName(multicastGroupField.getText());
            multicastService = new UdpMulticastService(port, group, currentNetworkInterface, this);
            multicastService.setHostStatus(isHostCheckBox.isSelected());
            multicastService.setTtl(1);

            peerDiscoveryService = new PeerDiscoveryService(
                    broadcastService, multicastService, nicknameField::getText, 2000,
                    (ip, added) -> Platform.runLater(() -> {
                        if (added) {
                            if (!participants.contains(ip)) participants.add(ip);
                        } else {
                            participants.remove(ip);
                            nicknameByIpMap.remove(ip);
                        }
                    }),
                    new PeerDiscoveryService.ModeSelector() {
                        @Override public boolean useBroadcast() { return !isCurrentModeMulticast(); }
                        @Override public boolean useMulticast() { return isCurrentModeMulticast() && isJoinedToGroup.get(); }
                        @Override public InetAddress currentMulticastGroup() {
                            try {
                                return isJoinedToGroup.get() ? InetAddress.getByName(multicastGroupField.getText()) : null;
                            } catch (Exception e) {
                                e.printStackTrace();
                                return null;
                            }
                        }
                    }
            );
            peerDiscoveryService.start();
        } catch (Exception e) {
            e.printStackTrace();
            displayErrorAlert("Failed to start network services: " + e.getMessage());
        }
    }

    private void shutdownNetworkServices() {
        if (peerDiscoveryService != null) peerDiscoveryService.stop();
        if (broadcastService != null) broadcastService.stop();
        if (multicastService != null) try { multicastService.leaveGroup(); } catch (IOException e) { e.printStackTrace(); }

        isJoinedToGroup.set(false);
        currentMulticastGroupAddress = null;
        groupBannedIpSet.clear();
        bannedIpList.clear();
        isMutedByHost = false;
        currentGroupHostIp = null;

        updateUIForCurrentMode();
    }

    private void processChatMessage(final MessageProtocol.DecodedMessage message, final String sourceIp, final UdpTransport transport) {
        if (transport == UdpTransport.MULTICAST && groupBannedIpSet.contains(sourceIp)) return; // Check group ban

        final String nickname = nicknameByIpMap.getOrDefault(sourceIp, "unknown");
        final ChatMessage chatMessage = new ChatMessage(nickname, sourceIp, message.payload, formatTimestamp(message.headers.get("ts")), false);

        Platform.runLater(() -> addChatMessageToView(chatMessage, transport));
    }

    private void setupChatListView(ListView<ChatMessage> listView, ObservableList<ChatMessage> messages) {
        listView.setItems(messages);
        listView.setCellFactory(lv -> new ChatCell());
    }

    private void setupParticipantsListCellFactory() {
        participantsListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String ip, boolean empty) {
                super.updateItem(ip, empty);
                setText(empty || ip == null ? null : formatPeerForDisplay(ip));
            }
        });
    }

    private void setupParticipantsListContextMenu() {
        final ContextMenu menu = new ContextMenu();
        final MenuItem ignoreItem = new MenuItem("Ignore (Local)");
        final MenuItem unignoreItem = new MenuItem("Unignore (Local)");
        final MenuItem banItem = new MenuItem("Ban in Group (Host)");
        final MenuItem unbanItem = new MenuItem("Unban in Group (Host)");

        ignoreItem.setOnAction(e -> handleParticipantAction(this::executeLocalBlock));
        unignoreItem.setOnAction(e -> handleParticipantAction(this::executeLocalUnblock));
        banItem.setOnAction(e -> handleParticipantAction(ip -> executeHostBanAction(true, ip)));
        unbanItem.setOnAction(e -> handleParticipantAction(ip -> executeHostBanAction(false, ip)));

        menu.getItems().addAll(ignoreItem, unignoreItem, new SeparatorMenuItem(), banItem, unbanItem);
        participantsListView.setContextMenu(menu);
    }

    private void setupBannedUsersListContextMenu() {
        final ContextMenu menu = new ContextMenu();
        final MenuItem unbanItem = new MenuItem("Unban (Host)");
        unbanItem.setOnAction(e -> {
            String selectedIp = bannedUsersListView.getSelectionModel().getSelectedItem();
            if (selectedIp != null) executeHostBanAction(false, selectedIp);
        });
        menu.getItems().add(unbanItem);
        bannedUsersListView.setContextMenu(menu);
    }

    private void executeLocalBlock(final String ip) {
        final String selfIp = (currentNetworkInterface != null) ? currentNetworkInterface.address().getHostAddress() : null;
        if (ip.equals(selfIp)) {
            displayErrorAlert("You cannot ignore your own IP address.");
            return;
        }
        localBlocklist.block(ip);
        appendSystemMessage("Locally ignoring " + formatPeerForDisplay(ip));
    }

    private void executeLocalUnblock(final String ip) {
        localBlocklist.unblock(ip);
        appendSystemMessage("Stopped locally ignoring " + formatPeerForDisplay(ip));
    }

    private void handleParticipantAction(final java.util.function.Consumer<String> action) {
        final String selectedIp = participantsListView.getSelectionModel().getSelectedItem();
        if (selectedIp != null) action.accept(selectedIp);
    }

    private void synchronizeBannedListView() {
        final List<String> sortedList = new ArrayList<>(groupBannedIpSet);
        Collections.sort(sortedList);
        bannedIpList.setAll(sortedList);
    }

    private void addChatMessageToView(final ChatMessage chatMessage, final UdpTransport transport) {
        if (transport == UdpTransport.MULTICAST) {
            multicastMessages.add(chatMessage);
            multicastChatListView.scrollTo(multicastMessages.size() - 1);
        } else {
            broadcastMessages.add(chatMessage);
            broadcastChatListView.scrollTo(broadcastMessages.size() - 1);
        }
    }

    private void appendSystemMessage(final String text) {
        final ChatMessage systemMessage = new ChatMessage("system", "", text, TIMESTAMP_FORMATTER.format(LocalDateTime.now()), false);
        Platform.runLater(() -> addChatMessageToView(systemMessage, isCurrentModeMulticast() ? UdpTransport.MULTICAST : UdpTransport.BROADCAST));
    }

    private void handleInterfaceSelection() {
        currentNetworkInterface = networkInterfaceComboBox.getSelectionModel().getSelectedItem();
        if (currentNetworkInterface == null) return;
        localIpLabel.setText(currentNetworkInterface.address().getHostAddress());
        broadcastAddressLabel.setText("Broadcast: " + (currentNetworkInterface.broadcast() != null ? currentNetworkInterface.broadcast().getHostAddress() : "N/A"));
        reinitializeNetworkServices();
    }

    private void handleModeChange() {
        updateUIForCurrentMode();
        modeTabPane.getSelectionModel().select(isCurrentModeMulticast() ? multicastTab : broadcastTab);
    }

    private boolean isCurrentModeMulticast() {
        return "Multicast".equals(modeSelectionBox.getValue());
    }

    private void updateUIForCurrentMode() {
        final boolean isMc = isCurrentModeMulticast();
        joinGroupButton.setDisable(!isMc);
        leaveGroupButton.setDisable(!isMc);
        isHostCheckBox.setDisable(!isMc);

        final boolean canSendMulticast = isMc && isJoinedToGroup.get() && !isMutedByHost;
        sendMulticastButton.setDisable(!canSendMulticast);
        multicastInputField.setDisable(!canSendMulticast);

        sendBroadcastButton.setDisable(isMc);
        broadcastInputField.setDisable(isMc);

        updateMutedStatusLabel();
    }

    private void updateMutedStatusLabel() {
        final boolean shouldShow = isCurrentModeMulticast() && isMutedByHost;
        if (mutedStatusLabel.isVisible() == shouldShow) return;

        if (shouldShow) {
            mutedStatusLabel.setVisible(true);
            mutedStatusLabel.setManaged(true);
            FadeTransition ft = new FadeTransition(Duration.millis(300), mutedStatusLabel);
            ft.setFromValue(0); ft.setToValue(1); ft.play();
        } else {
            FadeTransition ft = new FadeTransition(Duration.millis(300), mutedStatusLabel);
            ft.setFromValue(1); ft.setToValue(0);
            ft.setOnFinished(e -> {
                mutedStatusLabel.setVisible(false);
                mutedStatusLabel.setManaged(false);
            });
            ft.play();
        }
    }

    private void executeRefreshParticipantsAction() {
        if (peerDiscoveryService == null) return;
        final List<String> allPeers = peerDiscoveryService.getAllPeersSnapshot();
        final String selfIp = (currentNetworkInterface != null) ? currentNetworkInterface.address().getHostAddress() : null;
        if (selfIp != null) allPeers.remove(selfIp);
        participants.setAll(allPeers);
    }

    private String formatPeerForDisplay(final String ip) {
        final String nickname = nicknameByIpMap.getOrDefault(ip, "");
        return nickname.isBlank() ? ip : (ip + " — " + nickname);
    }

    private void displayErrorAlert(final String message) {
        Platform.runLater(() -> {
            final Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
            alert.setHeaderText("Error");
            alert.showAndWait();
        });
    }

    private String formatTimestamp(final String epochMillisHeader) {
        try {
            final long millis = (epochMillisHeader == null || epochMillisHeader.isBlank()) ?
                    System.currentTimeMillis() :
                    Long.parseLong(epochMillisHeader);
            return TIMESTAMP_FORMATTER.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
        } catch (Exception e) {
            e.printStackTrace();
            return TIMESTAMP_FORMATTER.format(LocalDateTime.now());
        }
    }
}
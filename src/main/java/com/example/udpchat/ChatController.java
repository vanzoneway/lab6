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

public class ChatController implements UdpMessageListener {

    // --- FXML UI Fields ---
    @FXML private ListView<ChatMessage> chatBroadcast;
    @FXML private ListView<ChatMessage> chatMulticast;
    @FXML private TextField inputBroadcast;
    @FXML private Button sendBroadcast;
    @FXML private TextField inputMulticast;
    @FXML private Button sendMulticast;
    @FXML private Label mutedLabel;
    @FXML private TabPane tabs;
    @FXML private Tab tabBroadcast;
    @FXML private Tab tabMulticast;
    @FXML private ChoiceBox<String> modeChoice;
    @FXML private ComboBox<NetworkUtils.IfaceInfo> ifaceCombo;
    @FXML private TextField multicastGroupField;
    @FXML private TextField portField;
    @FXML private Button joinButton;
    @FXML private Button leaveButton;
    @FXML private CheckBox hostMulticast;
    @FXML private Label ipLabel;
    @FXML private Label bcastLabel;
    @FXML private ListView<String> peersList;
    @FXML private Button refreshPeersButton;
    @FXML private ListView<String> bannedList;
    @FXML private TextField nickField;

    // --- Data Lists for UI ---
    private final ObservableList<ChatMessage> broadcastMessages = FXCollections.observableArrayList();
    private final ObservableList<ChatMessage> multicastMessages = FXCollections.observableArrayList();
    private final ObservableList<String> peers = FXCollections.observableArrayList();
    private final ObservableList<String> bannedItems = FXCollections.observableArrayList();

    // --- Network & Service Components ---
    private final Map<String, String> nickByIp = new ConcurrentHashMap<>();
    private final BlocklistManager blocklist = new BlocklistManager();
    private final RecentMessageCache dedup = new RecentMessageCache(4096, 30_000);
    private NetworkUtils.IfaceInfo currentIface;
    private UdpBroadcastService bcastService;
    private UdpMulticastService mcastService;
    private PeerDiscoveryService discovery;

    // --- Chat State ---
    private final AtomicBoolean joined = new AtomicBoolean(false);
    private String currentGroupAddr = null;
    private final Map<String, Boolean> seenInMulticast = new ConcurrentHashMap<>();
    private final Set<String> mcastHostBans = ConcurrentHashMap.newKeySet();
    private volatile boolean mutedByHost = false;
    private volatile String groupHostIp = null;

    // --- Constants ---
    private static final int DEFAULT_PORT = 50000;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @FXML
    public void initialize() {
        modeChoice.getItems().addAll("Broadcast", "Multicast");
        modeChoice.setValue("Broadcast");
        multicastGroupField.setText("239.255.0.1");
        portField.setText(String.valueOf(DEFAULT_PORT));
        nickField.setText("user-" + (int) (Math.random() * 1000));

        setupChatListView(chatBroadcast, broadcastMessages);
        setupChatListView(chatMulticast, multicastMessages);
        peersList.setItems(peers);
        setupPeersListCellFactory();
        setupPeersListContextMenu();
        bannedList.setItems(bannedItems);
        setupBannedListContextMenu();

        try {
            List<NetworkUtils.IfaceInfo> ifaces = NetworkUtils.enumerateIPv4Interfaces();
            if (ifaces.isEmpty()) {
                showError("No active IPv4 network interfaces found.");
                return;
            }
            ifaceCombo.getItems().addAll(ifaces);
            ifaceCombo.getSelectionModel().select(0);
            onInterfaceSelected();
        } catch (Exception e) {
            e.printStackTrace();
            showError("Error detecting network interfaces: " + e.getMessage());
        }

        sendBroadcast.setOnAction(e -> sendMessage(UdpTransport.BROADCAST));
        inputBroadcast.setOnAction(e -> sendMessage(UdpTransport.BROADCAST));
        sendMulticast.setOnAction(e -> sendMessage(UdpTransport.MULTICAST));
        inputMulticast.setOnAction(e -> sendMessage(UdpTransport.MULTICAST));
        joinButton.setOnAction(e -> doJoin());
        leaveButton.setOnAction(e -> doLeave());
        ifaceCombo.setOnAction(e -> onInterfaceSelected());
        hostMulticast.setOnAction(e -> {
            if (mcastService != null) mcastService.configureHost(hostMulticast.isSelected());
        });
        modeChoice.setOnAction(e -> onModeChanged());
        refreshPeersButton.setOnAction(e -> refreshPeersListManual());

        refreshUiForMode();
        updateMutedUi();
    }

    private void setupChatListView(ListView<ChatMessage> listView, ObservableList<ChatMessage> messages) {
        listView.setItems(messages);
        listView.setCellFactory(lv -> new ChatCell());
    }

    private void setupPeersListCellFactory() {
        peersList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String ip, boolean empty) {
                super.updateItem(ip, empty);
                if (empty || ip == null) {
                    setText(null);
                } else {
                    String nick = nickByIp.getOrDefault(ip, "");
                    setText(nick.isBlank() ? ip : ip + " — " + nick);
                }
            }
        });
    }

    private void setupPeersListContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem ignore = new MenuItem("Ignore (Local)");
        MenuItem unignore = new MenuItem("Unignore (Local)");
        MenuItem banInGroup = new MenuItem("Ban in Group (Host)");
        MenuItem unbanInGroup = new MenuItem("Unban in Group (Host)");

        ignore.setOnAction(e -> handlePeerAction(this::doLocalBlock));
        unignore.setOnAction(e -> handlePeerAction(this::doLocalUnblock));
        banInGroup.setOnAction(e -> handlePeerAction(ip -> sendHostBan(true, ip)));
        unbanInGroup.setOnAction(e -> handlePeerAction(ip -> sendHostBan(false, ip)));

        menu.getItems().addAll(ignore, unignore, new SeparatorMenuItem(), banInGroup, unbanInGroup);
        peersList.setContextMenu(menu);
        peersList.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                menu.show(peersList, event.getScreenX(), event.getScreenY());
            }
        });
    }

    private void doLocalBlock(String ip) {
        String selfIp = (currentIface != null) ? currentIface.address.getHostAddress() : null;
        if (ip.equals(selfIp)) {
            showError("You cannot ignore your own IP address.");
            return;
        }
        blocklist.block(ip);
        appendSysMessage("Locally ignoring " + pretty(ip));
    }

    private void doLocalUnblock(String ip) {
        blocklist.unblock(ip);
        appendSysMessage("Stopped ignoring " + pretty(ip));
    }

    private void handlePeerAction(java.util.function.Consumer<String> action) {
        String ip = peersList.getSelectionModel().getSelectedItem();
        if (ip != null) action.accept(ip);
    }

    private void setupBannedListContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem unban = new MenuItem("Unban (Host)");
        unban.setOnAction(e -> {
            String ip = bannedList.getSelectionModel().getSelectedItem();
            if (ip != null) sendHostBan(false, ip);
        });
        menu.getItems().add(unban);
        bannedList.setContextMenu(menu);
        bannedList.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                menu.show(bannedList, event.getScreenX(), event.getScreenY());
            }
        });
    }

    private void onInterfaceSelected() {
        currentIface = ifaceCombo.getSelectionModel().getSelectedItem();
        if (currentIface == null) return;
        ipLabel.setText(currentIface.address.getHostAddress());
        bcastLabel.setText("Broadcast: " + (currentIface.broadcast != null ? currentIface.broadcast.getHostAddress() : "N/A"));
        restartServices();
    }

    private void onModeChanged() {
        refreshUiForMode();
        tabs.getSelectionModel().select(isMulticastMode() ? tabMulticast : tabBroadcast);
        updateMutedUi();
    }

    private boolean isMulticastMode() {
        return "Multicast".equals(modeChoice.getValue());
    }

    private void refreshUiForMode() {
        boolean isMc = isMulticastMode();
        joinButton.setDisable(!isMc);
        leaveButton.setDisable(!isMc);
        hostMulticast.setDisable(!isMc);
        boolean mcSendEnabled = isMc && joined.get() && !mutedByHost;
        sendMulticast.setDisable(!mcSendEnabled);
        inputMulticast.setDisable(!mcSendEnabled);
        sendBroadcast.setDisable(isMc);
        inputBroadcast.setDisable(isMc);
    }

    private void updateMutedUi() {
        boolean show = isMulticastMode() && mutedByHost;
        if (mutedLabel.isVisible() == show) return;
        if (show) {
            mutedLabel.setVisible(true);
            mutedLabel.setManaged(true);
            FadeTransition ft = new FadeTransition(Duration.millis(300), mutedLabel);
            ft.setFromValue(0); ft.setToValue(1); ft.play();
        } else {
            FadeTransition ft = new FadeTransition(Duration.millis(300), mutedLabel);
            ft.setFromValue(1); ft.setToValue(0);
            ft.setOnFinished(e -> {
                mutedLabel.setVisible(false);
                mutedLabel.setManaged(false);
            });
            ft.play();
        }
    }

    private void restartServices() {
        stopServices();
        try {
            int port = Integer.parseInt(portField.getText());
            bcastService = new UdpBroadcastService(port, currentIface, null, this);
            bcastService.start();

            InetAddress group = InetAddress.getByName(multicastGroupField.getText());
            mcastService = new UdpMulticastService(port, group, currentIface, null, this);
            mcastService.configureHost(hostMulticast.isSelected());
            mcastService.setTtl(1);

            discovery = new PeerDiscoveryService(
                    bcastService, mcastService, nickField::getText, 2000,
                    (ip, added, transport, grpAddr) -> Platform.runLater(() -> {
                        if (added) {
                            if (!peers.contains(ip)) peers.add(ip);
                        } else {
                            peers.remove(ip);
                            nickByIp.remove(ip);
                        }
                    }),
                    new PeerDiscoveryService.ModeSelector() {
                        @Override public boolean useBroadcast() { return !isMulticastMode(); }
                        @Override public boolean useMulticast() { return isMulticastMode() && joined.get(); }
                        @Override public InetAddress currentMulticastGroup() {
                            try {
                                return joined.get() ? InetAddress.getByName(multicastGroupField.getText()) : null;
                            } catch (Exception e) {
                                e.printStackTrace();
                                return null;
                            }
                        }
                    }
            );
            discovery.start();
        } catch (Exception e) {
            e.printStackTrace();
            showError("Failed to start network services: " + e.getMessage());
        }
    }

    private void stopServices() {
        if (discovery != null) discovery.stop();
        if (bcastService != null) bcastService.stop();
        if (mcastService != null) try { mcastService.leave(); } catch (IOException e) { e.printStackTrace(); }
        joined.set(false);
        currentGroupAddr = null;
        seenInMulticast.clear();
        mcastHostBans.clear();
        bannedItems.clear();
        mutedByHost = false;
        groupHostIp = null;
        refreshUiForMode();
        updateMutedUi();
    }

    private void doJoin() {
        try {
            InetAddress newGroup = InetAddress.getByName(multicastGroupField.getText());
            if (mcastService == null) return;
            mcastService.switchGroup(newGroup);
            joined.set(true);
            currentGroupAddr = newGroup.getHostAddress();
            seenInMulticast.clear();
            mcastHostBans.clear();
            bannedItems.clear();
            mutedByHost = false;
            groupHostIp = hostMulticast.isSelected() && currentIface != null ? currentIface.address.getHostAddress() : null;

            appendSysMessage("Joined group " + currentGroupAddr + ":" + portField.getText()
                    + (hostMulticast.isSelected() ? " (as host)" : ""));
            refreshUiForMode();
            updateMutedUi();
        } catch (Exception e) {
            e.printStackTrace();
            showError("Failed to join group: " + e.getMessage());
        }
    }

    private void doLeave() {
        try {
            if (mcastService != null && mcastService.isJoined()) {
                mcastService.leave();
                appendSysMessage("Left group " + currentGroupAddr);
            }
            joined.set(false);
            currentGroupAddr = null;
            seenInMulticast.clear();
            mcastHostBans.clear();
            bannedItems.clear();
            mutedByHost = false;
            groupHostIp = null;
            refreshUiForMode();
            updateMutedUi();
        } catch (Exception e) {
            e.printStackTrace();
            showError("Error leaving group: " + e.getMessage());
        }
    }

    private void refreshPeersListManual() {
        if (discovery == null) return;
        List<String> all = discovery.snapshotAllPeers();
        String selfIp = (currentIface != null) ? currentIface.address.getHostAddress() : null;
        if (selfIp != null) all.remove(selfIp);
        peers.setAll(all);
    }

    @Override
    public void onMessage(UdpTransport transport, InetAddress from, MessageProtocol.Parsed msg, InetAddress group) {
        String fromIp = from.getHostAddress();
        if (currentIface != null && fromIp.equals(currentIface.address.getHostAddress())) return;
        if (dedup.isDuplicateAndRecord(msg.headers.get("id"))) return;

        discovery.seen(transport, from);
        String nickHdr = msg.headers.get("nick");
        if (nickHdr != null && !nickHdr.isBlank()) nickByIp.put(fromIp, nickHdr);

        if (isMulticastMode() ? transport == UdpTransport.BROADCAST : transport == UdpTransport.MULTICAST) return;
        String grpHdr = msg.headers.get("grp");
        if (transport == UdpTransport.MULTICAST && currentGroupAddr != null && !currentGroupAddr.equals(grpHdr)) return;

        if ("1".equals(msg.headers.get("host")) && groupHostIp == null) {
            groupHostIp = fromIp;
            appendSysMessage("Group host identified: " + pretty(groupHostIp));
        }
        if (blocklist.isBlocked(from) && MessageProtocol.TYPE_CHAT.equals(msg.type)) return;

        switch (msg.type) {
            case MessageProtocol.TYPE_CHAT -> {
                if (transport == UdpTransport.MULTICAST && mcastHostBans.contains(fromIp)) return;
                String nick = nickByIp.getOrDefault(fromIp, "unknown");
                ChatMessage chatMsg = new ChatMessage(nick, fromIp, msg.payload, formatTs(msg.headers.get("ts")), false);
                Platform.runLater(() -> addChatMessage(chatMsg, transport));
            }
            case MessageProtocol.TYPE_MBLOCK, MessageProtocol.TYPE_MUNBLOCK -> {
                if (transport != UdpTransport.MULTICAST || groupHostIp == null || !groupHostIp.equals(fromIp)) return;
                String target = msg.headers.get("target");
                if (target == null || target.isBlank()) return;
                boolean isBlock = msg.type.equals(MessageProtocol.TYPE_MBLOCK);

                if (isBlock) mcastHostBans.add(target); else mcastHostBans.remove(target);
                Platform.runLater(this::syncBannedItems);

                String actionText = isBlock ? "banned" : "unbanned";
                if (currentIface != null && target.equals(currentIface.address.getHostAddress())) {
                    mutedByHost = isBlock;
                    String selfStatus = isBlock ? "You have been banned by the host" : "The host has unbanned you";
                    appendSysMessage(selfStatus);
                    refreshUiForMode();
                    updateMutedUi();
                } else {
                    appendSysMessage("Host " + actionText + " " + pretty(target));
                }
            }
            default -> {} // HELLO is handled by discovery, other types are ignored
        }
    }

    private void addChatMessage(ChatMessage chatMsg, UdpTransport transport) {
        if (transport == UdpTransport.MULTICAST) {
            multicastMessages.add(chatMsg);
            chatMulticast.scrollTo(multicastMessages.size() - 1);
        } else {
            broadcastMessages.add(chatMsg);
            chatBroadcast.scrollTo(broadcastMessages.size() - 1);
        }
    }

    private void sendHostBan(boolean ban, String ip) {
        if (!isMulticastMode() || !joined.get() || !hostMulticast.isSelected()) {
            showError("This function is only available to the host of an active Multicast group.");
            return;
        }
        String selfIp = (currentIface != null) ? currentIface.address.getHostAddress() : null;
        if (ip.equals(selfIp)) {
            showError("You cannot ban yourself.");
            return;
        }
        try {
            Map<String, String> h = new HashMap<>();
            h.put("id", MessageIds.next());
            h.put("target", ip);
            mcastService.send(ban ? MessageProtocol.TYPE_MBLOCK : MessageProtocol.TYPE_MUNBLOCK, h, "");
            String action = ban ? "banned" : "unbanned";
            if (ban) mcastHostBans.add(ip); else mcastHostBans.remove(ip);
            syncBannedItems();
            appendSysMessage("Host: " + action + " " + pretty(ip));
        } catch (IOException ex) {
            ex.printStackTrace();
            showError("Error sending command: " + ex.getMessage());
        }
    }

    private void syncBannedItems() {
        List<String> sorted = new ArrayList<>(mcastHostBans);
        Collections.sort(sorted);
        bannedItems.setAll(sorted);
    }

    private void sendMessage(UdpTransport transport) {
        TextField input = (transport == UdpTransport.MULTICAST) ? inputMulticast : inputBroadcast;
        String text = input.getText();
        if (text == null || text.isBlank()) return;

        Map<String, String> h = new HashMap<>();
        h.put("id", MessageIds.next());
        long nowTs = System.currentTimeMillis();
        h.put("ts", Long.toString(nowTs));
        String nick = nickField.getText();
        if (nick != null && !nick.isBlank()) h.put("nick", nick);

        try {
            if (transport == UdpTransport.BROADCAST) {
                bcastService.send(MessageProtocol.TYPE_CHAT, h, text);
            } else {
                if (!joined.get()) { showError("Multicast: Must join a group first."); return; }
                if (mutedByHost) { showError("You are banned by the host."); return; }
                mcastService.send(MessageProtocol.TYPE_CHAT, h, text);
            }

            String selfIp = (currentIface != null) ? currentIface.address.getHostAddress() : "local";
            ChatMessage selfMsg = new ChatMessage(
                    (nick.isBlank() ? "You" : nick), selfIp, text, formatTs(Long.toString(nowTs)), true);
            addChatMessage(selfMsg, transport);
            input.clear();
        } catch (IOException e) {
            e.printStackTrace();
            showError("Send error: " + e.getMessage());
        }
    }

    private void appendSysMessage(String text) {
        ChatMessage sysMsg = new ChatMessage("system", "", text, TIME_FMT.format(LocalDateTime.now()), false);
        Platform.runLater(() -> addChatMessage(sysMsg, isMulticastMode() ? UdpTransport.MULTICAST : UdpTransport.BROADCAST));
    }

    private String pretty(String ip) {
        String nick = nickByIp.getOrDefault(ip, "");
        return nick.isBlank() ? ip : (ip + " — " + nick);
    }

    private void showError(String msg) {
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
            a.setHeaderText("Error");
            a.showAndWait();
        });
    }

    private String formatTs(String tsHeader) {
        try {
            long ms = (tsHeader == null || tsHeader.isBlank()) ? System.currentTimeMillis() : Long.parseLong(tsHeader);
            return TIME_FMT.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()));
        } catch (Exception e) {
            e.printStackTrace();
            return TIME_FMT.format(LocalDateTime.now());
        }
    }
}
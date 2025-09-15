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
import java.util.stream.Collectors;

public class ChatController implements UdpMessageListener {

    // --- FXML поля для UI элементов ---
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

    // --- Списки для хранения сообщений и участников ---
    private final ObservableList<ChatMessage> broadcastMessages = FXCollections.observableArrayList();
    private final ObservableList<ChatMessage> multicastMessages = FXCollections.observableArrayList();
    private final ObservableList<String> peers = FXCollections.observableArrayList();
    private final ObservableList<String> bannedItems = FXCollections.observableArrayList();

    // --- Сетевые и служебные компоненты ---
    private final Map<String, String> nickByIp = new ConcurrentHashMap<>();
    private final BlocklistManager blocklist = new BlocklistManager();
    private final RecentMessageCache dedup = new RecentMessageCache(4096, 30_000);
    private NetworkUtils.IfaceInfo currentIface;
    private UdpBroadcastService bcastService;
    private UdpMulticastService mcastService;
    private PeerDiscoveryService discovery;

    // --- Состояние чата ---
    private final AtomicBoolean joined = new AtomicBoolean(false);
    private String currentGroupAddr = null;
    private final Map<String, Boolean> seenInMulticast = new ConcurrentHashMap<>();
    private final Set<String> mcastHostBans = ConcurrentHashMap.newKeySet();
    private volatile boolean mutedByHost = false;
    private volatile String groupHostIp = null;

    // --- Константы ---
    private static final int DEFAULT_PORT = 50000;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @FXML
    public void initialize() {
        // Инициализация UI элементов
        modeChoice.getItems().addAll("Broadcast", "Multicast");
        modeChoice.setValue("Broadcast");

        multicastGroupField.setText("239.255.0.1");
        portField.setText(String.valueOf(DEFAULT_PORT));
        nickField.setText("user-" + (int) (Math.random() * 1000));

        // Настройка фабрик ячеек для всех списков
        setupChatListView(chatBroadcast, broadcastMessages);
        setupChatListView(chatMulticast, multicastMessages);
        peersList.setItems(peers);
        setupPeersListCellFactory();
        setupPeersListContextMenu();
        bannedList.setItems(bannedItems);
        setupBannedListContextMenu();

        // Автоопределение сетевых интерфейсов
        try {
            List<NetworkUtils.IfaceInfo> ifaces = NetworkUtils.enumerateIPv4Interfaces();
            if (ifaces.isEmpty()) {
                showError("Нет активных сетевых интерфейсов IPv4.");
                return;
            }
            ifaceCombo.getItems().addAll(ifaces);
            ifaceCombo.getSelectionModel().select(0);
            onInterfaceSelected();
        } catch (Exception e) {
            showError("Ошибка при определении сети: " + e.getMessage());
        }

        // Назначение обработчиков событий
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

        // Обновление UI в соответствии с начальным состоянием
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
                    setGraphic(null);
                } else {
                    String nick = nickByIp.getOrDefault(ip, "");
                    setText(nick.isBlank() ? ip : ip + " — " + nick);
                }
            }
        });
    }

    private void setupPeersListContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem ignore = new MenuItem("Игнорировать (локально)");
        MenuItem unignore = new MenuItem("Снять игнор (локально)");
        MenuItem banInGroup = new MenuItem("Заблокировать в группе (хост)");
        MenuItem unbanInGroup = new MenuItem("Снять блок в группе (хост)");

        ignore.setOnAction(e -> handlePeerAction(this::doLocalBlock));
        unignore.setOnAction(e -> handlePeerAction(blocklist::unblock));

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
        String selfIp = currentIface != null ? currentIface.address.getHostAddress() : null;
        if (ip.equals(selfIp)) {
            showError("Нельзя игнорировать свой собственный IP.");
            return;
        }
        blocklist.block(ip);
        appendSysMessage("Локально игнорируется " + pretty(ip));
    }

    private void handlePeerAction(java.util.function.Consumer<String> action) {
        String ip = peersList.getSelectionModel().getSelectedItem();
        if (ip != null) {
            action.accept(ip);
        }
    }

    private void setupBannedListContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem unban = new MenuItem("Снять блок (хост)");
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
            ft.setFromValue(0);
            ft.setToValue(1);
            ft.play();
        } else {
            FadeTransition ft = new FadeTransition(Duration.millis(300), mutedLabel);
            ft.setFromValue(1);
            ft.setToValue(0);
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
                            } catch (Exception e) { return null; }
                        }
                    }
            );
            discovery.start();
        } catch (Exception e) {
            showError("Не удалось запустить службы: " + e.getMessage());
        }
    }

    private void stopServices() {
        if (discovery != null) discovery.stop();
        if (bcastService != null) bcastService.stop();
        if (mcastService != null) try { mcastService.leave(); } catch (IOException ignored) {}
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

            appendSysMessage("Вошли в группу " + currentGroupAddr + ":" + portField.getText()
                    + (hostMulticast.isSelected() ? " (вы — хост)" : ""));
            refreshUiForMode();
            updateMutedUi();
        } catch (Exception e) {
            showError("Ошибка входа в группу: " + e.getMessage());
        }
    }

    private void doLeave() {
        try {
            if (mcastService != null && mcastService.isJoined()) {
                mcastService.leave();
                appendSysMessage("Покинули группу " + currentGroupAddr);
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
            showError("Ошибка выхода из группы: " + e.getMessage());
        }
    }

    private void refreshPeersListManual() {
        if (discovery == null) return;
        List<String> all = discovery.snapshotAllPeers();
        String selfIp = currentIface != null ? currentIface.address.getHostAddress() : null;
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
            appendSysMessage("Определён хост группы: " + pretty(groupHostIp));
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

                String actionText = isBlock ? "заблокировал" : "разблокировал";
                if (currentIface != null && target.equals(currentIface.address.getHostAddress())) {
                    mutedByHost = isBlock;
                    String selfStatus = isBlock ? "Вы заблокированы хостом" : "Хост снял блок";
                    appendSysMessage(selfStatus + " и не можете писать в эту группу.");
                    refreshUiForMode();
                    updateMutedUi();
                } else {
                    appendSysMessage("Хост " + actionText + " " + pretty(target));
                }
            }
            default -> {} // HELLO обрабатывается в discovery, другие типы игнорируем
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
            showError("Эта функция доступна только хосту в активной Multicast группе.");
            return;
        }
        String selfIp = currentIface != null ? currentIface.address.getHostAddress() : null;
        if (ip.equals(selfIp)) {
            showError("Нельзя заблокировать самого себя.");
            return;
        }
        try {
            Map<String, String> h = new HashMap<>();
            h.put("id", MessageIds.next());
            h.put("target", ip);
            mcastService.send(ban ? MessageProtocol.TYPE_MBLOCK : MessageProtocol.TYPE_MUNBLOCK, h, "");
            String action = ban ? "заблокирован" : "разблокирован";
            if (ban) mcastHostBans.add(ip); else mcastHostBans.remove(ip);
            syncBannedItems();
            appendSysMessage("Хост: " + action + " " + pretty(ip));
        } catch (IOException ex) {
            showError("Ошибка отправки команды: " + ex.getMessage());
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
                if (!joined.get()) { showError("Multicast: сначала Join."); return; }
                if (mutedByHost) { showError("Вы заблокированы хостом."); return; }
                mcastService.send(MessageProtocol.TYPE_CHAT, h, text);
            }

            String selfIp = currentIface != null ? currentIface.address.getHostAddress() : "local";
            assert nick != null;
            ChatMessage selfMsg = new ChatMessage(
                    (nick.isBlank() ? "Вы" : nick), selfIp, text, formatTs(Long.toString(nowTs)), true);
            addChatMessage(selfMsg, transport);
            input.clear();
        } catch (IOException e) {
            showError("Ошибка отправки: " + e.getMessage());
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
            a.setHeaderText("Ошибка");
            a.showAndWait();
        });
    }

    private String formatTs(String tsHeader) {
        try {
            long ms = (tsHeader == null || tsHeader.isBlank()) ? System.currentTimeMillis() : Long.parseLong(tsHeader);
            LocalDateTime ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
            return TIME_FMT.format(ldt);
        } catch (Exception e) {
            return TIME_FMT.format(LocalDateTime.now());
        }
    }
}
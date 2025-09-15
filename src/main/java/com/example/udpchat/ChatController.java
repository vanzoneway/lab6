package com.example.udpchat;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class ChatController implements UdpMessageListener {

    @FXML
    private TabPane tabs;

    @FXML
    private Tab tabBroadcast;

    @FXML
    private Tab tabMulticast;

    @FXML
    private TextArea chatBroadcast;

    @FXML
    private TextField inputBroadcast;

    @FXML
    private Button sendBroadcast;

    @FXML
    private TextArea chatMulticast;

    @FXML
    private TextField inputMulticast;

    @FXML
    private Button sendMulticast;

    @FXML
    private Label mutedLabel;

    @FXML
    private ChoiceBox<String> modeChoice;

    @FXML
    private ComboBox<NetworkUtils.IfaceInfo> ifaceCombo;

    @FXML
    private TextField multicastGroupField;

    @FXML
    private TextField portField;

    @FXML
    private Button joinButton;

    @FXML
    private Button leaveButton;

    @FXML
    private CheckBox hostMulticast;

    @FXML
    private Label ipLabel;

    @FXML
    private Label maskLabel;

    @FXML
    private Label bcastLabel;

    @FXML
    private ListView<String> peersList;

    @FXML
    private Button refreshPeersButton;

    @FXML
    private ListView<String> bannedList;

    @FXML
    private TextField nickField;

    private final ObservableList<String> peers = FXCollections.observableArrayList();
    private final Map<String, String> nickByIp = new ConcurrentHashMap<>();
    private final BlocklistManager blocklist = new BlocklistManager();
    private final RecentMessageCache dedup = new RecentMessageCache(4096, 30_000);

    private final ObservableList<String> bannedItems = FXCollections.observableArrayList();

    private NetworkUtils.IfaceInfo currentIface;
    private UdpBroadcastService bcastService;
    private UdpMulticastService mcastService;
    private PeerDiscoveryService discovery;

    private final AtomicBoolean joined = new AtomicBoolean(false);
    private String currentGroupAddr = null;
    private final Map<String, Boolean> seenInMulticast = new ConcurrentHashMap<>();

    private final Set<String> mcastHostBans = ConcurrentHashMap.newKeySet();
    private volatile boolean mutedByHost = false;
    private volatile String groupHostIp = null;

    private static final int DEFAULT_PORT = 50000;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @FXML
    public void initialize() {
        modeChoice.getItems().addAll("Broadcast", "Multicast");
        modeChoice.setValue("Broadcast");

        multicastGroupField.setText("239.255.0.1");
        portField.setText(String.valueOf(DEFAULT_PORT));

        peersList.setItems(peers);
        setupPeersListCellFactory();
        setupPeersListContextMenu();

        bannedList.setItems(bannedItems);
        setupBannedListContextMenu();

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

        nickField.setText("user-" + (int) (Math.random() * 1000));

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

    private void setupPeersListCellFactory() {
        peersList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String ip, boolean empty) {
                super.updateItem(ip, empty);
                if (empty || ip == null) {
                    setText(null);
                } else {
                    String nick = nickByIp.getOrDefault(ip, "");
                    setText(nick == null || nick.isBlank() ? ip : ip + " — " + nick);
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

        ignore.setOnAction(e -> {
            String ip = peersList.getSelectionModel().getSelectedItem();
            if (ip != null) {
                String selfIp = currentIface != null ? currentIface.address.getHostAddress() : null;
                if (ip.equals(selfIp)) {
                    showError("Нельзя игнорировать свой собственный IP.");
                    return;
                }
                blocklist.block(ip);
                appendSys(activeChatArea(), "Локально игнорируется " + pretty(ip));
            }
        });
        unignore.setOnAction(e -> {
            String ip = peersList.getSelectionModel().getSelectedItem();
            if (ip != null) {
                blocklist.unblock(ip);
                appendSys(activeChatArea(), "Игнорирование снято: " + pretty(ip));
            }
        });

        banInGroup.setOnAction(e -> {
            String ip = peersList.getSelectionModel().getSelectedItem();
            if (ip != null) sendHostBan(true, ip);
        });
        unbanInGroup.setOnAction(e -> {
            String ip = peersList.getSelectionModel().getSelectedItem();
            if (ip != null) sendHostBan(false, ip);
        });

        menu.getItems().addAll(ignore, unignore, new SeparatorMenuItem(), banInGroup, unbanInGroup);

        peersList.setContextMenu(menu);
        peersList.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                menu.show(peersList, event.getScreenX(), event.getScreenY());
            }
        });
    }

    private void setupBannedListContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem unban = new MenuItem("Снять блок (хост)");
        unban.setOnAction(e -> {
            String ip = bannedList.getSelectionModel().getSelectedItem();
            if (ip != null) sendHostBan(false, ip);
        });
        menu.getItems().addAll(unban);

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
        maskLabel.setText(currentIface.netmask);
        bcastLabel.setText(currentIface.broadcast != null ? currentIface.broadcast.getHostAddress() : "неизвестно");
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
        boolean mcSendEnabled = (isMc && joined.get() && !mutedByHost);
        sendMulticast.setDisable(!mcSendEnabled);
        inputMulticast.setDisable(!mcSendEnabled);
        sendBroadcast.setDisable(isMc);
        inputBroadcast.setDisable(isMc);
    }

    private void updateMutedUi() {
        boolean show = isMulticastMode() && mutedByHost;
        mutedLabel.setVisible(show);
        mutedLabel.setManaged(show);
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
                    bcastService, mcastService, nickField.getText(), 2000,
                    (ip, added, transport, grpAddr) -> Platform.runLater(() -> {
                        if (added) {
                            if (!peers.contains(ip)) peers.add(ip);
                            if (transport == UdpTransport.MULTICAST && currentGroupAddr != null && currentGroupAddr.equals(grpAddr)) {
                                if (seenInMulticast.putIfAbsent(ip, true) == null) {
                                    appendSys(chatMulticast, "HELLO от " + pretty(ip) + " (группа " + grpAddr + ")");
                                }
                            }
                        } else {
                            peers.remove(ip);
                            if (transport == UdpTransport.MULTICAST && currentGroupAddr != null && currentGroupAddr.equals(grpAddr)) {
                                seenInMulticast.remove(ip);
                                appendSys(chatMulticast, "Отключился " + pretty(ip) + " (группа " + grpAddr + ")");
                            }
                        }
                    }),
                    new PeerDiscoveryService.ModeSelector() {
                        @Override
                        public boolean useBroadcast() {
                            return !isMulticastMode();
                        }

                        @Override
                        public boolean useMulticast() {
                            return isMulticastMode() && joined.get();
                        }

                        @Override
                        public InetAddress currentMulticastGroup() {
                            try {
                                return joined.get() ? InetAddress.getByName(multicastGroupField.getText()) : null;
                            } catch (Exception e) {
                                return null;
                            }
                        }
                    }
            );

            discovery.start();
        } catch (Exception e) {
            showError("Не удалось запустить службы: " + e.getMessage());
        }
    }

    private void stopServices() {
        try {
            if (discovery != null) discovery.stop();
        } catch (Exception ignored) {
        }
        try {
            if (bcastService != null) bcastService.stop();
        } catch (Exception ignored) {
        }
        try {
            if (mcastService != null) mcastService.leave();
        } catch (Exception ignored) {
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
    }

    private void doJoin() {
        try {
            InetAddress newGroup = InetAddress.getByName(multicastGroupField.getText());
            if (mcastService == null) return;
            if (mcastService.isJoined()) {
                if (!mcastService.currentGroup().equals(newGroup)) mcastService.switchGroup(newGroup);
            } else {
                mcastService.switchGroup(newGroup);
            }
            joined.set(true);
            currentGroupAddr = newGroup.getHostAddress();
            seenInMulticast.clear();
            mcastHostBans.clear();
            bannedItems.clear();
            mutedByHost = false;
            groupHostIp = hostMulticast.isSelected() && currentIface != null ? currentIface.address.getHostAddress() : null;

            appendSys(chatMulticast, "Вошли в группу " + currentGroupAddr + ":" + portField.getText()
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
                appendSys(chatMulticast, "Покинули группу " + currentGroupAddr);
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
        if (selfIp != null) all.removeIf(selfIp::equals);
        peers.setAll(all);
        peersList.refresh();
    }

    @Override
    public void onMessage(UdpTransport transport, InetAddress from, MessageProtocol.Parsed msg, InetAddress group) {
        String fromIp = from.getHostAddress();

        if (currentIface != null && fromIp.equals(currentIface.address.getHostAddress())) return;

        String mid = msg.headers.get("id");
        if (dedup.isDuplicateAndRecord(mid)) return;

        if (discovery != null) discovery.seen(transport, from);

        String nickHdr = msg.headers.get("nick");
        if (nickHdr != null && !nickHdr.isBlank()) {
            nickByIp.put(fromIp, nickHdr);
            Platform.runLater(peersList::refresh);
        }

        if (isMulticastMode()) {
            if (transport == UdpTransport.BROADCAST) return;
            String grpHdr = msg.headers.get("grp");
            if (currentGroupAddr != null && grpHdr != null && !currentGroupAddr.equals(grpHdr)) return;
        } else {
            if (transport == UdpTransport.MULTICAST) return;
        }

        if (transport == UdpTransport.MULTICAST && currentGroupAddr != null) {
            String hostHdr = msg.headers.get("host");
            if ("1".equals(hostHdr) && groupHostIp == null) {
                groupHostIp = fromIp;
                appendSys(chatMulticast, "Определён хост группы: " + pretty(groupHostIp));
            }
        }

        if (blocklist.isBlocked(from) && MessageProtocol.TYPE_CHAT.equals(msg.type)) return;

        switch (msg.type) {
            case MessageProtocol.TYPE_CHAT -> {
                if (transport == UdpTransport.MULTICAST && currentGroupAddr != null && mcastHostBans.contains(fromIp)) {
                    return;
                }
                String nick = (nickHdr != null && !nickHdr.isBlank()) ? nickHdr : fromIp;
                String text = msg.payload;

                String tsHdr = msg.headers.get("ts");
                String tstr = formatTs(tsHdr);

                TextArea area = (transport == UdpTransport.MULTICAST) ? chatMulticast : chatBroadcast;
                final String line = "[" + tstr + "] [" + nick + "@" + fromIp + "]: " + text + "\n";
                Platform.runLater(() -> area.appendText(line));
            }
            case MessageProtocol.TYPE_HELLO -> {
                if (transport == UdpTransport.MULTICAST && currentGroupAddr != null) {
                    String grpHdr = msg.headers.get("grp");
                    if (currentGroupAddr.equals(grpHdr)) {
                        if (seenInMulticast.putIfAbsent(fromIp, true) == null) {
                            appendSys(chatMulticast, "HELLO от " + pretty(fromIp) + " (группа " + grpHdr + ")");
                        }
                    }
                }
            }
            case MessageProtocol.TYPE_MBLOCK -> {
                if (transport != UdpTransport.MULTICAST) return;
                String grpHdr = msg.headers.get("grp");
                if (currentGroupAddr == null || !currentGroupAddr.equals(grpHdr)) return;
                if (groupHostIp == null || !groupHostIp.equals(fromIp)) return;

                String target = msg.headers.get("target");
                if (target == null || target.isBlank()) return;

                mcastHostBans.add(target);
                Platform.runLater(this::syncBannedItems);
                if (currentIface != null && target.equals(currentIface.address.getHostAddress())) {
                    mutedByHost = true;
                    appendSys(chatMulticast, "Вы заблокированы хостом и не можете писать в эту группу.");
                    refreshUiForMode();
                    updateMutedUi();
                } else {
                    appendSys(chatMulticast, "Хост заблокировал " + pretty(target));
                }
            }
            case MessageProtocol.TYPE_MUNBLOCK -> {
                if (transport != UdpTransport.MULTICAST) return;
                String grpHdr = msg.headers.get("grp");
                if (currentGroupAddr == null || !currentGroupAddr.equals(grpHdr)) return;
                if (groupHostIp == null || !groupHostIp.equals(fromIp)) return;

                String target = msg.headers.get("target");
                if (target == null || target.isBlank()) return;

                mcastHostBans.remove(target);
                Platform.runLater(this::syncBannedItems);
                if (currentIface != null && target.equals(currentIface.address.getHostAddress())) {
                    mutedByHost = false;
                    appendSys(chatMulticast, "Хост снял блок — теперь вы можете писать в группу.");
                    refreshUiForMode();
                    updateMutedUi();
                } else {
                    appendSys(chatMulticast, "Хост разблокировал " + pretty(target));
                }
            }
            default -> {
            }
        }
    }

    private void sendHostBan(boolean ban, String ip) {
        if (!isMulticastMode() || !joined.get()) {
            showError("Доступно только в Multicast и после Join.");
            return;
        }
        if (!hostMulticast.isSelected()) {
            showError("Только хост может отправлять блокировку.");
            return;
        }
        if (ip == null) {
            showError("Не выбран участник.");
            return;
        }
        String selfIp = (currentIface != null ? currentIface.address.getHostAddress() : null);
        if (ip.equals(selfIp)) {
            showError("Нельзя заблокировать самого себя.");
            return;
        }

        try {
            Map<String, String> h = new HashMap<>();
            h.put("id", MessageIds.next());
            if (currentGroupAddr != null) h.put("grp", currentGroupAddr);
            mcastService.send(ban ? MessageProtocol.TYPE_MBLOCK : MessageProtocol.TYPE_MUNBLOCK,
                    addTarget(h, ip), "");
            if (ban) {
                mcastHostBans.add(ip);
                syncBannedItems();
                appendSys(chatMulticast, "Хост: заблокирован " + pretty(ip));
            } else {
                mcastHostBans.remove(ip);
                syncBannedItems();
                appendSys(chatMulticast, "Хост: разблокирован " + pretty(ip));
            }
        } catch (IOException ex) {
            showError("Ошибка отправки команды: " + ex.getMessage());
        }
    }

    private Map<String, String> addTarget(Map<String, String> h, String ip) {
        h.put("target", ip);
        return h;
    }

    private void syncBannedItems() {
        List<String> sorted = new ArrayList<>(mcastHostBans);
        Collections.sort(sorted);
        bannedItems.setAll(sorted);
        bannedList.refresh();
    }

    private void sendMessage(UdpTransport transport) {
        TextField input = (transport == UdpTransport.MULTICAST) ? inputMulticast : inputBroadcast;
        TextArea area = (transport == UdpTransport.MULTICAST) ? chatMulticast : chatBroadcast;

        String text = input.getText();
        if (text == null || text.isBlank()) return;

        Map<String, String> h = new HashMap<>();
        h.put("id", MessageIds.next()); // для дедупа

        // === NEW: timestamp header (millis since epoch) ===
        long nowTs = System.currentTimeMillis();
        h.put("ts", Long.toString(nowTs));

        String nick = nickField.getText();
        if (nick != null && !nick.isBlank()) h.put("nick", nick);

        try {
            if (transport == UdpTransport.BROADCAST) {
                if (isMulticastMode()) {
                    showError("Сейчас выбран Multicast. Переключитесь на Broadcast.");
                    return;
                }

                // скрытый unicast-фолбэк всем известным пирами (кроме себя и заблокированных локально)
                List<InetAddress> extra = Collections.emptyList();
                if (discovery != null) {
                    String selfIp = currentIface != null ? currentIface.address.getHostAddress() : null;
                    Set<String> localBlocked = blocklist.current();
                    extra = discovery.snapshotAllPeers().stream()
                            .filter(ip -> !Objects.equals(ip, selfIp))
                            .filter(ip -> !localBlocked.contains(ip))
                            .map(ip -> {
                                try {
                                    return InetAddress.getByName(ip);
                                } catch (Exception e) {
                                    return null;
                                }
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                }
                bcastService.sendAll(MessageProtocol.TYPE_CHAT, h, text, extra);

            } else {
                if (!joined.get()) {
                    showError("Multicast: сначала Join.");
                    return;
                }
                if (mutedByHost) {
                    showError("Вы заблокированы хостом и не можете писать в эту группу.");
                    return;
                }
                if (currentGroupAddr != null) h.put("grp", currentGroupAddr);
                mcastService.send(MessageProtocol.TYPE_CHAT, h, text);
            }

            String selfIp = currentIface != null ? currentIface.address.getHostAddress() : "local";
            String tstr = formatTs(Long.toString(nowTs));
            area.appendText("[" + tstr + "] [" + (nick == null || nick.isBlank() ? selfIp : nick) + "@" + selfIp + "]: " + text + "\n");
            input.clear();
        } catch (IOException e) {
            showError("Ошибка отправки: " + e.getMessage());
        }
    }

    private TextArea activeChatArea() {
        return isMulticastMode() ? chatMulticast : chatBroadcast;
    }

    private void appendSys(TextArea area, String s) {
        Platform.runLater(() -> area.appendText("[sys] " + s + "\n"));
    }

    private String pretty(String ip) {
        String nick = nickByIp.getOrDefault(ip, "");
        return (nick == null || nick.isBlank()) ? ip : (ip + " — " + nick);
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

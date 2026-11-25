package com.mgames.minecraft.rpg.lobby;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class LobbyPlugin extends JavaPlugin implements Listener {

    private static final NamespacedKey SESSION_COOKIE = new NamespacedKey("rpg_lobby", "session");
    private static final NamespacedKey TICKET_COOKIE = new NamespacedKey("rpg_lobby", "ticket");
    private static final NamespacedKey TRUSTED_LOGIN = new NamespacedKey("rpg_lobby", "trusted_login");
    private static final int SPAWN_Y = 65;
    private static final int MAX_MENU_SIZE = 54;
    private static final int PAGE_SIZE = 45;

    private final Map<UUID, Session> sessions = new HashMap<>();
    private LobbyApi api;

    @Override
    public void onEnable(){
        try{
            api = new LobbyApi();
        }catch (IllegalArgumentException exception){
            getLogger().severe(exception.getMessage());
            Bukkit.shutdown();
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id){
        return new ChunkGenerator() {
        };
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event){
        World world = Bukkit.getWorlds().getFirst();
        for (int x = -5; x <= 5; x++){
            for (int z = -5; z <= 5; z++){
                Material block = Math.abs(x) == 5 || Math.abs(z) == 5 ? Material.SEA_LANTERN : Material.SMOOTH_STONE;
                world.getBlockAt(x, SPAWN_Y - 1, z).setType(block, false);
            }
        }
        world.setSpawnLocation(0, SPAWN_Y, 0);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event){
        Player player = event.getPlayer();
        Session session = new Session();
        sessions.put(player.getUniqueId(), session);
        player.setGameMode(GameMode.ADVENTURE);
        onPlayer(player, session, () -> player.teleport(spawn()));

        session.busy = true;
        player.retrieveCookie(SESSION_COOKIE).orTimeout(3, TimeUnit.SECONDS).whenComplete((cookie, failure) ->
                onPlayer(player, session, () -> {
                    String token;
                    if (failure == null && cookie != null && cookie.length == 43){
                        token = new String(cookie, StandardCharsets.US_ASCII);
                    }else{
                        token = rememberedToken(player);
                    }

                    if (token == null){
                        session.busy = false;
                        promptLogin(player);
                        return;
                    }

                    complete(player, session, api.session(player, token), response -> {
                        if (response != null && response.authenticated()){
                            openAuthenticated(player, session, token);
                        }else{
                            promptLogin(player);
                        }
                    });
                }));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event){
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event){
        event.setCancelled(true);
        Player player = event.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(event.originalMessage());
        Bukkit.getScheduler().runTask(this, () -> handlePassword(player, text));
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event){
        if (event.getTo() != null && event.getTo().getY() < SPAWN_Y - 4){
            event.setTo(spawn());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args){
        if (!(sender instanceof Player player)){
            return true;
        }

        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.busy){
            player.sendMessage("Подожди, проверяем аккаунт.");
            return true;
        }

        if (command.getName().equals("modes")){
            if (session.token == null){
                promptLogin(player);
            }else{
                openModes(player, session);
            }
            return true;
        }
        if (args.length != 0){
            player.sendMessage("Используй команду без пароля. Пароль вводится следующим сообщением.");
            return true;
        }
        if (session.token != null){
            openModes(player, session);
            return true;
        }

        session.password = null;
        session.input = command.getName().equals("register") ? Input.REGISTER : Input.LOGIN;
        player.sendMessage("Введи пароль в чат. Сообщение увидит только плагин входа.");
        return true;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event){
        if (!(event.getWhoClicked() instanceof Player player)){
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.menu == null || session.menu.inventory() != event.getView().getTopInventory()){
            return;
        }

        event.setCancelled(true);
        Menu menu = session.menu;
        int slot = event.getRawSlot();
        if (session.busy || session.token == null || slot < 0 || slot >= menu.inventory().getSize()){
            return;
        }

        if (menu.modes().size() > MAX_MENU_SIZE && slot >= PAGE_SIZE){
            int page = menu.page();
            if (slot == PAGE_SIZE && page > 0){
                changePage(player, session, menu, page - 1);
            }else if (slot == MAX_MENU_SIZE - 1 && (page + 1) * PAGE_SIZE < menu.modes().size()){
                changePage(player, session, menu, page + 1);
            }
            return;
        }

        int index = menu.page() * PAGE_SIZE + slot;
        if (index >= menu.modes().size()){
            return;
        }

        LobbyApi.Mode mode = menu.modes().get(index);
        if (!mode.canJoin()){
            return;
        }

        complete(player, session, api.ticket(player, session.token, mode.serverId()), response -> {
            if (response != null && response.ticket() != null && response.host() != null
                    && !response.host().isBlank() && response.port() > 0 && response.port() <= 65535){
                player.storeCookie(TICKET_COOKIE, response.ticket().getBytes(StandardCharsets.US_ASCII));
                player.transfer(response.host(), response.port());
            }else if (response != null && "invalid_session".equals(response.error())){
                endSession(player, session);
            }else{
                player.sendMessage("Сейчас не удалось войти в " + mode.name() + ".");
                if (player.getOpenInventory().getTopInventory() == menu.inventory()){
                    openModes(player, session);
                }
            }
        });
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event){
        Session session = sessions.get(event.getWhoClicked().getUniqueId());
        if (session != null && session.menu != null && session.menu.inventory() == event.getView().getTopInventory()){
            event.setCancelled(true);
        }
    }

    private void handlePassword(Player player, String text){
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.input == Input.NONE || session.busy || session.token != null){
            return;
        }

        Input input = session.input;
        session.input = Input.NONE;
        if (input == Input.REGISTER){
            session.password = text;
            session.input = Input.REGISTER_CONFIRM;
            player.sendMessage("Повтори пароль.");
            return;
        }

        String password = session.password;
        session.password = null;
        if (input == Input.REGISTER_CONFIRM && !text.equals(password)){
            player.sendMessage("Пароли не совпали. Используй /register ещё раз.");
            return;
        }
        if (input == Input.LOGIN || input == Input.REGISTER_CONFIRM){
            password = text;
        }

        String currentPassword = password;
        CompletableFuture<LobbyApi.Response> request = api.password(player, input == Input.REGISTER_CONFIRM,
                currentPassword, input == Input.CHANGE_PASSWORD ? text : null);
        complete(player, session, request, response -> {
            if (response != null && response.session() != null){
                String address = remoteAddress(player);
                if (address != null){
                    player.getPersistentDataContainer().set(TRUSTED_LOGIN, PersistentDataType.STRING,
                            address + "|" + response.session());
                }else{
                    player.getPersistentDataContainer().remove(TRUSTED_LOGIN);
                }
                openAuthenticated(player, session, response.session());
                return;
            }
            if (response != null && "password_change_required".equals(response.error())){
                session.password = currentPassword;
                session.input = Input.CHANGE_PASSWORD;
                player.sendMessage("Введи новый пароль от 6 до 64 символов.");
                return;
            }
            if (response != null && "rate_limited".equals(response.error())){
                player.sendMessage("Слишком много попыток. Попробуй снова через минуту.");
                return;
            }
            player.sendMessage("Войти не удалось. Проверь пароль или попробуй позже.");
        });
    }

    private void complete(Player player, Session session, CompletableFuture<LobbyApi.Response> request,
                          Consumer<LobbyApi.Response> action){
        session.busy = true;
        request.whenComplete((response, failure) -> onPlayer(player, session, () -> {
            session.busy = false;
            action.accept(failure == null ? response : null);
        }));
    }

    private void onPlayer(Player player, Session session, Runnable action){
        if (!isEnabled()){
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            if (sessions.get(player.getUniqueId()) == session && player.isOnline()){
                action.run();
            }
        });
    }

    private String remoteAddress(Player player){
        InetSocketAddress remote = player.getAddress();
        if (remote == null || remote.getAddress() == null){
            return null;
        }
        return remote.getAddress().getHostAddress();
    }

    private String rememberedToken(Player player){
        String address = remoteAddress(player);
        String saved = player.getPersistentDataContainer().get(TRUSTED_LOGIN, PersistentDataType.STRING);
        if (address == null || saved == null || !saved.startsWith(address + "|")){
            return null;
        }
        return saved.substring(address.length() + 1);
    }

    private void openAuthenticated(Player player, Session session, String token){
        session.token = token;
        player.storeCookie(SESSION_COOKIE, token.getBytes(StandardCharsets.US_ASCII));
        openModes(player, session);
    }

    private void openModes(Player player, Session session){
        complete(player, session, api.modes(player, session.token), response -> {
            if (response != null && response.modes() != null){
                if (response.modes().isEmpty()){
                    closeModes(player, session);
                    player.sendMessage("Список режимов пока пуст.");
                }else{
                    showModes(player, session, response.modes(), 0);
                }
            }else if (response != null && "invalid_session".equals(response.error())){
                endSession(player, session);
            }else{
                player.sendMessage("Не удалось загрузить режимы. Попробуй /modes чуть позже.");
            }
        });
    }

    private void showModes(Player player, Session session, List<LobbyApi.Mode> modes, int page){
        boolean paged = modes.size() > MAX_MENU_SIZE;
        int size = paged ? MAX_MENU_SIZE : ((modes.size() + 8) / 9) * 9;
        int first = page * PAGE_SIZE;
        int end = paged ? Math.min(first + PAGE_SIZE, modes.size()) : modes.size();
        Inventory menu = Bukkit.createInventory(null, size, Component.text("Игровые режимы"));
        for (int index = first; index < end; index++){
            menu.setItem(index - first, modeIcon(modes.get(index)));
        }

        if (page > 0){
            ItemStack previous = new ItemStack(Material.ARROW);
            previous.editMeta(meta -> meta.displayName(Component.text("Предыдущая страница")));
            menu.setItem(PAGE_SIZE, previous);
        }
        if (end < modes.size()){
            ItemStack next = new ItemStack(Material.ARROW);
            next.editMeta(meta -> meta.displayName(Component.text("Следующая страница")));
            menu.setItem(MAX_MENU_SIZE - 1, next);
        }

        session.menu = new Menu(menu, modes, page);
        player.openInventory(menu);
    }

    private void changePage(Player player, Session session, Menu menu, int page){
        onPlayer(player, session, () -> {
            if (!session.busy && session.menu == menu && player.getOpenInventory().getTopInventory() == menu.inventory()){
                showModes(player, session, menu.modes(), page);
            }
        });
    }

    private ItemStack modeIcon(LobbyApi.Mode mode){
        Component environment = switch (mode.environment()){
            case "dev" -> Component.text("В разработке", NamedTextColor.YELLOW);
            case "test" -> Component.text("Сервер тестируется", NamedTextColor.AQUA);
            case "prod" -> Component.text("Активный режим", NamedTextColor.GREEN);
            case null, default -> Component.text("Статус неизвестен", NamedTextColor.GRAY);
        };
        List<Component> lore = new ArrayList<>();
        if (mode.serverName() != null && !mode.serverName().equals(mode.name())){
            lore.add(Component.text(mode.serverName(), NamedTextColor.GRAY));
        }
        lore.add(environment);

        if (!mode.online()){
            lore.add(Component.text("Офлайн", NamedTextColor.RED));
        }else if (mode.onlinePlayersKnown()){
            String count = Integer.toString(mode.onlinePlayers());
            if (mode.maxPlayers() > 0){
                count += " / " + mode.maxPlayers();
            }
            lore.add(Component.text("Онлайн · " + count, NamedTextColor.GREEN));
        }else{
            lore.add(Component.text("Онлайн", NamedTextColor.GREEN));
        }

        if (mode.online() && !mode.canJoin()){
            lore.add(Component.text("Вход недоступен", NamedTextColor.GRAY));
        }

        ItemStack icon = new ItemStack(mode.canJoin() ? Material.COMPASS : Material.GRAY_DYE);
        icon.editMeta(meta -> {
            meta.displayName(Component.text(mode.name()));
            meta.lore(lore);
        });
        return icon;
    }

    private void endSession(Player player, Session session){
        closeModes(player, session);
        session.token = null;
        promptLogin(player);
    }

    private void closeModes(Player player, Session session){
        Menu menu = session.menu;
        session.menu = null;
        if (menu != null && player.getOpenInventory().getTopInventory() == menu.inventory()){
            player.closeInventory();
        }
    }

    private Location spawn(){
        return new Location(Bukkit.getWorlds().getFirst(), 0.5, SPAWN_Y, 0.5);
    }

    private void promptLogin(Player player){
        player.sendMessage("Используй /login для входа или /register для создания аккаунта.");
    }

    private static class Session {
        private String token;
        private String password;
        private Input input = Input.NONE;
        private boolean busy;
        private Menu menu;
    }

    private enum Input {
        NONE, LOGIN, REGISTER, REGISTER_CONFIRM, CHANGE_PASSWORD
    }

    private record Menu(Inventory inventory, List<LobbyApi.Mode> modes, int page) {
    }
}

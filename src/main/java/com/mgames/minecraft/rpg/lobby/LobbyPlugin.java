package com.mgames.minecraft.rpg.lobby;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
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
import org.bukkit.plugin.java.JavaPlugin;

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
    private static final int SPAWN_Y = 65;

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final List<Mode> modes = new ArrayList<>();
    private LobbyApi api;

    @Override
    public void onEnable(){
        saveDefaultConfig();
        try{
            api = new LobbyApi();
        }catch (IllegalArgumentException exception){
            getLogger().severe(exception.getMessage());
            Bukkit.shutdown();
            return;
        }

        loadModes();
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
                    if (failure != null || cookie == null || cookie.length != 43){
                        session.busy = false;
                        promptLogin(player);
                        return;
                    }

                    String token = new String(cookie, StandardCharsets.US_ASCII);
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
        if (session == null || session.menu != event.getView().getTopInventory()){
            return;
        }

        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (session.busy || session.token == null || slot < 0 || slot >= modes.size()){
            return;
        }

        Mode mode = modes.get(slot);
        complete(player, session, api.ticket(player, session.token, mode.id()), response -> {
            if (response != null && response.ticket() != null){
                player.storeCookie(TICKET_COOKIE, response.ticket().getBytes(StandardCharsets.US_ASCII));
                player.transfer(mode.host(), mode.port());
            }else if (response != null && "invalid_session".equals(response.error())){
                session.token = null;
                player.closeInventory();
                promptLogin(player);
            }else{
                player.sendMessage("Не удалось открыть " + mode.label() + ". Попробуй ещё раз.");
            }
        });
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event){
        Session session = sessions.get(event.getWhoClicked().getUniqueId());
        if (session != null && session.menu == event.getView().getTopInventory()){
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

    private void openAuthenticated(Player player, Session session, String token){
        session.token = token;
        player.storeCookie(SESSION_COOKIE, token.getBytes(StandardCharsets.US_ASCII));
        openModes(player, session);
    }

    private void openModes(Player player, Session session){
        if (modes.isEmpty()){
            player.sendMessage("Доступные режимы появятся здесь после настройки сервера.");
            return;
        }

        int size = ((modes.size() + 8) / 9) * 9;
        Inventory menu = Bukkit.createInventory(null, size, Component.text("Игровые режимы"));
        for (int slot = 0; slot < modes.size(); slot++){
            Mode mode = modes.get(slot);
            ItemStack icon = new ItemStack(mode.icon());
            icon.editMeta(meta -> meta.displayName(Component.text(mode.label())));
            menu.setItem(slot, icon);
        }
        session.menu = menu;
        player.openInventory(menu);
    }

    private void loadModes(){
        ConfigurationSection section = getConfig().getConfigurationSection("modes");
        if (section == null){
            return;
        }

        for (String id: section.getKeys(false)){
            ConfigurationSection settings = section.getConfigurationSection(id);
            if (settings == null || modes.size() >= 54 || !id.matches("[A-Za-z0-9._-]{1,64}")){
                continue;
            }

            String host = settings.getString("host", "").trim();
            int port = settings.getInt("port", 25565);
            if (host.isEmpty() || port < 1 || port > 65535){
                continue;
            }

            Material icon = Material.matchMaterial(settings.getString("icon", ""));
            if (icon == null || !icon.isItem() || icon.isAir()){
                icon = Material.COMPASS;
            }
            modes.add(new Mode(id, settings.getString("label", id), host, port, icon));
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
        private Inventory menu;
    }

    private enum Input {
        NONE, LOGIN, REGISTER, REGISTER_CONFIRM, CHANGE_PASSWORD
    }

    private record Mode(String id, String label, String host, int port, Material icon) {
    }
}

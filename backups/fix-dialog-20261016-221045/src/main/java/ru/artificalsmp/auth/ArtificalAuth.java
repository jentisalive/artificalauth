package ru.artificalsmp.auth;
import com.google.inject.Inject;
import com.google.gson.*;
import com.velocitypowered.api.plugin.*;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.event.command.*;
import com.velocitypowered.api.command.*;
import com.velocitypowered.proxy.protocol.packet.ServerboundCustomClickActionPacket;
import net.elytrium.limboapi.api.*;
import net.elytrium.limboapi.api.event.*;
import net.elytrium.limboapi.api.player.*;
import net.elytrium.limboapi.api.chunk.*;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.Duration;

@Plugin(id="artificalauth",name="ArtificalAuth",version="0.1.0-beta",authors={"ArtificalSMP"},
    dependencies={@Dependency(id="limboapi")})
public final class ArtificalAuth {
    private final ProxyServer proxy;private final Logger log;private final Path dir;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),
        r->{Thread t=new Thread(r,"ArtificalAuth-State");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Map<UUID,Client> clients=new ConcurrentHashMap<>();
    private Config config;private Store store;private Telegram bot;private Engine engine;private Dialogs dialogs;
    private Limbo limbo;private volatile boolean ready;private volatile boolean stopping;
    @Inject public ArtificalAuth(ProxyServer proxy,Logger log,@DataDirectory Path dir){this.proxy=proxy;this.log=log;this.dir=dir;}
    @Subscribe public void initialize(ProxyInitializeEvent e){
        try{
            config=new Config(dir.resolve("config.yml"));dialogs=new Dialogs(config);store=new Store(dir.resolve("accounts.json"));
            LimboFactory factory=(LimboFactory)proxy.getPluginManager().getPlugin("limboapi").orElseThrow().getInstance().orElseThrow();
            VirtualWorld world=factory.createVirtualWorld(Dimension.OVERWORLD,0,64,0,0,0);
            limbo=factory.createLimbo(world).setName(config.s("limbo.name")).setGameMode(GameMode.ADVENTURE)
                .setViewDistance(2).setSimulationDistance(2).setWorldTime(6000)
                .setReadTimeout(config.i("security.auth-timeout-seconds")*1000);
            if(proxy.getServer(config.s("routing.target-server")).isEmpty())throw new IllegalStateException("Target server is not registered");
            bot=new Telegram(config,update->worker.execute(()->safe(()->update(update),null)),log::warn);
            engine=new Engine(config,store,bot,System::currentTimeMillis);
            registerCommands();
            proxy.getScheduler().buildTask(this,()->submit(()->engine.tick(),null)).repeat(Duration.ofSeconds(1)).schedule();
            if(!config.b("enabled")||config.token().isBlank()){
                log.warn("ArtificalAuth закрывает вход: заполните bot.token или переменную окружения, bot.username и enabled.");
                return;
            }
            worker.execute(()->{
                try{bot.start();ready=true;log.info("ArtificalAuth готов. Авторизация полностью на Velocity.");}
                catch(Exception failure){log.error("Telegram не запущен. Проверьте токен, имя бота и отсутствие второго обработчика.");}
            });
        }catch(Exception failure){log.error("ArtificalAuth не инициализирован; вход будет закрыт. Причина: {}",failure.getClass().getSimpleName());}
    }
    private void registerCommands(){
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder(config.s("commands.login")).plugin(this).build(),new SimpleCommand(){
            public void execute(Invocation i){
                if(!(i.source() instanceof Player p))return;Client cl=clients.get(p.getUniqueId());
                if(cl!=null)submit(()->engine.refresh(cl),cl);
            }
        });
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder(config.s("commands.admin")).plugin(this).build(),new SimpleCommand(){
            public boolean hasPermission(Invocation i){return i.source() instanceof ConsoleCommandSource;}
            public void execute(Invocation i){
                if(!(i.source() instanceof ConsoleCommandSource))return;
                String[] args=i.arguments();
                if(args.length==2&&args[0].equals(config.s("commands.unlock-subcommand"))){
                    submit(()->{engine.unlock(args[1]);i.source().sendMessage(mm(config.s("messages.admin-unlocked")));},null);
                }else i.source().sendMessage(mm(config.text("admin-usage",Map.of("command",config.s("commands.admin"),"unlock",config.s("commands.unlock-subcommand")))));
            }
        });
    }
    @Subscribe(order=PostOrder.LAST) public void preLogin(PreLoginEvent e){
        if(!ready)e.setResult(PreLoginEvent.PreLoginComponentResult.denied(mm(config==null?"ArtificalAuth: unavailable":config.s("messages.unavailable"))));
    }
    @Subscribe public void limboLogin(LoginLimboRegisterEvent e){
        e.addOnJoinCallback(()->{
            if(!ready){e.getPlayer().disconnect(mm(config.s("messages.unavailable")));return;}
            Client cl=new Client(e.getPlayer());Client old=clients.putIfAbsent(cl.id(),cl);
            if(old!=null){cl.kick(config.s("messages.already-online"));return;}
            limbo.spawnPlayer(cl.player,cl);
        });
    }
    @Subscribe(order=PostOrder.LAST) public void beforeServer(ServerPreConnectEvent e){
        Client cl=clients.get(e.getPlayer().getUniqueId());
        if(!ready||cl==null||!engine.allowed(cl))e.setResult(ServerPreConnectEvent.ServerResult.denied());
    }
    @Subscribe(order=PostOrder.LAST) public void command(CommandExecuteEvent e){
        if(e.getCommandSource() instanceof Player p){
            Client cl=clients.get(p.getUniqueId());
            if(cl==null||engine==null||!engine.allowed(cl)){
                e.setResult(CommandExecuteEvent.CommandResult.denied());
                if(cl!=null&&e.getCommand().equalsIgnoreCase(config.s("commands.login")))submit(()->engine.refresh(cl),cl);
            }
        }
    }
    @Subscribe public void disconnect(DisconnectEvent e){
        Client cl=clients.remove(e.getPlayer().getUniqueId());if(cl!=null&&engine!=null)submit(()->engine.disconnect(cl),null);
    }
    private void update(JsonObject update) throws Exception {
        if(update.has("message")){
            JsonObject message=update.getAsJsonObject("message");
            if(!message.has("from")||!message.has("text"))return;
            JsonObject chat=message.getAsJsonObject("chat");
            if(!chat.get("type").getAsString().equals("private"))return;
            long owner=message.getAsJsonObject("from").get("id").getAsLong();
            if(owner!=chat.get("id").getAsLong())return;
            if(System.currentTimeMillis()/1000-message.get("date").getAsLong()>config.l("security.action-ttl-seconds"))return;
            engine.message(owner,message.get("text").getAsString());
        }else if(update.has("callback_query")){
            JsonObject q=update.getAsJsonObject("callback_query");if(!q.has("data")||!q.has("message"))return;
            long owner=q.getAsJsonObject("from").get("id").getAsLong();
            JsonObject chat=q.getAsJsonObject("message").getAsJsonObject("chat");
            if(!"private".equals(chat.get("type").getAsString())||owner!=chat.get("id").getAsLong())return;
            engine.callback(owner,q.get("data").getAsString(),q.get("id").getAsString());
        }
    }
    private net.kyori.adventure.text.Component mm(String s){return MiniMessage.miniMessage().deserialize(s);}
    interface Job {void run() throws Exception;}
    private void safe(Job job,Client cl){
        try{job.run();}catch(Exception e){
            log.error("Операция авторизации отклонена: {}. Секреты не записаны в журнал.",e.getClass().getSimpleName());
            if(cl!=null)cl.kick(config.s("messages.unavailable"));
        }
    }
    private void submit(Job job,Client cl){
        if(stopping)return;
        try{worker.execute(()->safe(job,cl));}
        catch(RejectedExecutionException e){if(cl!=null)cl.kick(config.s("messages.busy"));}
    }
    @Subscribe public void shutdown(ProxyShutdownEvent e){
        ready=false;stopping=true;if(bot!=null)bot.close();
        proxy.getScheduler().tasksByPlugin(this).forEach(t->t.cancel());
        worker.shutdown();
        try{if(!worker.awaitTermination(20,TimeUnit.SECONDS))worker.shutdownNow();}catch(InterruptedException x){Thread.currentThread().interrupt();}
        if(engine!=null)engine.shutdown();
        try{if(store!=null)store.close();}catch(Exception ignored){}
        if(limbo!=null)limbo.dispose();
    }
    final class Client implements Engine.Client,LimboSessionHandler {
        final Player player;volatile LimboPlayer lp;volatile long nextPacket;
        Client(Player p){player=p;}
        public UUID id(){return player.getUniqueId();}
        public String name(){return player.getUsername();}
        public String ip(){return player.getRemoteAddress().getAddress().getHostAddress();}
        public boolean connected(){return player.isActive();}
        public void onSpawn(Limbo server,LimboPlayer p){
            lp=p;p.disableFalling();
            if(player.getProtocolVersion().getProtocol()<771){kick(config.s("messages.unsupported-version"));return;}
            submit(()->engine.begin(this),this);
        }
        public void show(String screen,Map<String,String> vars){
            LimboPlayer current=lp;
            if(current==null)return;
            current.getScheduledExecutor().execute(()->{
                if(!connected())return;
                try{current.writePacketAndFlush(dialogs.packet(screen,vars,player.getProtocolVersion()));}
                catch(Exception e){log.warn("Не удалось отправить диалог: {}",e.getClass().getSimpleName());kick(config.s("messages.unsupported-version"));}
            });
        }
        public void kick(String message){player.disconnect(mm(message));}
        public void allow(){
            LimboPlayer current=lp;
            if(current!=null)current.getScheduledExecutor().execute(()->{
                if(connected()&&engine.allowed(this))current.disconnect(proxy.getServer(config.s("routing.target-server")).orElseThrow());
            });
        }
        public void onChat(String text){
            if(!text.startsWith("/"))return;
            String[] args=text.substring(1).split("\\s+",2);
            if(args[0].equalsIgnoreCase(config.s("commands.login")))submit(()->engine.refresh(this),this);
        }
        public void onGeneric(Object packet){
            if(!(packet instanceof ServerboundCustomClickActionPacket click))return;
            long now=System.currentTimeMillis();if(now<nextPacket)return;nextPacket=now+300;
            try{
                Dialogs.Reply reply=Dialogs.reply(click.content(),player.getProtocolVersion());
                if(reply==null)return;
                submit(()->{
                    Engine.Session s=engine.session(this);
                    if(s==null||!s.nonce.equalsIgnoreCase(reply.nonce()))return;
                    switch(reply.action()){
                        case "password"->engine.password(this,s.nonce,reply.password());
                        case "refresh"->engine.refresh(this);
                        case "exit"->kick(config.s("messages.cancelled"));
                    }
                },this);
            }catch(Exception malformed){kick(config.s("messages.invalid-response"));}
        }
        // Leaving Limbo also calls onDisconnect: only the real Velocity DisconnectEvent closes the account session.
    }
}

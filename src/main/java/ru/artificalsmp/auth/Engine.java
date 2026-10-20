package ru.artificalsmp.auth;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
import java.util.function.LongSupplier;

/** All state transitions run on the plugin's single bounded worker, never on a Netty thread. */
public final class Engine {
    public interface Client {
        UUID id(); String name(); String ip(); boolean connected();
        void show(String screen,Map<String,String> values);
        void kick(String message); void allow();
    }
    public interface Bot {boolean send(long chat,String text,List<List<Button>> buttons);void answer(String id,String text);}
    public record Button(String label,String data){}
    public static final class Session {
        public final Client client;
        public final String nonce=Crypto.random(24,"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789");
        public final long deadline;
        public String code,stage;
        public int failures;
        public long nextPassword;
        public volatile boolean allowed;
        Session(Client c,long deadline){client=c;this.deadline=deadline;}
    }
    private record Action(String kind,long owner,String name,long revision,String nonce,long expires){}
    private final Config c;private final Store store;private final Bot bot;private final LongSupplier clock;
    private final Map<UUID,Session> sessions=new ConcurrentHashMap<>();
    private final Map<String,Action> actions=new HashMap<>();
    private final Map<String,Long> tgRate=new HashMap<>();
    private final Map<String,Long> blockedIp=new HashMap<>();
    private final Map<String,Integer> ipFailures=new HashMap<>();
    public Engine(Config c,Store store,Bot bot,LongSupplier clock){this.c=c;this.store=store;this.bot=bot;this.clock=clock;}
    public boolean allowed(Client cl){Session s=sessions.get(cl.id());return s!=null && s.client==cl && s.allowed && cl.connected();}
    public Session session(Client cl){Session s=sessions.get(cl.id());return s!=null && s.client==cl?s:null;}
    private long now(){return clock.getAsLong();}
    private Map<String,String> values(Session s){
        Map<String,String> v=new HashMap<>();v.put("player",s.client.name());v.put("ip",s.client.ip());
        v.put("code",s.code==null?"":s.code);v.put("bot",c.s("bot.username"));
        v.put("login-command",c.s("commands.login"));v.put("seconds",Long.toString(Math.max(0,(s.deadline-now())/1000)));
        v.put("nonce",s.nonce);return v;
    }
    private String game(String key){return c.text(key,Map.of());}
    private String msg(String key,Map<String,String> vars){
        Map<String,String> escaped=new HashMap<>();
        vars.forEach((k,v)->escaped.put(k,escape(v)));return c.text(key,escaped);
    }
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    private void show(Session s,String stage){s.stage=stage;s.client.show(stage,values(s));}
    public void refresh(Client cl){Session s=session(cl);if(s!=null&&!s.allowed&&valid(s))show(s,s.stage);}
    private boolean valid(Session s){return sessions.get(s.client.id())==s && s.client.connected() && now()<s.deadline;}
    public void begin(Client cl) throws IOException {
        if(blockedIp.getOrDefault(cl.ip(),0L)>now()){cl.kick(game("too-many-attempts"));return;}
        for(Session other:sessions.values())if(Store.key(other.client.name()).equals(Store.key(cl.name())) && other.client.connected()){
            cl.kick(game("already-online"));return;
        }
        Store.Account a=store.get(cl.name());
        if(a!=null && a.locked){cl.kick(game("contact-admin"));return;}
        Session s=new Session(cl,now()+c.l("security.auth-timeout-seconds")*1000);sessions.put(cl.id(),s);
        if(a==null){
            do{s.code=Crypto.random(c.i("security.code-length"),"ABCDEFGHJKLMNPQRSTUVWXYZ23456789");}
            while(codeCollision(s));
            show(s,"register");return;
        }
        if(!a.delivered){cl.kick(game("recover-required"));return;}
        if(a.ip.equals(cl.ip()) && a.lastSeen>0 && now()-a.lastSeen<c.l("security.session-hours")*3600000){
            authorize(s,a);return;
        }
        if(a.twoFactor){
            show(s,"telegram");
            String yes=action("approve",a,s),no=action("deny",a,s);
            Map<String,String> v=values(s);v.put("old-ip",a.ip.isEmpty()?c.s("messages.no-ip"):a.ip);
            if(!bot.send(a.telegram,msg("bot-approval",v),List.of(List.of(button("approve",yes),button("deny",no)))))
                fail(s,"bot-unavailable");
        }else show(s,"password");
    }
    private boolean codeCollision(Session current){
        return sessions.values().stream().anyMatch(s->s!=current&&Objects.equals(s.code,current.code));
    }
    private Button button(String key,String id){return new Button(c.s("messages.button-"+key),id);}
    private String action(String kind,Store.Account a,Session s){
        String id=Crypto.random(24,"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789");
        actions.put(id,new Action(kind,a.telegram,a.name,a.revision,s==null?"":s.nonce,now()+c.l("security.action-ttl-seconds")*1000));
        return id;
    }
    private void fail(Session s,String key){s.allowed=false;sessions.remove(s.client.id(),s);s.client.kick(game(key));}
    private void authorize(Session s,Store.Account a) throws IOException {
        if(!valid(s)||a.locked||!a.delivered)return;
        Store.Account fresh=store.get(a.name);
        if(fresh==null||fresh.locked||fresh.revision!=a.revision)return;
        a.ip=s.client.ip();a.lastSeen=now();store.save(a);
        s.code=null;s.stage="authenticated";s.allowed=true;
        s.client.allow();
    }
    public void password(Client cl,String nonce,String password) throws IOException {
        Session s=session(cl);
        if(s==null || s.allowed || !valid(s) || !s.nonce.equals(nonce) || !"password".equals(s.stage))return;
        if(now()<s.nextPassword)return;s.nextPassword=now()+c.l("security.password-delay-ms");
        Store.Account a=store.get(cl.name());
        if(a==null||a.locked||a.twoFactor||!a.delivered){fail(s,"contact-admin");return;}
        if(password.length()>128 || !Crypto.verify(password,a.hash)){
            s.failures++;int n=ipFailures.merge(cl.ip(),1,Integer::sum);
            if(s.failures>=c.i("security.max-password-attempts") || n>=c.i("security.max-ip-failures")){
                blockedIp.put(cl.ip(),now()+c.l("security.ip-lock-seconds")*1000);fail(s,"too-many-attempts");
            }else {Map<String,String> v=values(s);v.put("error",game("wrong-password"));s.client.show("password",v);}
            return;
        }
        ipFailures.remove(cl.ip());authorize(s,a);
    }
    public void disconnect(Client cl) throws IOException {
        Session s=session(cl);
        if(s==null)return;sessions.remove(cl.id(),s);
        if(s.allowed){
            Store.Account a=store.get(cl.name());
            if(a!=null&&!a.locked&&a.ip.equals(cl.ip())){a.lastSeen=now();store.save(a);}
        }
    }
    public void tick(){
        for(Session s:List.copyOf(sessions.values()))if(!s.allowed && now()>=s.deadline)fail(s,"timeout");
        actions.entrySet().removeIf(e->e.getValue().expires<now());
        tgRate.entrySet().removeIf(e->e.getValue()<now()-60000);
        blockedIp.entrySet().removeIf(e->e.getValue()<now());
        if(ipFailures.size()>10000)ipFailures.clear();
    }
    private boolean limited(long chat){
        String key=Long.toString(chat);long last=tgRate.getOrDefault(key,0L);
        if(now()-last<c.l("security.telegram-delay-ms"))return true;
        tgRate.put(key,now());return false;
    }
    public void message(long owner,String text) throws IOException {
        if(limited(owner))return;
        String raw=text.trim();
        String command=raw.split("\\s+",2)[0].split("@",2)[0];
        if(command.equals("/"+c.s("bot.commands.start")) && raw.contains(" "))raw=raw.substring(raw.indexOf(' ')+1).trim();
        if(raw.matches("[A-Za-z2-9]{8,32}")){bind(owner,raw.toUpperCase(Locale.ROOT));return;}
        Store.Account a=store.owner(owner);
        if(a==null){
            bot.send(owner,msg(store.hasTombstone(owner)?"bot-contact-admin":"bot-not-linked",Map.of()),List.of());return;
        }
        for(String kind:List.of("kick","recover","unlink","twofactor"))
            if(command.equals("/"+c.s("bot.commands."+kind))){request(owner,a,kind);return;}
        menu(owner,a);
    }
    private void bind(long owner,String code) throws IOException {
        if(store.owner(owner)!=null){bot.send(owner,msg("bot-already-linked",Map.of()),List.of());return;}
        Session s=sessions.values().stream().filter(x->"register".equals(x.stage)&&code.equals(x.code)&&valid(x)).findFirst().orElse(null);
        if(s==null){bot.send(owner,msg("bot-invalid-code",Map.of()),List.of());return;}
        if(store.get(s.client.name())!=null){bot.send(owner,msg("bot-invalid-code",Map.of()),List.of());return;}
        String password=Crypto.password(c.i("security.password-length"),c.s("security.password-alphabet"));
        Store.Account a=new Store.Account();a.name=s.client.name();a.telegram=owner;a.twoFactor=c.b("security.default-two-factor");
        a.hash=Crypto.hash(password,c.i("security.pbkdf2-iterations"));a.revision=1;
        store.save(a);s.code=null;
        Map<String,String> v=values(s);v.put("password",password);
        if(!bot.send(owner,msg("bot-linked",v),List.of())){fail(s,"recover-required");return;}
        a.delivered=true;store.save(a);authorize(s,a);menu(owner,a);
    }
    private void menu(long owner,Store.Account a){
        List<List<Button>> rows=new ArrayList<>();
        for(String kind:List.of("kick","recover","unlink","twofactor"))
            if(c.b("actions."+kind+".enabled"))rows.add(List.of(button(kind,action(kind,a,null))));
        bot.send(owner,msg("bot-menu",Map.of("player",a.name,"twofactor",c.s("messages."+(a.twoFactor?"state-on":"state-off")))),rows);
    }
    private void request(long owner,Store.Account a,String kind) throws IOException {
        if(!c.b("actions."+kind+".enabled")){bot.send(owner,msg("bot-disabled",Map.of()),List.of());return;}
        if(kind.equals("kick")){revoke(a.name,"remote-kick");bot.send(owner,msg("bot-kicked",Map.of("player",a.name)),List.of());return;}
        String operation=switch(kind){case "recover"->"recover-confirm";case "unlink"->"unlink-confirm";case "twofactor"->a.twoFactor?"disable-2fa":"enable-2fa";default->throw new IllegalArgumentException();};
        bot.send(owner,msg("bot-confirm-"+kind,Map.of("player",a.name)),List.of(List.of(button("confirm",action(operation,a,null)),button("cancel",action("cancel",a,null)))));
    }
    public void callback(long owner,String id,String callbackId) throws IOException {
        Action action=actions.get(id);
        if(action==null||action.owner!=owner||action.expires<now()){bot.answer(callbackId,c.s("messages.bot-expired"));return;}
        actions.remove(id);
        Store.Account a=store.get(action.name);
        if(a==null||a.locked||a.telegram!=owner||a.revision!=action.revision){bot.answer(callbackId,c.s("messages.bot-expired"));return;}
        bot.answer(callbackId,c.s("messages.bot-accepted"));
        if(action.kind.equals("approve")||action.kind.equals("deny")){
            Session s=sessions.values().stream().filter(x->x.nonce.equals(action.nonce)&&valid(x)&&!x.allowed&&"telegram".equals(x.stage)).findFirst().orElse(null);
            if(s==null){bot.send(owner,msg("bot-expired",Map.of()),List.of());return;}
            if(action.kind.equals("deny")){fail(s,"denied");bot.send(owner,msg("bot-denied",Map.of()),List.of());}
            else {authorize(s,a);bot.send(owner,msg("bot-approved",Map.of("player",a.name,"ip",s.client.ip())),List.of());}
            return;
        }
        switch(action.kind){
            case "kick","recover","unlink","twofactor" -> request(owner,a,action.kind);
            case "cancel" -> menu(owner,a);
            case "recover-confirm" -> {
                if(!c.b("actions.recover.enabled"))return;
                String password=Crypto.password(c.i("security.password-length"),c.s("security.password-alphabet"));
                a.hash=Crypto.hash(password,c.i("security.pbkdf2-iterations"));a.lastSeen=0;a.revision++;a.delivered=false;
                store.save(a);revoke(a.name,"password-reset");
                if(bot.send(owner,msg("bot-recovered",Map.of("password",password,"player",a.name)),List.of())){a.delivered=true;store.save(a);}
            }
            case "unlink-confirm" -> {
                if(!c.b("actions.unlink.enabled"))return;
                a.locked=true;a.lastSeen=0;a.ip="";a.revision++;store.save(a);revoke(a.name,"contact-admin");
                bot.send(owner,msg("bot-contact-admin",Map.of()),List.of());
            }
            case "disable-2fa","enable-2fa" -> {
                if(!c.b("actions.twofactor.enabled"))return;
                a.twoFactor=action.kind.equals("enable-2fa");a.lastSeen=0;a.revision++;store.save(a);
                revokePending(a.name);menu(owner,a);
            }
        }
    }
    private void revokePending(String name){for(Session s:List.copyOf(sessions.values()))if(!s.allowed&&Store.key(s.client.name()).equals(Store.key(name)))fail(s,"settings-changed");}
    public void revoke(String name,String reason){for(Session s:List.copyOf(sessions.values()))if(Store.key(s.client.name()).equals(Store.key(name)))fail(s,reason);}
    public void unlock(String name) throws IOException {store.releaseName(name);}
    public void shutdown(){for(Session s:List.copyOf(sessions.values()))fail(s,"unavailable");}
}

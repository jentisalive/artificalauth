package ru.artificalsmp.auth;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.io.*;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.*;
import io.netty.buffer.*;
import net.kyori.adventure.nbt.*;

public final class AuthTests {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static class Player implements Engine.Client{
        final UUID uuid=UUID.randomUUID();final String name,ip;
        boolean online=true,joined;String screen,kick;Map<String,String> vars;
        Player(String n,String ip){name=n;this.ip=ip;}
        public UUID id(){return uuid;}public String name(){return name;}public String ip(){return ip;}public boolean connected(){return online;}
        public void show(String s,Map<String,String> v){screen=s;vars=v;}
        public void kick(String m){kick=m;online=false;}public void allow(){joined=true;}
    }
    record Sent(long owner,String text,List<List<Engine.Button>> buttons){}
    static class Bot implements Engine.Bot{
        List<Sent> messages=new ArrayList<>();boolean fail;
        public boolean send(long owner,String text,List<List<Engine.Button>> buttons){messages.add(new Sent(owner,text,buttons));return !fail;}
        public void answer(String id,String text){}
        String button(String label){
            for(int i=messages.size()-1;i>=0;i--)for(var row:messages.get(i).buttons)for(var b:row)if(b.label().equals(label))return b.data();
            throw new AssertionError("Missing button "+label);
        }
        String password(){
            for(int i=messages.size()-1;i>=0;i--){String t=messages.get(i).text;if(t.contains("пароль: <code>")){
                int from=t.indexOf("пароль: <code>")+"пароль: <code>".length();return t.substring(from,t.indexOf("</code>",from));
            }}throw new AssertionError("No delivered password");
        }
    }
    static class Fixture implements AutoCloseable{
        Path dir;Config c;Store store;Bot bot=new Bot();AtomicLong time=new AtomicLong(1800000000000L);Engine engine;
        Fixture(Path template) throws Exception{
            dir=Files.createTempDirectory("artifical-auth-test-");
            String s=Files.readString(template).replace("pbkdf2-iterations: 600000","pbkdf2-iterations: 100000").replace("telegram-delay-ms: 1000","telegram-delay-ms: 0");
            Files.writeString(dir.resolve("config.yml"),s);c=new Config(dir.resolve("config.yml"));store=new Store(dir.resolve("accounts.json"));
            engine=new Engine(c,store,bot,time::get);
        }
        Player player(String name,String ip) throws Exception{Player p=new Player(name,ip);engine.begin(p);return p;}
        void bind(Player p,long owner) throws Exception{engine.message(owner,engine.session(p).code);}
        void callback(long owner,String label) throws Exception{engine.callback(owner,bot.button(label),"test");}
        void disconnect(Player p)throws Exception{p.online=false;engine.disconnect(p);}
        public void close()throws Exception{store.close();}
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),config=root.resolve("src/main/resources/config.yml");
        try(Fixture f=new Fixture(config)){
            Player p=f.player("Alice","1.2.3.4");String code=f.engine.session(p).code;
            check("register".equals(p.screen),"first connection displays registration");
            check(code.length()==10,"code length");
            f.bind(p,100);String password=f.bot.password();
            check(p.joined,"successful Telegram bind opens backend");
            check(password.length()==8 && password.chars().anyMatch(Character::isLetter)&&password.chars().anyMatch(x->!Character.isLetterOrDigit(x)),"password alphabet");
            check(!Files.readString(f.dir.resolve("accounts.json")).contains(password),"plaintext password never persisted");
            check(Crypto.verify(password,f.store.get("Alice").hash),"password hash verifies");
            check(!Crypto.verify(password+"x",f.store.get("Alice").hash),"wrong password rejected");
            f.engine.message(100,code);check(f.store.owner(100).name.equals("Alice"),"one-time code consumed");
            Player second=f.player("Bob","2.2.2.2");f.bind(second,100);check(!second.joined&&f.store.get("Bob")==null,"one Telegram one active account");
            f.disconnect(second);f.disconnect(p);
            Player auto=f.player("aLiCe","1.2.3.4");check(auto.joined,"case-insensitive nickname and remembered IP");
            f.disconnect(auto);
            Player changed=f.player("Alice","5.6.7.8");check("telegram".equals(changed.screen)&&!changed.joined,"new IP requires Telegram");
            String oldApprove=f.bot.button(f.c.s("messages.button-approve"));
            f.engine.callback(101,oldApprove,"wrong-owner");check(!changed.joined,"wrong Telegram cannot approve");
            f.callback(100,f.c.s("messages.button-deny"));
            check(!changed.online&&!changed.joined,"denial kicks");
            check(f.store.get("Alice").ip.equals("1.2.3.4"),"denial does not replace saved IP");
            Player retry=f.player("Alice","5.6.7.8");
            f.engine.callback(100,oldApprove,"stale");check(!retry.joined,"old approval cannot approve reconnect");
            f.callback(100,f.c.s("messages.button-approve"));check(retry.joined,"current approval allows");
            check(f.store.get("Alice").ip.equals("5.6.7.8"),"one saved IP replaced");
            f.disconnect(retry);f.time.addAndGet(5*3600000L+1);
            Player expired=f.player("Alice","5.6.7.8");check("telegram".equals(expired.screen),"five-hour expiry");
            f.engine.message(100,"/twofactor");f.callback(100,f.c.s("messages.button-confirm"));
            check(!f.store.get("Alice").twoFactor&&!expired.online,"two-factor toggle invalidates pending login");
            Player pass=f.player("Alice","9.9.9.9");check("password".equals(pass.screen),"disabled Telegram uses password even new IP");
            f.engine.password(pass,"wrong-nonce",password);check(!pass.joined,"wrong session nonce rejected");
            f.engine.password(pass,f.engine.session(pass).nonce,"wrong");check(!pass.joined,"incorrect password rejected");
            f.time.addAndGet(1500);f.engine.password(pass,f.engine.session(pass).nonce,password);check(pass.joined,"password-only login");
            f.engine.message(100,"/recover");f.callback(100,f.c.s("messages.button-confirm"));String newPassword=f.bot.password();
            check(!pass.online&&!Crypto.verify(password,f.store.get("Alice").hash),"recovery revokes session and old password");
            check(Crypto.verify(newPassword,f.store.get("Alice").hash),"new password stored");
            Player afterReset=f.player("Alice","9.9.9.9");check("password".equals(afterReset.screen),"recovery invalidates trusted session");
            f.engine.message(100,"/unlink");f.callback(100,f.c.s("messages.button-confirm"));
            check(!afterReset.online&&f.store.get("ALICE").locked,"unlink kicks and tombstones nickname");
            Player locked=f.player("ALICE","33.33.33.33");check(!locked.online&&!locked.joined,"locked nickname blocked for arbitrary IP");
            Player replacement=f.player("Carol","4.4.4.4");f.bind(replacement,100);check(replacement.joined,"Telegram may bind a new nick after unlink");
            f.store.close();f.store=new Store(f.dir.resolve("accounts.json"));
            check(f.store.get("alice").locked&&f.store.owner(100).name.equals("Carol"),"tombstone and new binding survive restart");
            f.store.releaseName("alice");check(f.store.get("alice")==null,"console can explicitly unlock tombstone");
        }
        try(Fixture f=new Fixture(config)){
            Player p=f.player("Timeout","1.1.1.1");String code=f.engine.session(p).code;
            f.time.addAndGet(181000);f.engine.tick();check(!p.online,"authentication timeout");
            f.engine.message(200,code);check(f.store.get("Timeout")==null,"expired code rejected");
            Player delivery=f.player("Delivery","8.8.8.8");f.bot.fail=true;f.bind(delivery,300);
            check(!delivery.online&&!f.store.get("Delivery").delivered,"failed Telegram delivery fails closed");
            f.bot.fail=false;f.engine.message(300,"/recover");f.callback(300,f.c.s("messages.button-confirm"));
            check(f.store.get("Delivery").delivered,"undelivered password recoverable");
        }
        try(Fixture f=new Fixture(config)){
            Player p=f.player("Brute","2.3.4.5");f.bind(p,400);f.engine.message(400,"/twofactor");f.callback(400,f.c.s("messages.button-confirm"));
            f.disconnect(p);Player attempt=f.player("Brute","9.8.7.6");
            for(int i=0;i<5;i++){f.engine.password(attempt,f.engine.session(attempt).nonce,"bad");f.time.addAndGet(1500);}
            check(!attempt.online,"password attempt cap");
            Player blocked=f.player("Other","9.8.7.6");check(!blocked.online,"temporary IP rate limit");
        }
        Config c=new Config(config);Dialogs dialogs=new Dialogs(c);
        Map<String,String> vars=new HashMap<>(Map.of("nonce","abcdef012345678901234567","player","Alice","code","ABCDEFGHJK","bot","ExampleBot","seconds","180","ip","127.0.0.1"));
        ProtocolVersion v=ProtocolVersion.getProtocolVersion(774);
        for(String screen:List.of("register","telegram","password")){
            ByteBuf b=Unpooled.buffer();
            try{
                dialogs.packet(screen,vars,v).encode(b,ProtocolUtils.Direction.CLIENTBOUND,v);
                check(ProtocolUtils.readVarInt(b)==0,"inline dialog packet");
                CompoundBinaryTag nbt=(CompoundBinaryTag)ProtocolUtils.readBinaryTag(b,v,BinaryTagIO.reader());
                check(nbt.getString("type").equals("minecraft:multi_action"),"native dialog structure");
                check(!nbt.getBoolean("can_close_with_escape"),"blocking dialog");
                check(nbt.getString("after_action").equals("none"),"cancel URL returns to original dialog instead of waiting for server");
                check(nbt.get("pause") instanceof ByteBinaryTag && !nbt.getBoolean("pause",true),"Mojang requires explicit pause=false with after_action=none");
            }finally{b.release();}
        }
        // Independent wire fixture: VarInt length + anonymous NBT, as sent by Mojang clients.
        byte[] fixture=java.util.HexFormat.of().parseHex("1a0a08000870617373776f7264000b68656c6c6f20776f726c6400");
        ByteBuf b=Unpooled.buffer();
        try{
            ProtocolUtils.writeString(b,"artificalauth:password/abcdef012345678901234567");b.writeBytes(fixture);
            int index=b.readerIndex();
            Dialogs.Reply reply=Dialogs.reply(b,v);
            check(reply.password().equals("hello world")&&reply.action().equals("password"),"real 1.21.11 length-prefixed password payload");
            check(b.readerIndex()==index,"reply parser preserves original buffer ownership");
        }finally{b.release();}
        for(String action:List.of("refresh","exit"))for(byte[] payload:List.of(new byte[]{1,0},new byte[]{2,10,0})){
            b=Unpooled.buffer();
            try{ProtocolUtils.writeString(b,"artificalauth:"+action+"/abcdef012345678901234567");b.writeBytes(payload);
                check(Dialogs.reply(b,v).action().equals(action),"real empty "+action+" button payload");
            }finally{b.release();}
        }
        for(byte[] malformed:List.of(new byte[]{0},new byte[]{2,0},new byte[]{1,0,0},new byte[]{2,1,0},new byte[]{1,10})){
            b=Unpooled.buffer();boolean rejected=false;
            try{ProtocolUtils.writeString(b,"artificalauth:refresh/abcdef012345678901234567");b.writeBytes(malformed);
                try{Dialogs.reply(b,v);}catch(RuntimeException expected){rejected=true;}
                check(rejected,"invalid length/type/truncated reply rejected");
            }finally{b.release();}
        }
        Path reloadConfig=Files.createTempDirectory("artifical-config-reload-").resolve("config.yml");
        String original=Files.readString(config);Files.writeString(reloadConfig,original);
        Config live=new Config(reloadConfig),snapshot=live.snapshot();
        Files.writeString(reloadConfig,original.replace("session-hours: 5","session-hours: 2"));live.apply(new Config(reloadConfig));
        check(live.i("security.session-hours")==2,"atomic config reload updates existing references");
        live.apply(snapshot);check(live.i("security.session-hours")==5,"config snapshot rollback");
        Files.writeString(reloadConfig,original.replace("session-hours: 5","session-hours: -1"));
        boolean invalidReload=false;try{live.apply(new Config(reloadConfig));}catch(IllegalArgumentException expected){invalidReload=true;}
        check(invalidReload&&live.i("security.session-hours")==5,"invalid reload retains previous config");
        Path invalid=Files.createTempDirectory("artifical-auth-corrupt-").resolve("accounts.json");Files.writeString(invalid,"broken");
        boolean refused=false;try(Store s=new Store(invalid)){}catch(IOException expected){refused=true;}
        check(refused&&Files.readString(invalid).equals("broken"),"corrupt database never reset");
        System.out.println("PASS: "+checks+" authentication, persistence and dialog protocol assertions.");
    }
}

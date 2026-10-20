package ru.artificalsmp.auth;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.nio.ByteBuffer;
import java.io.*;
import java.util.*;

public final class Store implements AutoCloseable {
    public static final class Account {
        public String name,hash,ip="";
        public long telegram,lastSeen,revision;
        public boolean twoFactor=true,locked=false,delivered=false;
        public Account copy(){return JSON.fromJson(JSON.toJson(this),Account.class);}
    }
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path path;
    private final FileChannel lockFile;
    private final FileLock lock;
    private Map<String,Account> accounts=new LinkedHashMap<>();
    public Store(Path path) throws IOException {
        this.path=path;Files.createDirectories(path.getParent());
        lockFile=FileChannel.open(path.resolveSibling("accounts.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        lock=lockFile.tryLock();
        if(lock==null) throw new IOException("Account database already in use");
        if(Files.exists(path)) {
            try {
                JsonObject root=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if(root.get("schema").getAsInt()!=1) throw new IOException("Unsupported database schema");
                for(var e:root.getAsJsonObject("accounts").entrySet()) accounts.put(e.getKey(),JSON.fromJson(e.getValue(),Account.class));
                Set<Long> owners=new HashSet<>();
                for(var e:accounts.entrySet()) {
                    Account a=e.getValue();
                    if(!e.getKey().equals(key(a.name)) || a.hash==null || a.telegram<=0 || !a.locked && !owners.add(a.telegram))
                        throw new IOException("Invalid account database");
                }
            }catch(RuntimeException e){throw new IOException("Invalid account database; automatic reset is forbidden",e);}
        }
    }
    public static String key(String name){return name.toLowerCase(Locale.ROOT);}
    public synchronized Account get(String name){Account a=accounts.get(key(name));return a==null?null:a.copy();}
    public synchronized Account owner(long telegram){
        return accounts.values().stream().filter(a->a.telegram==telegram && !a.locked).findFirst().map(Account::copy).orElse(null);
    }
    public synchronized boolean hasTombstone(long telegram){return accounts.values().stream().anyMatch(a->a.telegram==telegram && a.locked);}
    public synchronized void save(Account a) throws IOException {
        Account owner=owner(a.telegram);
        if(!a.locked && owner!=null && !key(owner.name).equals(key(a.name))) throw new IOException("Telegram already linked");
        Map<String,Account> next=new LinkedHashMap<>(accounts);next.put(key(a.name),a.copy());commit(next);
    }
    public synchronized void releaseName(String name) throws IOException {
        Account a=get(name);
        if(a==null || !a.locked) throw new IOException("Name is not locked");
        Map<String,Account> next=new LinkedHashMap<>(accounts);next.remove(key(name));commit(next);
    }
    private void commit(Map<String,Account> next) throws IOException {
        JsonObject root=new JsonObject();root.addProperty("schema",1);root.add("accounts",JSON.toJsonTree(next));
        byte[] bytes=JSON.toJson(root).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path tmp=path.resolveSibling("accounts.tmp");
        try(FileChannel channel=FileChannel.open(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
            ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true);
        }
        Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        accounts=next;
    }
    public synchronized void close() throws IOException {lock.release();lockFile.close();}
}

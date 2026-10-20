package ru.artificalsmp.auth;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Configuration is deliberately explicit; missing messages fail startup. */
public final class Config {
    private final Map<String,Object> root;
    public Config(Path path) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            try (InputStream in = Config.class.getResourceAsStream("/config.yml")) {
                if (in == null) throw new IOException("Missing default config");
                Files.copy(in, path);
            }
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (Reader r = Files.newBufferedReader(path)) {
            root = new Yaml(new SafeConstructor(options)).load(r);
        }
        if (root == null) throw new IllegalArgumentException("Empty config");
        range("security.password-length",8,64);
        range("security.pbkdf2-iterations",100000,2000000);
        range("security.code-length",8,32);
        range("security.auth-timeout-seconds",30,1800);
        range("security.session-hours",0,168);
        range("security.max-password-attempts",1,20);
        if (!s("security.password-alphabet").matches(".*[A-Za-z].*")
            || !s("security.password-alphabet").matches(".*[^A-Za-z0-9].*"))
            throw new IllegalArgumentException("Password alphabet must contain letters and symbols");
        if (s("commands.login").equalsIgnoreCase(s("commands.admin")))
            throw new IllegalArgumentException("Command names must differ");
        if (!s("commands.login").matches("[a-z0-9_-]+") || !s("commands.admin").matches("[a-z0-9_-]+"))
            throw new IllegalArgumentException("Invalid command name");
        for (String key : List.of("start","menu","kick","recover","unlink","twofactor"))
            if (!s("bot.commands."+key).matches("[a-z0-9_]{1,32}"))
                throw new IllegalArgumentException("Invalid bot command: "+key);
    }
    private void range(String key,int min,int max) {
        int n=i(key); if(n<min || n>max) throw new IllegalArgumentException("Invalid "+key);
    }
    public Object get(String path) {
        Object value=root;
        for(String part:path.split("\\.")) {
            if(!(value instanceof Map<?,?> map) || !map.containsKey(part))
                throw new IllegalArgumentException("Missing config key: "+path);
            value=map.get(part);
        }
        return value;
    }
    public String s(String path){return String.valueOf(get(path));}
    public int i(String path){return ((Number)get(path)).intValue();}
    public long l(String path){return ((Number)get(path)).longValue();}
    public boolean b(String path){return (Boolean)get(path);}
    public String text(String key, Map<String,String> values) {
        String result=s("messages."+key);
        for(var e:values.entrySet()) result=result.replace("{"+e.getKey()+"}",e.getValue());
        return result;
    }
    public String token() {
        String env=System.getenv(s("bot.token-env"));
        return env!=null && !env.isBlank()?env:s("bot.token");
    }
}

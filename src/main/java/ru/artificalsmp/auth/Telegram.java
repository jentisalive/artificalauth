package ru.artificalsmp.auth;
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class Telegram implements Engine.Bot,AutoCloseable {
    private final Config config;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private volatile String token;
    private volatile String apiBase;
    private final Consumer<JsonObject> updates;
    private final Consumer<String> warning;
    private volatile boolean running;
    private Thread thread;
    private long offset;
    public Telegram(Config c,Consumer<JsonObject> updates,Consumer<String> warning){
        config=c;token=c.token();this.updates=updates;this.warning=warning;
        String configured;
        try{configured=c.s("bot.api-base-url");}catch(IllegalArgumentException missing){configured="https://api.telegram.org";}
        apiBase=configured;
        URI base=URI.create(apiBase);
        if(!"https".equals(base.getScheme()) && !("http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost()))) throw new IllegalArgumentException("Telegram endpoint requires HTTPS or local testing");
    }
    public void restart() throws Exception {
        close();
        Thread previous=thread;
        if(previous!=null){previous.join(5000);if(previous.isAlive())throw new IllegalStateException("Telegram poller did not stop");}
        token=config.token();apiBase=config.s("bot.api-base-url");
        URI base=URI.create(apiBase);
        if(!"https".equals(base.getScheme()) && !("http".equals(base.getScheme())&&"127.0.0.1".equals(base.getHost())))
            throw new IllegalArgumentException("Invalid Telegram endpoint");
        if(config.b("enabled")&&!token.isBlank())start();
    }
    public JsonObject call(String method,JsonObject body,int timeout) throws Exception {
        // No URL or exception text containing the bot token is logged.
        HttpRequest request=HttpRequest.newBuilder(URI.create(apiBase+"/bot"+token+"/"+method))
            .timeout(Duration.ofSeconds(timeout)).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
        JsonObject result=JsonParser.parseString(response.body()).getAsJsonObject();
        if(response.statusCode()!=200 || !result.has("ok") || !result.get("ok").getAsBoolean())
            throw new IllegalStateException("Telegram API request failed (HTTP "+response.statusCode()+")");
        return result;
    }
    public void start() throws Exception {
        JsonObject me=call("getMe",new JsonObject(),12).getAsJsonObject("result");
        if(!me.get("username").getAsString().equalsIgnoreCase(config.s("bot.username")))
            throw new IllegalStateException("bot.username does not match getMe");
        configureCommands();
        // Old callbacks are still harmless: all action nonces are memory-only and expire.
        running=true;thread=new Thread(this::poll,"ArtificalAuth-Telegram");thread.setDaemon(true);thread.start();
    }
    public boolean isRunning(){return running;}
    public void configureCommands() throws Exception {
        JsonObject commands=new JsonObject();JsonArray list=new JsonArray();
        for(String key:List.of("start","menu","kick","recover","unlink","twofactor")){
            JsonObject item=new JsonObject();item.addProperty("command",config.s("bot.commands."+key));
            item.addProperty("description",config.s("messages.bot-description-"+key));list.add(item);
        }
        commands.add("commands",list);call("setMyCommands",commands,12);
    }
    private void poll(){
        while(running){
            try {
                JsonObject request=new JsonObject();request.addProperty("offset",offset);request.addProperty("timeout",25);
                request.add("allowed_updates",new Gson().toJsonTree(List.of("message","callback_query")));
                JsonArray result=call("getUpdates",request,35).getAsJsonArray("result");
                for(JsonElement el:result){
                    JsonObject update=el.getAsJsonObject();
                    updates.accept(update); // Acceptance must succeed before acknowledging the update.
                    offset=Math.max(offset,update.get("update_id").getAsLong()+1);
                }
            }catch(Exception e){
                if(running)warning.accept("Telegram недоступен или очередь заполнена; повтор через 5 секунд.");
                try{Thread.sleep(5000);}catch(InterruptedException stop){Thread.currentThread().interrupt();return;}
            }
        }
    }
    public boolean send(long chat,String text,List<List<Engine.Button>> buttons){
        try{
            JsonObject request=new JsonObject();request.addProperty("chat_id",chat);request.addProperty("text",text);
            request.addProperty("parse_mode","HTML");
            if(!buttons.isEmpty()){
                JsonArray keyboard=new JsonArray();
                for(var row:buttons){JsonArray r=new JsonArray();for(var button:row){
                    JsonObject b=new JsonObject();b.addProperty("text",button.label());b.addProperty("callback_data",button.data());r.add(b);
                }keyboard.add(r);}
                JsonObject markup=new JsonObject();markup.add("inline_keyboard",keyboard);request.add("reply_markup",markup);
            }
            call("sendMessage",request,12);return true;
        }catch(Exception e){warning.accept("Не удалось доставить сообщение Telegram.");return false;}
    }
    public void answer(String id,String text){
        try{JsonObject body=new JsonObject();body.addProperty("callback_query_id",id);body.addProperty("text",text);call("answerCallbackQuery",body,8);}
        catch(Exception ignored){}
    }
    public void close(){running=false;if(thread!=null)thread.interrupt();}
}

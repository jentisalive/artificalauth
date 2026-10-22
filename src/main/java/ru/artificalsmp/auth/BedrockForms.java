package ru.artificalsmp.auth;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.geysermc.cumulus.form.*;

/** Native forms, with per-view replay guards and bounded, cancellable-by-generation retries. */
public final class BedrockForms implements BedrockUi {
    public interface Transport {
        boolean isBedrock(UUID id);
        boolean send(UUID id,Form form);
        void close(UUID id);
    }
    public interface Dispatcher {
        void enqueue(Engine.Client client,Runnable task);
        void later(Runnable task,int milliseconds);
        void reply(Engine.Client client,Reply reply);
        boolean active(Engine.Client client,String nonce,String screen);
    }
    private static final class View {
        final Engine.Client client;final String screen,nonce;final Map<String,String> values;
        final AtomicBoolean answered=new AtomicBoolean();
        View(Engine.Client client,String screen,Map<String,String> values){
            this.client=client;this.screen=screen;this.values=Map.copyOf(values);nonce=values.get("nonce");
        }
    }
    private final Config c;private final Transport transport;private final Dispatcher dispatcher;
    private final Map<UUID,View> views=new ConcurrentHashMap<>();
    public BedrockForms(Config c,Transport transport,Dispatcher dispatcher){this.c=c;this.transport=transport;this.dispatcher=dispatcher;}
    public boolean isBedrock(UUID id){return transport.isBedrock(id);}
    public void show(Engine.Client client,String screen,Map<String,String> values){
        if(!List.of("register","telegram","password").contains(screen)||values.get("nonce")==null)
            throw new IllegalArgumentException("Invalid authentication view");
        View view=new View(client,screen,values);
        // Replace FIRST: closing the previous form can synchronously invoke its callbacks.
        View previous=views.put(client.id(),view);
        if(previous!=null)transport.close(client.id());
        dispatcher.later(()->dispatcher.enqueue(client,()->send(view,1)),c.i("bedrock.send-delay-ms"));
    }
    private boolean active(View view){return views.get(view.client.id())==view&&view.client.connected()&&!view.answered.get()
        &&dispatcher.active(view.client,view.nonce,view.screen);}
    private void send(View view,int attempt){
        if(!active(view))return;
        boolean sent;
        try{sent=transport.send(view.client.id(),form(view));}catch(RuntimeException e){sent=false;}
        if(sent)return;
        if(attempt<c.i("bedrock.max-send-attempts"))
            dispatcher.later(()->dispatcher.enqueue(view.client,()->send(view,attempt+1)),c.i("bedrock.retry-delay-ms"));
        else respond(view,"unavailable","");
    }
    private Form form(View view){
        String title=plain(c.s("messages.dialog-title"));
        String body=plain(c.text("dialog-"+view.screen,view.values));
        if(view.values.containsKey("error"))body=plain(view.values.get("error"))+"\n\n"+body;
        if(view.screen.equals("password")){
            return CustomForm.builder().title(title)
                .label(body+"\n\n"+plain(c.s("messages.bedrock-password-warning")))
                .input(plain(c.s("messages.password-label")),plain(c.s("messages.bedrock-password-placeholder")))
                .validResultHandler(response->{
                    try{
                        String password=response.asInput();
                        respond(view,password!=null&&password.length()<=128?"password":"invalid",password==null?"":password);
                    }catch(RuntimeException malformed){respond(view,"invalid","");}
                })
                .closedResultHandler(()->respond(view,"closed",""))
                .invalidResultHandler(()->respond(view,"invalid",""))
                .build();
        }
        return SimpleForm.builder().title(title).content(body)
            .button(plain(c.s("messages.dialog-refresh")))
            .button(plain(c.s("messages.dialog-exit")))
            .validResultHandler(response->respond(view,switch(response.clickedButtonId()){case 0->"refresh";case 1->"exit";default->"invalid";},""))
            .closedResultHandler(()->respond(view,"closed",""))
            .invalidResultHandler(()->respond(view,"invalid",""))
            .build();
    }
    private void respond(View view,String action,String password){
        // Check generation and consume exactly once INSIDE the auth state queue, not on Geyser's callback thread.
        dispatcher.enqueue(view.client,()->{
            if(!active(view)||!view.answered.compareAndSet(false,true))return;
            dispatcher.reply(view.client,new Reply(view.screen,view.nonce,action,password));
        });
    }
    public void close(Engine.Client client){
        View old=views.get(client.id());
        // A delayed disconnect belonging to an old player object must not close a new connection's form.
        if(old!=null&&old.client==client&&views.remove(client.id(),old))transport.close(client.id());
    }
    public void closeAll(){for(View view:List.copyOf(views.values()))close(view.client);}
    static String plain(String text){
        return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(text));
    }
}

package ru.artificalsmp.auth;

import java.nio.file.*;
import java.util.*;
import org.geysermc.cumulus.form.*;
import org.geysermc.cumulus.form.impl.FormDefinitions;
import org.geysermc.cumulus.form.util.FormCodec;

/** Real installed Cumulus codecs with a deterministic transport/state queue; no live account or token. */
public final class BedrockFormsTests {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static class Player implements Engine.Client {
        final UUID id; boolean online=true,allowed;String nonce="SessionNonce0123456789",screen="register";
        Player(){this(UUID.randomUUID());} Player(UUID id){this.id=id;}
        public UUID id(){return id;}public String name(){return "BedrockTest";}public String ip(){return "127.0.0.1";}
        public boolean connected(){return online;}public void show(String s,Map<String,String> vars){}
        public void kick(String message){online=false;}public void allow(){allowed=true;}
        Map<String,String> values(){return Map.of("nonce",nonce,"player",name(),"code","ABCDEFGHJK","bot","ExampleBot","seconds","180","ip",ip(),"login-command","auth");}
    }
    static class Fixture implements BedrockForms.Transport,BedrockForms.Dispatcher {
        final BedrockForms ui; final Deque<Runnable> state=new ArrayDeque<>(),scheduled=new ArrayDeque<>();
        final List<BedrockUi.Reply> replies=new ArrayList<>();final Map<UUID,Form> forms=new HashMap<>();
        boolean sends=true;int attempts,closes;
        Fixture(Config c){ui=new BedrockForms(c,this,this);}
        public boolean isBedrock(UUID id){return true;}
        public boolean send(UUID id,Form form){attempts++;if(sends)forms.put(id,form);return sends;}
        public void close(UUID id){closes++;Form old=forms.remove(id);if(old!=null)answer(old,"");}
        public void enqueue(Engine.Client client,Runnable task){state.add(task);}
        public void later(Runnable task,int delay){scheduled.add(task);}
        public void reply(Engine.Client client,BedrockUi.Reply reply){replies.add(reply);}
        public boolean active(Engine.Client client,String nonce,String screen){
            Player p=(Player)client;return !p.allowed&&p.nonce.equals(nonce)&&p.screen.equals(screen);
        }
        void drain(){int limit=1000;while(!state.isEmpty()){if(--limit==0)throw new AssertionError("Unbounded state queue");state.remove().run();}}
        void advance(){int count=scheduled.size();for(int i=0;i<count;i++)scheduled.remove().run();drain();}
        Form show(Player p,String screen){p.screen=screen;ui.show(p,screen,p.values());advance();return forms.get(p.id());}
        BedrockUi.Reply last(){return replies.get(replies.size()-1);}
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static void answer(Form form,String payload){
        try{FormDefinitions.instance().definitionFor(form).handleFormResponse(form,payload);}
        catch(Exception e){throw new AssertionError("Cumulus response processing failed (payload redacted)",e);}
    }
    static String json(Form form){FormCodec<Form,?> codec=FormDefinitions.instance().codecFor(form);return codec.jsonData(form);}
    static final class FlowPlayer extends Player {
        final BedrockForms ui;
        FlowPlayer(BedrockForms ui){this.ui=ui;}
        @Override public void show(String screen,Map<String,String> values){this.screen=screen;nonce=values.get("nonce");ui.show(this,screen,values);}
        @Override public void allow(){allowed=true;ui.close(this);}
        @Override public void kick(String message){ui.close(this);online=false;}
    }
    static void engineFlow(Path template)throws Exception {
        try(AuthTests.Fixture auth=new AuthTests.Fixture(template)){
            Fixture flow=new Fixture(auth.c){
                @Override public boolean active(Engine.Client client,String nonce,String screen){return auth.engine.acceptsReply(client,nonce,screen);}
                @Override public void reply(Engine.Client client,BedrockUi.Reply reply){
                    super.reply(client,reply);
                    try{
                        switch(reply.action()){
                            case "password"->auth.engine.password(client,reply.nonce(),reply.password());
                            case "refresh"->auth.engine.refresh(client);
                            case "exit","invalid","unavailable"->client.kick("rejected");
                        }
                    }catch(Exception e){throw new AssertionError("Auth form flow failed",e);}
                }
            };
            FlowPlayer registered=new FlowPlayer(flow.ui);auth.engine.begin(registered);flow.advance();
            String code=auth.engine.session(registered).code;Form initial=flow.forms.get(registered.id());
            answer(initial,"0");flow.drain();flow.advance();
            check(code.equals(auth.engine.session(registered).code),"Bedrock refresh retains registration code");
            check(!registered.allowed,"refresh never authorizes registration");
            Form refreshed=flow.forms.get(registered.id());auth.engine.message(777,code);flow.drain();
            check(registered.allowed&&auth.engine.allowed(registered),"native registration binds through existing Telegram engine");
            check(!flow.forms.containsKey(registered.id()),"successful registration closes native UI");
            answer(refreshed,"1");flow.drain();check(registered.online,"late exit callback after success ignored");
            String password=auth.bot.password();registered.online=false;auth.engine.disconnect(registered);
            auth.engine.message(777,"/twofactor");auth.engine.callback(777,auth.bot.button(auth.c.s("messages.button-confirm")),"test");
            check(!auth.store.get(registered.name()).twoFactor,"Telegram can select password-only mode for native client");
            FlowPlayer login=new FlowPlayer(flow.ui);auth.engine.begin(login);flow.advance();
            check(flow.forms.get(login.id()) instanceof CustomForm,"existing account receives native password form");
            answer(flow.forms.get(login.id()),"");flow.drain();check(!auth.engine.allowed(login),"closing password form leaves backend blocked");
            auth.engine.refresh(login);flow.advance();Form attempt=flow.forms.get(login.id());
            answer(attempt,"[null,\"wrong-password\"]");flow.drain();flow.advance();
            check(!auth.engine.allowed(login)&&auth.engine.session(login).failures==1,"native wrong password uses engine attempt counter");
            check(json(flow.forms.get(login.id())).contains("Неверный пароль"),"native failed password form includes configured error");
            auth.time.addAndGet(1500);
            answer(flow.forms.get(login.id()),"[null,\""+password+"\"]");flow.drain();
            check(auth.engine.allowed(login)&&login.allowed,"native correct password authorizes via engine");
            check(!flow.forms.containsKey(login.id()),"native password success closes form without reopen");
            check(flow.scheduled.isEmpty(),"successful native flow leaves no scheduled reopen");
            flow.ui.closeAll();
        }
    }
    public static void main(String[] args)throws Exception {
        Path template=Path.of(args[0]).resolve("src/main/resources/config.yml");Config c=new Config(template);
        Fixture f=new Fixture(c);Player p=new Player();
        Form register=f.show(p,"register");
        check(register instanceof SimpleForm,"registration uses native SimpleForm");
        check(((SimpleForm)register).buttons().size()==2,"registration offers refresh and exit");
        check(((SimpleForm)register).content().contains("ABCDEFGHJK"),"registration displays the current code");
        check(!json(register).contains("<gray>"),"MiniMessage converted to plain form text");
        answer(register,"0");check(f.replies.isEmpty(),"callback does not mutate auth state on Geyser thread");
        f.drain();check(f.last().action().equals("refresh"),"refresh response decoded");
        answer(register,"1");f.drain();check(f.replies.size()==1,"form response consumed only once");

        Form telegram=f.show(p,"telegram");check(telegram instanceof SimpleForm,"Telegram stage uses native SimpleForm");
        check(((SimpleForm)telegram).content().contains("180"),"Telegram form shows remaining auth time");
        answer(telegram,"1");f.drain();check(f.last().action().equals("exit"),"exit decoded separately from close");

        Form password=f.show(p,"password");check(password instanceof CustomForm,"password uses native CustomForm");
        check(((CustomForm)password).content().size()==2,"password warning label plus one input");
        check(json(password).contains("Bedrock"),"password visibility warning displayed");
        String secret="SamplePassword!42";
        answer(password,"[null,\""+secret+"\"]");f.drain();
        check(f.last().action().equals("password")&&f.last().password().equals(secret),"actual Cumulus parser skips label and reads password");
        check(f.last().nonce().equals(p.nonce)&&f.last().screen().equals("password"),"callback carries original session and stage");
        check(!f.last().toString().contains(secret)&&!f.last().toString().contains(p.nonce),"reply diagnostics redact secrets");

        Form old=f.show(p,"password");int before=f.replies.size();
        Form current=f.show(p,"password");f.drain();
        check(f.replies.size()==before,"programmatic replacement close ignored");
        answer(old,"[null,\"stale\"]");f.drain();check(f.replies.size()==before,"response to replaced same-stage form ignored");
        answer(current,"[null,\"current\"]");f.drain();check(f.last().password().equals("current"),"replacement form remains usable");

        current=f.show(p,"register");before=f.replies.size();f.ui.close(p);f.drain();
        check(f.replies.size()==before,"explicit programmatic close does not produce user-close response");
        current=f.show(p,"register");answer(current,"");f.drain();
        check(f.last().action().equals("closed"),"user closing form reported without authenticating");
        check(f.scheduled.isEmpty(),"closing form does not create reopen loop");

        current=f.show(p,"register");before=f.replies.size();p.allowed=true;answer(current,"0");f.drain();
        check(f.replies.size()==before,"late response after authorization ignored");p.allowed=false;
        current=f.show(p,"register");before=f.replies.size();p.online=false;answer(current,"1");f.drain();
        check(f.replies.size()==before,"response after game disconnect ignored");p.online=true;
        current=f.show(p,"register");before=f.replies.size();p.nonce="NewSessionNonce";answer(current,"0");f.drain();
        check(f.replies.size()==before,"old session nonce rejected");
        current=f.show(p,"password");before=f.replies.size();p.screen="telegram";answer(current,"[null,\"late\"]");f.drain();
        check(f.replies.size()==before,"old stage rejected before dispatch");

        Fixture retry=new Fixture(c);Player r=new Player();retry.sends=false;retry.show(r,"register");
        for(int i=0;i<20;i++)retry.advance();
        check(retry.attempts==c.i("bedrock.max-send-attempts"),"delivery retries bounded");
        check(retry.replies.size()==1&&retry.last().action().equals("unavailable"),"exhausted delivery fails closed");
        Fixture canceled=new Fixture(c);Player cp=new Player();canceled.sends=false;canceled.show(cp,"register");
        canceled.ui.close(cp);for(int i=0;i<20;i++)canceled.advance();
        check(canceled.attempts==1&&canceled.replies.isEmpty(),"close cancels delayed retry by generation");

        Fixture identity=new Fixture(c);Player original=new Player();Form previous=identity.show(original,"register");
        Player reconnect=new Player(original.id());Form latest=identity.show(reconnect,"register");
        int count=identity.closes;identity.ui.close(original);identity.drain();
        check(identity.closes==count,"stale player object cannot close new player's UI");
        answer(previous,"0");identity.drain();check(identity.replies.isEmpty(),"previous connection cannot control reconnect");
        answer(latest,"0");identity.drain();check(identity.last().action().equals("refresh"),"new player object can respond");

        for(String raw:List.of("-1","2","999")){
            Fixture malformed=new Fixture(c);Player mp=new Player();Form form=malformed.show(mp,"register");
            answer(form,raw);malformed.drain();check(malformed.last().action().equals("invalid"),"invalid button index rejected");
        }
        for(String raw:List.of("[null]","[null,true]","[null,\""+"a".repeat(129)+"\"]")){
            Fixture malformed=new Fixture(c);Player mp=new Player();Form form=malformed.show(mp,"password");
            answer(form,raw);malformed.drain();check(malformed.last().action().equals("invalid"),"invalid password response rejected");
        }

        // An unchanged 0.1.3 config remains loadable. Defaults are merged in memory, not written over secrets.
        Path legacy=Files.createTempDirectory(Path.of(args[0]).resolve("build"),"legacy-bedrock-config-").resolve("config.yml");
        String text=Files.readString(template).replaceAll("(?m)^bedrock:\\R(?:[ \\t]+.*\\R)*","")
                .replaceAll("(?m)^  bedrock-[^\\r\\n]*(?:\\R|$)","");
        Files.writeString(legacy,text);Config oldConfig=new Config(legacy);
        check(oldConfig.b("bedrock.enabled")&&oldConfig.i("bedrock.max-send-attempts")==5,"legacy config receives safe in-memory defaults");
        check(Files.readString(legacy).equals(text),"loading legacy config does not rewrite it");
        Files.writeString(legacy,Files.readString(template).replace("max-send-attempts: 5","max-send-attempts: 0"));
        boolean rejected=false;try{new Config(legacy);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"invalid retry count rejected");
        check(BedrockUi.NONE.isBedrock(p.id())==false,"Java-only boundary does not require Geyser");
        engineFlow(template);
        System.out.println("PASS: "+checks+" native Bedrock form, Cumulus codec, replay, queue and config assertions.");
    }
}

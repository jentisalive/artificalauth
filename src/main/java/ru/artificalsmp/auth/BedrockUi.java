package ru.artificalsmp.auth;
import java.util.*;

/** Optional UI boundary. This type deliberately has no Geyser/Cumulus references. */
public interface BedrockUi {
    record Reply(String screen,String nonce,String action,String password) {
        @Override public String toString(){return "BedrockReply[screen="+screen+", action="+action+", secrets=redacted]";}
    }
    boolean isBedrock(UUID id);
    void show(Engine.Client client,String screen,Map<String,String> values);
    void close(Engine.Client client);
    void closeAll();
    BedrockUi NONE=new BedrockUi(){
        public boolean isBedrock(UUID id){return false;}
        public void show(Engine.Client client,String screen,Map<String,String> values){throw new IllegalStateException("Bedrock UI unavailable");}
        public void close(Engine.Client client){}
        public void closeAll(){}
    };
}

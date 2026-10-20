package ru.artificalsmp.auth;
import com.google.gson.*;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.*;
import com.velocitypowered.proxy.protocol.packet.*;
import io.netty.buffer.*;
import net.kyori.adventure.nbt.*;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import java.util.*;

/** Protocol adapter isolated here; compiled/tested against the supplied Velocity build. */
public final class Dialogs {
    private static final MiniMessage MM=MiniMessage.miniMessage();
    public record Reply(String action,String nonce,String password){}
    private final Config c;
    public Dialogs(Config c){this.c=c;}
    private BinaryTag text(String source){
        return tag(JsonParser.parseString(GsonComponentSerializer.gson().serialize(MM.deserialize(source))));
    }
    private static BinaryTag tag(JsonElement e){
        if(e.isJsonObject()){
            CompoundBinaryTag.Builder b=CompoundBinaryTag.builder();e.getAsJsonObject().entrySet().forEach(x->b.put(x.getKey(),tag(x.getValue())));return b.build();
        }
        if(e.isJsonArray()){
            ListBinaryTag.Builder<CompoundBinaryTag> b=ListBinaryTag.builder(BinaryTagTypes.COMPOUND); e.getAsJsonArray().forEach(x->{ BinaryTag t=tag(x); b.add(t instanceof CompoundBinaryTag compound ? compound : CompoundBinaryTag.builder().put("text",t).build()); });return b.build();
        }
        JsonPrimitive p=e.getAsJsonPrimitive();
        if(p.isBoolean())return ByteBinaryTag.byteBinaryTag((byte)(p.getAsBoolean()?1:0));
        if(p.isNumber())return IntBinaryTag.intBinaryTag(p.getAsInt());
        return StringBinaryTag.stringBinaryTag(p.getAsString());
    }
    public CompoundBinaryTag nbt(String screen,Map<String,String> vars){
        String body=c.text("dialog-"+screen,vars);
        if(vars.containsKey("error"))body=vars.get("error")+"\n\n"+body;
        ListBinaryTag.Builder<CompoundBinaryTag> bodies=ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        bodies.add(CompoundBinaryTag.builder().putString("type","minecraft:plain_message").put("contents",text(body)).putInt("width",c.i("dialog.body-width")).build());
        ListBinaryTag.Builder<CompoundBinaryTag> actions=ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        ListBinaryTag.Builder<CompoundBinaryTag> inputs=ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        if(screen.equals("password")){
            inputs.add(CompoundBinaryTag.builder().putString("type","minecraft:text").putString("key","password")
                .put("label",text(c.s("messages.password-label"))).putInt("max_length",128).putInt("width",c.i("dialog.input-width")).build());
            actions.add(button(c.s("messages.dialog-submit"),"password",vars.get("nonce")));
        }else{
            if(screen.equals("register")&&c.b("dialog.telegram-link")){
                CompoundBinaryTag action=CompoundBinaryTag.builder().putString("type","minecraft:open_url")
                    .putString("url","https://t.me/"+c.s("bot.username")+"?start="+vars.get("code")).build();
                actions.add(CompoundBinaryTag.builder().put("label",text(c.s("messages.dialog-open-bot"))).put("action",action).putInt("width",c.i("dialog.button-width")).build());
            }
            actions.add(button(c.s("messages.dialog-refresh"),"refresh",vars.get("nonce")));
        }
        actions.add(button(c.s("messages.dialog-exit"),"exit",vars.get("nonce")));
        return CompoundBinaryTag.builder().putString("type","minecraft:multi_action")
            .put("title",text(c.s("messages.dialog-title"))).putBoolean("can_close_with_escape",false)
            .putString("after_action","wait_for_response").putInt("columns",1)
            .put("body",bodies.build()).put("inputs",inputs.build()).put("actions",actions.build()).build();
    }
    private CompoundBinaryTag button(String label,String kind,String nonce){
        return CompoundBinaryTag.builder().put("label",text(label)).putInt("width",c.i("dialog.button-width"))
            .put("action",CompoundBinaryTag.builder().putString("type","minecraft:dynamic/custom")
                .putString("id","artificalauth:"+kind+"/"+nonce.toLowerCase(Locale.ROOT)).build()).build();
    }
    public DialogShowPacket packet(String screen,Map<String,String> vars,ProtocolVersion protocol){
        ByteBuf b=Unpooled.buffer();
        try {
            ProtocolUtils.writeVarInt(b,0);ProtocolUtils.writeBinaryTag(b,protocol,nbt(screen,vars));
            DialogShowPacket packet=new DialogShowPacket(StateRegistry.PLAY);
            packet.decode(b,ProtocolUtils.Direction.CLIENTBOUND,protocol);return packet;
        }finally{b.release();}
    }
    public static Reply reply(ByteBuf original,ProtocolVersion protocol){
        if(original.readableBytes()>4096)throw new IllegalArgumentException("Oversized dialog reply");
        ByteBuf b=original.duplicate();
        String id=ProtocolUtils.readString(b,256);
        if(!id.startsWith("artificalauth:"))return null;
        String[] parts=id.substring(14).split("/",2);
        if(parts.length!=2)return null;
        // 26.x introduced a bounded length prefix; 1.21.6-1.21.11 use Optional<NBT>.
        if(protocol.getProtocol()>=775){
            int len=ProtocolUtils.readVarInt(b);
            if(len<1||len!=b.readableBytes()||len>2048)throw new IllegalArgumentException("Invalid reply length");
            b=b.readSlice(len);
        }
        int marker=b.readUnsignedByte();
        CompoundBinaryTag data=CompoundBinaryTag.empty();
        if(marker!=0){
            if(marker!=1)b.readerIndex(b.readerIndex()-1);
            BinaryTag value=ProtocolUtils.readBinaryTag(b,protocol,BinaryTagIO.reader(4096));
            if(!(value instanceof CompoundBinaryTag compound))throw new IllegalArgumentException("Expected compound");
            data=compound;
        }
        if(b.isReadable())throw new IllegalArgumentException("Trailing dialog data");
        return new Reply(parts[0],parts[1],data.getString("password",""));
    }
}

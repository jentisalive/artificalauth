package ru.artificalsmp.auth;
import java.util.UUID;
import org.geysermc.cumulus.form.Form;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.floodgate.api.FloodgateApi;

/** Loaded only when Geyser is installed on this Velocity. No plugin-message/backend dependency. */
public final class GeyserForms implements BedrockForms.Transport {
    private final boolean floodgate;
    public GeyserForms(boolean floodgate){this.floodgate=floodgate;}
    public boolean isBedrock(UUID id){
        GeyserApi api=GeyserApi.api();
        if(api!=null&&api.isBedrockPlayer(id))return true;
        return floodgate&&FloodgateApi.getInstance()!=null&&FloodgateApi.getInstance().isFloodgatePlayer(id);
    }
    public boolean send(UUID id,Form form){
        GeyserApi api=GeyserApi.api();
        return api!=null&&api.sendForm(id,form);
    }
    public void close(UUID id){
        GeyserApi api=GeyserApi.api();
        if(api!=null){var connection=api.connectionByUuid(id);if(connection!=null&&connection.hasFormOpen())connection.closeForm();}
    }
}

package com.khronodragon.bluestone.emotes;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

import static com.khronodragon.bluestone.util.Strings.str;

public class FrankerFaceZEmoteProvider implements EmoteProvider {
    private JSONObject emotes = null;

    public FrankerFaceZEmoteProvider(OkHttpClient client) {
        client.newCall(new Request.Builder()
                .get()
                .url("https://api.frankerfacez.com/v1/emoticons?sort=count-desc&per_page=200&page=1")
                .build()).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                LogManager.getLogger(FrankerFaceZEmoteProvider.class).error("Failed to get data", e);
                emotes = new JSONObject();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                JSONObject data = new JSONObject(response.body().string());
                JSONArray rawEmotes = data.getJSONArray("emoticons");
                JSONObject tempEmotes = new JSONObject();

                for (Object emote: rawEmotes) {
                    JSONObject realEmote = (JSONObject) emote;
                    final String name = realEmote.getString("name");
                    realEmote.remove("name");
                    realEmote.remove("css");
                    realEmote.remove("margins");
                    realEmote.remove("public");
                    realEmote.remove("hidden");
                    realEmote.remove("modifier");
                    realEmote.remove("offset");

                    tempEmotes.put(name, realEmote);
                }
                emotes = tempEmotes;
                LogManager.getLogger(FrankerFaceZEmoteProvider.class).info("Data loaded.");
            }
        });
    }

    @Override
    public boolean hasEmote(String emote) {
        return emotes.has(emote);
    }

    @Override
    public boolean isLoaded() {
        return emotes != null;
    }

    @Override
    public String getUrl(String emote) {
        if (emotes.has(emote)) {
            return "https:" + emotes.getJSONObject(emote).getJSONObject("urls").getString("2");
        } else {
            return null;
        }
    }

    @Override
    public EmoteInfo getEmoteInfo(String emote) {
        if (emotes.has(emote)) {
            JSONObject emoteObj = emotes.getJSONObject(emote);
            return new EmoteInfo(emote, str(emoteObj.getInt("id")), null);
        } else {
            return null;
        }
    }
}

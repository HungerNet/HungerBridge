package com.hungerbridge.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public final class Json {

    public static final Gson GSON = new Gson();

    private Json() {
    }

    public static JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            o.add(kv[i].toString(), GSON.toJsonTree(kv[i + 1]));
        }
        return o;
    }

    public static String stringify(Object o) {
        return GSON.toJson(o);
    }
}

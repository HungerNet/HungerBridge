package com.hungerbridge.common.http;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Json;

public final class JsonResponse {
    private JsonResponse() {}

    public static JsonObject ok() {
        return Json.obj("ok", true);
    }

    public static JsonObject ok(Object payload) {
        return Json.obj("ok", true, "data", payload);
    }

    public static JsonObject error(String code, String message) {
        return Json.obj("ok", false, "error", code, "message", message);
    }

    public static JsonObject error(String code, String message, Object details) {
        JsonObject obj = Json.obj("ok", false, "error", code, "message", message);
        if (details != null) {
            obj.add("details", Json.GSON.toJsonTree(details));
        }
        return obj;
    }
}

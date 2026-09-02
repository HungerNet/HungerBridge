package com.hungerbridge.common.http;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Json;

public final class Response {
    public static JsonObject ok(Object payload) {
        return Json.obj("ok", true, "data", payload);
    }

    public static JsonObject ok() {
        return Json.obj("ok", true);
    }
}

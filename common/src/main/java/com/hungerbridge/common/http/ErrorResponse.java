package com.hungerbridge.common.http;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Json;

public final class ErrorResponse {
    public static JsonObject error(String code, String message) {
        return Json.obj("ok", false, "error", code, "message", message);
    }
}

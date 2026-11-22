package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.ApiHandler;
import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.log.BridgeLogger;
import com.hungerbridge.common.log.LogEntry;
import com.hungerbridge.common.log.MessageNormalizer;
import com.hungerbridge.common.security.Permissions;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * {@code POST /server/log} —
 * {@code {"message": "...", "level": "INFO", "thread": "...", "logger": "...", "normalize": false}}.
 *
 * <p>Console: {@code [HH:mm:ss] [thread/LEVEL]: [logger] message} (Paper:
 * {@code [HH:mm:ss LEVEL]: [logger] message}); stream {@code line}: the former on every
 * loader. Defaults: level {@code INFO}, thread and logger {@value Logger#NAME}.
 *
 * <p>Validation (400): {@code level} matches {@link BridgeLogger#LEVEL_NAME};
 * {@code thread} and {@code logger} are 1–{@value #MAX_NAME} characters without control
 * characters; {@code message} has no control characters other than tab and newline.
 */
public final class LogHandler extends ApiHandler {

    static final int MAX_NAME = 64;

    public LogHandler(Config config, Logger logger) {
        super(config, logger, "/server/log", "POST", Permissions.SERVER_LOG);
    }

    @Override
    protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
        if (!body.has("message")) throw new HttpUtil.BadRequestException("Missing field: message");
        String message = optString(body, "message", "");
        String level = optString(body, "level", "INFO").trim();
        String thread = name(body, "thread");
        String loggerName = name(body, "logger");
        boolean normalize = optBoolean(body, "normalize", false);

        if (!BridgeLogger.LEVEL_NAME.matcher(level).matches()) {
            throw new HttpUtil.BadRequestException("Field 'level' must match " + BridgeLogger.LEVEL_NAME.pattern());
        }
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\t') {
                throw new HttpUtil.BadRequestException("Field 'message' contains control characters");
            }
        }
        if (normalize) message = MessageNormalizer.normalize(message, config.getMinecraftVersion());
        try {
            BridgeLogger.level(level);
        } catch (IllegalArgumentException e) {
            throw new HttpUtil.BadRequestException(e.getMessage());
        }
        if (logger != null) {
            logger.log(new LogEntry(level, thread == null ? Logger.NAME : thread, loggerName == null ? Logger.NAME : loggerName, message));
        }
        HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "message", message));
    }

    private static String name(JsonObject body, String field) throws HttpUtil.BadRequestException {
        String value = optString(body, field, null);
        if (value == null) return null;
        if (value.isBlank() || value.length() > MAX_NAME || value.chars().anyMatch(Character::isISOControl)) {
            throw new HttpUtil.BadRequestException("Field '" + field + "' must be 1-" + MAX_NAME + " characters without control characters");
        }
        return value;
    }
}

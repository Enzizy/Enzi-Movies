package com.truelockmc.streambert;

import org.json.JSONException;
import org.json.JSONObject;

final class NativeCore {
    static {
        System.loadLibrary("streambert_android");
    }

    private NativeCore() {}

    private static native String call(String request);

    static JSONObject invoke(JSONObject request) throws JSONException {
        JSONObject reply = new JSONObject(call(request.toString()));
        if (reply.has("error")) {
            throw new IllegalArgumentException(reply.getString("error"));
        }
        return reply.getJSONObject("value");
    }
}

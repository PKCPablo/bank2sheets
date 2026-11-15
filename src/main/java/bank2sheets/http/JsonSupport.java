package bank2sheets.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Small Gson helpers shared by the HTTP-backed clients. */
public final class JsonSupport {

    private JsonSupport() {
    }

    /** Parses a response body as a JSON object. */
    public static JsonObject parseObject(String body) {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    /** Member text, or "" when the member is missing or JSON null. */
    public static String stringOrEmpty(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}

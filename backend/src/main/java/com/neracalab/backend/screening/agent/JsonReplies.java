package com.neracalab.backend.screening.agent;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lenient reading of the JSON object a model was asked for: code fences and text around the
 * object are ignored, unknown fields too.
 */
public final class JsonReplies {

    /** The reply is not a JSON object of the expected shape. */
    public static class InvalidReplyException extends RuntimeException {

        public InvalidReplyException(String message) {
            super(message);
        }
    }

    private final JsonMapper json;

    public JsonReplies(JsonMapper json) {
        this.json = json.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    public <T> T parse(String text, Class<T> type) {
        String object = extractObject(text);
        try {
            T value = json.readValue(object, type);
            if (value == null) {
                throw new InvalidReplyException("the reply is empty");
            }
            return value;
        } catch (JacksonException e) {
            throw new InvalidReplyException("the reply is not a valid JSON object of the requested shape: "
                    + e.getOriginalMessage());
        }
    }

    public JsonNode tree(String text) {
        try {
            return json.readTree(extractObject(text));
        } catch (JacksonException e) {
            throw new InvalidReplyException("the reply is not valid JSON: " + e.getOriginalMessage());
        }
    }

    public String write(Object value) {
        return json.writeValueAsString(value);
    }

    /** The outermost {...} of the text. */
    static String extractObject(String text) {
        if (text == null) {
            throw new InvalidReplyException("the reply is empty");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new InvalidReplyException("the reply contains no JSON object");
        }
        return text.substring(start, end + 1);
    }
}

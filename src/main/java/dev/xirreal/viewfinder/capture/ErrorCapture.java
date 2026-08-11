package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ErrorCapture {
    private static final int MAX_ERRORS = 100;
    private final List<ErrorEntry> errors = new CopyOnWriteArrayList<>();

    public void addError(String type, String filename, String message, String stackTrace) {
        errors.add(new ErrorEntry(type, filename, message, stackTrace, System.currentTimeMillis()));
        if (errors.size() > MAX_ERRORS) {
            errors.remove(0);
        }
    }

    public List<ErrorEntry> getErrors() {
        return Collections.unmodifiableList(new ArrayList<>(errors));
    }

    public void clearErrors() {
        errors.clear();
    }

    public JsonArray toJson() {
        JsonArray array = new JsonArray();
        for (ErrorEntry entry : errors) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", entry.type);
            obj.addProperty("filename", entry.filename);
            obj.addProperty("message", entry.message);
            obj.addProperty("stackTrace", entry.stackTrace);
            obj.addProperty("timestamp", entry.timestamp);
            JsonArray compilerMessages = new JsonArray();
            for (ShaderLogParser.Message parsed : ShaderLogParser.parse(entry.message)) {
                JsonObject message = new JsonObject();
                message.addProperty("severity", parsed.severity());
                message.addProperty("file", parsed.file());
                message.addProperty("line", parsed.line());
                message.addProperty("message", parsed.message());
                compilerMessages.add(message);
            }
            obj.add("compilerMessages", compilerMessages);
            array.add(obj);
        }
        return array;
    }

    public static class ErrorEntry {
        public final String type;
        public final String filename;
        public final String message;
        public final String stackTrace;
        public final long timestamp;

        public ErrorEntry(String type, String filename, String message, String stackTrace, long timestamp) {
            this.type = type;
            this.filename = filename;
            this.message = message;
            this.stackTrace = stackTrace;
            this.timestamp = timestamp;
        }
    }
}

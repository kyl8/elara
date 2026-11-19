package org.elaramc.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

public final class SettingsStore {
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path file;
    private ElaraSettings current = new ElaraSettings();
    private ElaraSettings.Palette palette = current.validate();

    public SettingsStore(Path directory) {
        file = directory.resolve("elara.json");
    }

    public ElaraSettings current() {
        return current;
    }

    public ElaraSettings.Palette palette() {
        return palette;
    }

    public ElaraSettings read() {
        try {
            if (!Files.exists(file)) {
                write(file, JSON.toJson(new ElaraSettings()));
            }
            ElaraSettings candidate = JSON.fromJson(readObject(file), ElaraSettings.class);
            candidate.validate();
            return candidate;
        } catch (IOException | JsonParseException exception) {
            throw new IllegalArgumentException("Não foi possível ler elara.json. Verifique o arquivo de ajustes.", exception);
        }
    }

    public void apply(ElaraSettings candidate) {
        palette = candidate.validate();
        current = candidate;
    }

    public void edit(Consumer<ElaraSettings> edit) {
        ElaraSettings candidate = JSON.fromJson(JSON.toJsonTree(current), ElaraSettings.class);
        edit.accept(candidate);
        candidate.validate();
        try {
            write(file, JSON.toJson(candidate));
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível salvar elara.json.", exception);
        }
        apply(candidate);
    }

    public static JsonObject readObject(Path file) throws IOException {
        try (var reader = new JsonReader(new StringReader(Files.readString(file)))) {
            var data = JsonParser.parseReader(reader);
            if (!data.isJsonObject() || reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) {
                throw new JsonParseException("Expected one JSON object in " + file.getFileName());
            }
            return data.getAsJsonObject();
        }
    }

    public static void write(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "elara-", ".tmp");
        try {
            Files.writeString(temporary, json + System.lineSeparator());
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

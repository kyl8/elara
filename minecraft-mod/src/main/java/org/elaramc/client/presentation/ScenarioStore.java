package org.elaramc.client.presentation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.elaramc.client.config.SettingsStore;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScenarioStore {
    private final Path file;
    private final Map<String, Scenario> bundled;
    private Map<String, Scenario> custom = Map.of();
    private Map<String, Scenario> available;
    private List<String> names;

    public ScenarioStore(Path directory) {
        file = directory.resolve("elara-scenarios.json");
        try (var stream = ScenarioStore.class.getResourceAsStream("/assets/elara/scenarios.json")) {
            if (stream == null) {
                throw new IllegalStateException("Bundled scenarios are missing.");
            }
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            bundled = parse(json.getAsJsonObject());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read bundled scenarios.", exception);
        }
        apply(Map.of());
    }

    public Map<String, Scenario> read() {
        if (!Files.exists(file)) {
            return Map.of();
        }
        try {
            return parse(SettingsStore.readObject(file));
        } catch (IOException | JsonParseException exception) {
            throw new IllegalArgumentException("Não foi possível ler elara-scenarios.json. Verifique o arquivo de cenários.", exception);
        }
    }

    public void apply(Map<String, Scenario> scenarios) {
        custom = scenarios;
        var merged = new LinkedHashMap<>(bundled);
        merged.putAll(custom);
        available = Collections.unmodifiableMap(merged);
        names = List.copyOf(available.keySet());
    }

    public List<String> names() {
        return names;
    }

    public void validateStartup(String name, Map<String, Scenario> scenarios) {
        if (!name.isEmpty() && !bundled.containsKey(name) && !scenarios.containsKey(name)) {
            throw new IllegalArgumentException("Cenário inicial desconhecido: " + name + ".");
        }
    }

    public Scenario get(String name) {
        Scenario scenario = available.get(name);
        if (scenario == null) {
            throw new IllegalArgumentException("Cenário desconhecido: " + name + ". Use /elara scenario list.");
        }
        return scenario;
    }

    public void save(String name, Scenario scenario) {
        scenario.validate(name);
        if (!custom.containsKey(name) && custom.size() == 64) {
            throw new IllegalStateException("O arquivo local aceita até 64 cenários.");
        }
        var updated = new LinkedHashMap<>(custom);
        updated.put(name, scenario);
        try {
            SettingsStore.write(file, SettingsStore.JSON.toJson(updated));
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível salvar os cenários.", exception);
        }
        apply(Collections.unmodifiableMap(updated));
    }

    private static Map<String, Scenario> parse(JsonObject root) {
        if (root.size() > 64) {
            throw new IllegalArgumentException("O arquivo de cenários aceita até 64 entradas.");
        }
        var parsed = new LinkedHashMap<String, Scenario>();
        for (var entry : root.entrySet()) {
            Scenario scenario = SettingsStore.JSON.fromJson(entry.getValue(), Scenario.class);
            if (scenario == null) {
                throw new IllegalArgumentException("O cenário " + entry.getKey() + " precisa ser um objeto JSON.");
            }
            scenario.validate(entry.getKey());
            parsed.put(entry.getKey(), scenario);
        }
        return Collections.unmodifiableMap(parsed);
    }
}

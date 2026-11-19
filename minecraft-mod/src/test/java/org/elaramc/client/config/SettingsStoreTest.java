package org.elaramc.client.config;

import org.elaramc.client.presentation.ScenarioStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettingsStoreTest {
    @TempDir
    Path directory;

    @Test
    void partialFilesUseDefaultsAndInvalidReloadLeavesActiveSettingsAvailable() throws Exception {
        Path file = directory.resolve("elara.json");
        Files.writeString(file, "{\"expansionsPerSecond\": 35, \"colors\": {\"pearl\": \"#FFFFFF\"}}");
        var settings = new SettingsStore(directory);
        settings.apply(settings.read());
        assertEquals(35, settings.current().expansionsPerSecond);
        assertEquals(0xffffffff, settings.palette().pearl());
        assertEquals(0xff14171e, settings.palette().obsidian());

        Files.writeString(file, "{\"expansionsPerSecond\": 0}");
        assertThrows(IllegalArgumentException.class, settings::read);
        assertEquals(35, settings.current().expansionsPerSecond);
    }

    @Test
    void customScenariosSurviveReloadAndCannotStartWithUnknownNames() {
        var original = new ScenarioStore(directory);
        var scene = original.get("level0-detour");
        original.save("lecture", scene);
        var reopened = new ScenarioStore(directory);
        var custom = reopened.read();
        reopened.validateStartup("lecture", custom);
        reopened.apply(custom);
        assertEquals(scene, reopened.get("lecture"));
        assertThrows(IllegalArgumentException.class, () -> reopened.validateStartup("missing", custom));
    }
}

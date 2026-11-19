package org.elaramc.client.config;

import org.elaramc.client.presentation.ScenarioStore;

import java.nio.file.Path;

public final class ValidateConfig {
    public static void main(String[] arguments) {
        Path directory = Path.of(arguments[0]).toAbsolutePath();
        var settings = new SettingsStore(directory).read();
        var store = new ScenarioStore(directory);
        var scenarios = store.read();
        store.validateStartup(settings.startupScenario, scenarios);
        store.apply(scenarios);
        System.out.println("Valid Elara configuration in " + directory);
        System.out.println("Scenarios " + String.join(", ", store.names()));
    }
}

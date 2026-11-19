package org.elaramc.client.config;

public enum PlayMode {
    STEP("Passo a passo"),
    ANIMATED("Animado"),
    INSTANT("Instantâneo");

    private final String label;

    PlayMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

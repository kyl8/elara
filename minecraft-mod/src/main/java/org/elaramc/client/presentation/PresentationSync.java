package org.elaramc.client.presentation;

import net.minecraft.client.MinecraftClient;
import org.elaramc.client.ElaraChat;
import org.elaramc.client.config.ElaraSettings;
import org.elaramc.client.config.SettingsStore;
import org.elaramc.client.search.WorldSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/** Optional IPv4 loopback controller, polled without blocking the client tick. */
public final class PresentationSync {
    private static final Logger LOGGER = LoggerFactory.getLogger("Elara");
    private static final String PROTOCOL = "ELARA1";
    private final SettingsStore settings;
    private final WorldSearch search;
    private final ScenarioManager scenarios;
    private final ByteBuffer read = ByteBuffer.allocate(256);
    private final PriorityQueue<Request> pending = new PriorityQueue<>(Comparator.comparingLong(Request::due)
        .thenComparing(Request::id));
    private final Map<UUID, String> completed = new LinkedHashMap<>();
    private DatagramChannel channel;
    private int boundPort;
    private int retryTicks;

    private record Request(UUID id, long due, String action, String value, InetSocketAddress peer) {}

    public PresentationSync(SettingsStore settings, WorldSearch search, ScenarioManager scenarios) {
        this.settings = settings;
        this.search = search;
        this.scenarios = scenarios;
    }

    public void tick(MinecraftClient client) {
        int port = settings.current().syncPort;
        try {
            if (port != boundPort) {
                if (retryTicks > 0) {
                    retryTicks--;
                    return;
                }
                bind(port);
            }
            if (channel == null) {
                return;
            }
            for (int index = 0; index < 8; index++) {
                if (!receive()) {
                    break;
                }
            }
            long now = System.currentTimeMillis();
            while (!pending.isEmpty() && pending.peek().due() <= now) {
                Request request = pending.remove();
                String response = execute(client, request);
                completed.put(request.id, response);
                if (completed.size() > 64) {
                    completed.remove(completed.keySet().iterator().next());
                }
                send(request.peer, response);
            }
        } catch (IOException exception) {
            LOGGER.warn("Elara loopback port {} is unavailable. {}", port, exception.getMessage());
            close();
            retryTicks = 100;
        }
    }

    private void bind(int port) throws IOException {
        close();
        if (port != 0) {
            var socket = DatagramChannel.open(StandardProtocolFamily.INET);
            try {
                socket.configureBlocking(false);
                socket.bind(new InetSocketAddress("127.0.0.1", port));
                channel = socket;
                boundPort = port;
                LOGGER.info("Elara presentation control listening on 127.0.0.1:{}", port);
            } catch (IOException exception) {
                socket.close();
                throw exception;
            }
        }
    }

    private boolean receive() throws IOException {
        read.clear();
        var address = channel.receive(read);
        if (address == null) {
            return false;
        }
        if (!(address instanceof InetSocketAddress peer) || !peer.getAddress().isLoopbackAddress()
            || read.position() == read.capacity()) {
            return true;
        }
        read.flip();
        String[] fields = StandardCharsets.UTF_8.decode(read).toString().trim().split(" ", 5);
        if (fields.length < 4 || !PROTOCOL.equals(fields[0])) {
            return true;
        }
        try {
            UUID id = UUID.fromString(fields[1]);
            if (completed.containsKey(id)) {
                send(peer, completed.get(id));
                return true;
            }
            long due = Long.parseLong(fields[2]);
            long now = System.currentTimeMillis();
            if (due < now - 8000 || due > now + 8000 || pending.size() >= 32
                || pending.stream().anyMatch(request -> request.id.equals(id))) {
                return true;
            }
            pending.add(new Request(id, due, fields[3], fields.length == 5 ? fields[4] : "", peer));
        } catch (IllegalArgumentException ignored) {
            // Ignore malformed datagrams.
        }
        return true;
    }

    private String execute(MinecraftClient client, Request request) {
        try {
            if (request.action.equals("status")) {
                return reply(request, "OK", status());
            }
            if (client.world == null || client.player == null) {
                throw new IllegalStateException("Open the presentation world first.");
            }
            if (scenarios.loading() && !request.action.equals("load")) {
                throw new IllegalStateException("Wait for the scenario to finish loading.");
            }
            String result = switch (request.action) {
                case "start" -> search.run();
                case "pause" -> search.pause();
                case "resume" -> search.resume();
                case "reset" -> search.reset();
                case "replay" -> scenarios.replay(client);
                case "speed" -> {
                    int speed = Integer.parseInt(request.value);
                    if (speed < 1 || speed > ElaraSettings.MAX_SPEED) {
                        throw new IllegalArgumentException("Speed must be between 1 and " + ElaraSettings.MAX_SPEED + ".");
                    }
                    search.editSettings(options -> options.expansionsPerSecond = speed);
                    yield speed + " exp/s";
                }
                case "load" -> scenarios.load(client, request.value, settings.current().algorithm);
                default -> throw new IllegalArgumentException("Unknown presentation action.");
            };
            client.player.sendMessage(ElaraChat.info(settings.palette(), result), false);
            return reply(request, "OK", result);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return reply(request, "ERROR", exception.getMessage());
        }
    }

    private String status() {
        var options = settings.current();
        var graph = search.graph();
        var metrics = search.metrics();
        return "algorithm=" + search.algorithm() + " mode=" + options.mode + " speed=" + options.expansionsPerSecond
            + " scenario=" + (scenarios.activeName().isEmpty() ? "-" : scenarios.activeName())
            + " graph=" + (graph == null ? "-" : graph.fingerprint())
            + " phase=" + (scenarios.loading() ? "LOADING" : search.phase().replace(' ', '_'))
            + " time=" + search.elapsedSeconds('.')
            + " expanded=" + (metrics == null ? 0 : metrics.expanded())
            + " path=" + (metrics == null ? -1 : metrics.pathLength().orElse(-1));
    }

    private static String reply(Request request, String result, String message) {
        return PROTOCOL + " " + request.id + " " + result + " " + message.replace('\n', ' ').replace('\r', ' ');
    }

    private void send(InetSocketAddress peer, String message) throws IOException {
        channel.send(StandardCharsets.UTF_8.encode(message), peer);
    }

    public void close() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException exception) {
                LOGGER.debug("Cannot close Elara loopback socket.", exception);
            }
        }
        channel = null;
        boundPort = 0;
        pending.clear();
        completed.clear();
    }
}

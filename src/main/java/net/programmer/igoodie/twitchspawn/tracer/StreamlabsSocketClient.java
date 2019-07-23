package net.programmer.igoodie.twitchspawn.tracer;

import com.google.gson.GsonBuilder;
import io.socket.client.IO;
import io.socket.client.Socket;
import net.programmer.igoodie.twitchspawn.TwitchSpawn;
import net.programmer.igoodie.twitchspawn.configuration.ConfigManager;
import net.programmer.igoodie.twitchspawn.configuration.CredentialsConfig;
import net.programmer.igoodie.twitchspawn.tslanguage.EventArguments;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class StreamlabsSocketClient {

    private static StreamlabsSocketClient instance;

    public static StreamlabsSocketClient getInstance() {
        return instance;
    }

    public static void start() {
        if (instance != null)
            throw new IllegalStateException("Streamlabs socket is already listening");

        TwitchSpawn.LOGGER.info("Starting Streamlabs client...");

        instance = new StreamlabsSocketClient();
        instance.sockets.forEach(s -> s.connect());

        TwitchSpawn.LOGGER.info("Started Streamlabs client");
    }

    public static void stop() { stop(null); }

    public static void stop(String reason) {
        if (instance == null)
            throw new IllegalStateException("Streamlabs socket is already stopped");

        TwitchSpawn.LOGGER.info("Stopping Streamlabs client...");

        instance.sockets.forEach(s -> s.disconnect());
        instance = null;

        TwitchSpawn.LOGGER.info("Stopped Streamlabs client {}",
                reason == null ? "" : String.format("(Reason: %s)", reason));
    }

    public static boolean isStarted() {
        return instance != null;
    }

    /* --------------------------------------------------- */

    private List<Socket> sockets = new LinkedList<>();

    private StreamlabsSocketClient() {
        ConfigManager.CREDENTIALS.streamers.forEach(this::createSocket);
    }

    private void createSocket(CredentialsConfig.Streamer streamer) {
        try {
            if (streamer.socketToken == null || streamer.socketToken.isEmpty())
                throw new IllegalArgumentException("Socket token is not set.");

            IO.Options options = new IO.Options();
            options.forceNew = true;
            options.reconnection = false;
            options.transports = new String[]{"websocket"};
            options.query = "token=" + streamer.socketToken;

            Socket socket = IO.socket("https://sockets.streamlabs.com", options);

            AtomicBoolean authorized = new AtomicBoolean(false); // Weird trick

            // Socket connected to the server with no rejections
            socket.on(Socket.EVENT_CONNECT, obj -> {
                TwitchSpawn.LOGGER.info("Connected to Streamlabs Socket with {}'s socket token successfully!", streamer.minecraftNick);
                authorized.set(true);
            });

            // Socket disconnected before or after connection
            socket.on(Socket.EVENT_DISCONNECT, obj -> {
                TwitchSpawn.LOGGER.info("Disconnected from {}'s Streamlabs socket connection. ({})",
                        streamer.minecraftNick, authorized.get() ? "intentional" : "unauthorized");

                if (authorized.get() == false) {
                    StreamlabsSocketClient.stop("Unauthorized by the socket server");
                }
            });

            // Socket received a live event
            socket.on("event", args -> onEvent(socket, streamer, args));

            this.sockets.add(socket);


        } catch (URISyntaxException e) {
            throw new InternalError("Invalid URL. TwitchSpawn is outdated, probably Streamlabs URL is changed.");
        }
    }

    private void onEvent(Socket socket, CredentialsConfig.Streamer streamer, Object... args) {
        JSONObject response = (JSONObject) args[0];

        if (!response.has("message") || response.optJSONArray("message") == null)
            return; // No message field (in expected format), stop here

        String responseType = extractFrom(response, "type", String.class, null);
        String responseFor = extractFrom(response, "for", String.class, null);

        JSONArray messages = extractFrom(response, "message", JSONArray.class, null);

        forEachMessage(messages, message -> {
            EventArguments eventArguments = new EventArguments();
            eventArguments.eventType = responseType;
            eventArguments.eventFor = responseFor;
            eventArguments.streamerNickname = streamer.minecraftNick;
            eventArguments.actorNickname = extractFrom(message, "name", String.class, null);
            eventArguments.message = extractFrom(message, "message", String.class, null);
            eventArguments.donationAmount = Float.parseFloat(extractFrom(message, "amount", String.class, "0"));
            eventArguments.donationCurrency = extractFrom(message, "currency", String.class, null);
            eventArguments.subscriptionMonths = extractFrom(message, "months", Integer.class, 0);
            eventArguments.raiderCount = extractFrom(message, "raiders", Integer.class, 0);
            eventArguments.viewerCount = Integer.parseInt(extractFrom(message, "viewers", String.class, "0"));

            ConfigManager.HANDLING_RULES.handleEvent(eventArguments);
        });
    }

    private <T> T extractFrom(JSONObject json, String key, Class<T> type, T defaultValue) {
        try {
            Object value = json.get(key);
            return type.cast(value);

        } catch (JSONException e) {
            return defaultValue;

        } catch (ClassCastException e) {
            throw new InternalError(String.format("Unable to cast %s key into %s", key, type.getSimpleName()));
        }
    }

    private void forEachMessage(JSONArray array, Consumer<JSONObject> consumer) {
        for (int i = 0; i < array.length(); i++) {
            try {
                JSONObject json = array.getJSONObject(i);
                consumer.accept(json);

            } catch (JSONException e) {
                throw new InternalError("Error performing JSONArray forEachMessage.");
            }
        }
    }

}

package dev.prismglass.scan;

import java.util.List;
import java.util.function.Predicate;

/** Everything the engine looks at, gathered by the scanner (or made up by a test). */
public record ScanInputs(TreeAnalyzer.Report tree, List<TabAnswer> tabs, ServerIntel.Snapshot intel, String brand, StatusInfo status,
                         List<CommandAnswer> commandProbes, List<String> pluginsReply, boolean pluginsCommandRan,
                         Predicate<String> ignored) {

    public enum TabKind {
        /** "/" or "/a": the answer is root command names. */
        ROOT,
        /** "/version ": the answer is plugin names. */
        VERSION,
        /** "/help ": the answer is help topics (command names and plugin names). */
        HELP,
        /** "/plugin:": the answer is that namespace's commands. */
        PREFIX
    }

    /** One tab-completion we asked for. {@code answered} false = the server never replied. */
    public record TabAnswer(String sent, TabKind kind, boolean answered, int start, int length, List<String> entries) {}

    public enum Reply {
        /** Same as the answer to a made-up command. */
        UNKNOWN,
        /** A permission refusal. */
        DENIED,
        /** Anything else: usage, help, a version banner. */
        EXISTS,
        /** No answer at all. */
        SILENT
    }

    /** Our answer to one command we ran. {@code command} has no slash. */
    public record CommandAnswer(String command, Reply reply) {}

    /**
     * What the server list said (null fields = never pinged this server, e.g. direct connect).
     *
     * @param translatorLoaded the client itself translates versions (ViaFabricPlus), so a version mismatch says nothing
     */
    public record StatusInfo(String versionName, int protocol, String clientVersion, int clientProtocol, boolean translatorLoaded) {
        public static StatusInfo none() { return new StatusInfo(null, 0, "", 0, false); }
    }

    public static ScanInputs empty() {
        return new ScanInputs(TreeAnalyzer.Report.empty(), List.of(), ServerIntel.Snapshot.empty(), null, StatusInfo.none(), List.of(), List.of(), false, s -> false);
    }
}

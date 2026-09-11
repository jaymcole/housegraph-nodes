package io.github.jaymcole.housegraph.plugins.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the rule Discord imposes on us — one gateway session per token — is what
 * {@link DiscordGateway} enforces, and that joining someone else's session costs a bot nothing:
 * it still gets every event, its button preferences still count, and its slash commands survive
 * another bot syncing.
 * <p>
 * Logging in is passed in as a {@link DiscordGateway.Login} stub that hands back no connection, so
 * these exercise the joining, sharing and teardown decisions without talking to Discord. Each test
 * uses its own token: the token-to-session map is process-wide by design, and tests must not see
 * each other's.
 */
class DiscordGatewayTest {

    /** A login that never talks to Discord: counts calls, hands back no connection. */
    private static final class CountingLogin implements DiscordGateway.Login {
        final AtomicInteger logins = new AtomicInteger();

        @Override
        public JDA logIn(String token, Object listener) {
            logins.incrementAndGet();
            return null;
        }
    }

    @Test
    void aSecondBotOnTheSameTokenJoinsTheFirstSessionRatherThanOpeningAnother() throws Exception {
        CountingLogin login = new CountingLogin();
        DiscordBot first = new DiscordBot();
        DiscordBot second = new DiscordBot();

        DiscordGateway firstSession = DiscordGateway.join("token-share", first, login);
        DiscordGateway secondSession = DiscordGateway.join("token-share", second, login);

        assertSame(firstSession, secondSession, "both bots must land on one session");
        assertEquals(1, login.logins.get(),
                "a second login on the same token is what makes Discord drop the first session");

        firstSession.leave(first);
        firstSession.leave(second);
    }

    @Test
    void differentTokensGetTheirOwnSessions() throws Exception {
        CountingLogin login = new CountingLogin();
        DiscordBot first = new DiscordBot();
        DiscordBot second = new DiscordBot();

        DiscordGateway firstSession = DiscordGateway.join("token-a", first, login);
        DiscordGateway secondSession = DiscordGateway.join("token-b", second, login);

        assertNotSame(firstSession, secondSession, "two bot identities are two connections");
        assertEquals(2, login.logins.get());

        firstSession.leave(first);
        secondSession.leave(second);
    }

    @Test
    void theSessionOutlivesTheBotThatOpenedItAndEndsWithTheLastToLeave() throws Exception {
        CountingLogin login = new CountingLogin();
        DiscordBot opener = new DiscordBot();
        DiscordBot joiner = new DiscordBot();
        DiscordGateway session = DiscordGateway.join("token-lifetime", opener, login);
        DiscordGateway.join("token-lifetime", joiner, login);

        session.leave(opener);

        DiscordBot late = new DiscordBot();
        assertSame(session, DiscordGateway.join("token-lifetime", late, login),
                "the joiner still holds it, so the opener leaving must not close the session");
        assertEquals(1, login.logins.get(), "still the same session, so still no second login");

        session.leave(joiner);
        session.leave(late);

        DiscordBot afterwards = new DiscordBot();
        assertNotSame(session, DiscordGateway.join("token-lifetime", afterwards, login),
                "once everyone has left, the next bot opens a fresh session");
        assertEquals(2, login.logins.get());
    }

    @Test
    void aFailedLoginLeavesNoSessionBehind() throws Exception {
        DiscordGateway.Login failing = (token, listener) -> {
            throw new IllegalStateException("bad token");
        };
        CountingLogin working = new CountingLogin();

        assertThrows(IllegalStateException.class,
                () -> DiscordGateway.join("token-failed", new DiscordBot(), failing));

        DiscordBot next = new DiscordBot();
        DiscordGateway.join("token-failed", next, working).leave(next);

        assertEquals(1, working.logins.get(),
                "a failed attempt must not leave a session the next bot joins as if it were connected");
    }

    @Test
    void everyBotOnTheSessionGetsEveryMessage() throws Exception {
        CountingLogin login = new CountingLogin();
        DiscordBot first = new DiscordBot();
        DiscordBot second = new DiscordBot();
        DiscordGateway session = DiscordGateway.join("token-fanout", first, login);
        DiscordGateway.join("token-fanout", second, login);
        List<String> seen = new ArrayList<>();
        first.addMessageListener(message -> seen.add("first:" + message.content()));
        second.addMessageListener(message -> seen.add("second:" + message.content()));

        // What the session's bridge does with one gateway event: hand it to every joined bot.
        DiscordMessage message = new DiscordMessage("hello", "channel", "user", "User");
        first.deliverMessage(message);
        second.deliverMessage(message);

        assertEquals(List.of("first:hello", "second:hello"), seen,
                "sharing a connection must not cost a bot the events its own nodes are waiting for");

        session.leave(first);
        session.leave(second);
    }

    @Test
    void aButtonPreferenceCountsWhicheverBotOnTheSessionDeclaredIt() throws Exception {
        CountingLogin login = new CountingLogin();
        DiscordBot first = new DiscordBot();
        DiscordBot second = new DiscordBot();
        DiscordGateway session = DiscordGateway.join("token-buttons", first, login);
        DiscordGateway.join("token-buttons", second, login);

        second.setButtonEphemeral("Yes", false);

        assertTrue(session.isButtonEphemeral("Unknown"), "an undeclared button defaults to ephemeral");
        assertFalse(session.isButtonEphemeral("Yes"),
                "the declaring bot's preference must count even though another bot shares the session");
        assertFalse(first.isButtonEphemeral("Yes"),
                "a connected bot reports what would actually happen at defer time, session-wide");

        session.leave(first);
        session.leave(second);
    }

    @Test
    void aDisableOnClickPreferenceCountsWhicheverBotOnTheSessionDeclaredIt() throws Exception {
        CountingLogin login = new CountingLogin();
        DiscordBot first = new DiscordBot();
        DiscordBot second = new DiscordBot();
        DiscordGateway session = DiscordGateway.join("token-disable", first, login);
        DiscordGateway.join("token-disable", second, login);

        second.setButtonDisableOnClick("Yes", false);

        assertTrue(session.isButtonDisableOnClick("Unknown"),
                "an undeclared button still disables, as a click did before the preference existed");
        assertFalse(session.isButtonDisableOnClick("Yes"),
                "the declaring bot's preference must count even though another bot shares the session");
        assertFalse(first.isButtonDisableOnClick("Yes"),
                "a connected bot reports what would actually happen on a click, session-wide");

        session.leave(first);
        session.leave(second);
    }

    @Test
    void oneBotSyncingRegistersEveryBotsCommandsRatherThanOnlyItsOwn() throws Exception {
        CountingLogin login = new CountingLogin();
        DiscordBot first = new DiscordBot();
        DiscordBot second = new DiscordBot();
        DiscordGateway session = DiscordGateway.join("token-commands", first, login);
        DiscordGateway.join("token-commands", second, login);

        first.syncCommands(List.of(spec("weather")));
        second.syncCommands(List.of(spec("lights")));

        assertEquals(List.of("weather", "lights"), names(session.commandGroups().get(null)),
                "a bare overwrite is what used to let whichever node synced last wipe the other's commands");

        session.leave(first);
        session.leave(second);
    }

    @Test
    void aCommandTwoBotsBothDeclareIsRegisteredOnce() throws Exception {
        CountingLogin login = new CountingLogin();
        DiscordBot first = new DiscordBot();
        DiscordBot second = new DiscordBot();
        DiscordGateway session = DiscordGateway.join("token-duplicate", first, login);
        DiscordGateway.join("token-duplicate", second, login);

        first.syncCommands(List.of(spec("status")));
        second.syncCommands(List.of(spec("status"), spec("lights")));

        assertEquals(List.of("status", "lights"), names(session.commandGroups().get(null)),
                "Discord takes one command list, so a name declared twice is registered once");

        session.leave(first);
        session.leave(second);
    }

    @Test
    void commandsAreGroupedByTheGuildEachBotRegistersTo() throws Exception {
        CountingLogin login = new CountingLogin();
        DiscordBot guildBot = new DiscordBot();
        DiscordBot globalBot = new DiscordBot();
        DiscordGateway session = DiscordGateway.join("token-guilds", guildBot, login);
        DiscordGateway.join("token-guilds", globalBot, login);
        guildBot.setGuildId("123");

        guildBot.syncCommands(List.of(spec("lights")));
        globalBot.syncCommands(List.of(spec("weather")));

        Map<String, List<SlashCommandSpec>> groups = session.commandGroups();
        assertEquals(List.of("lights"), names(groups.get("123")),
                "registering to a guild replaces only that guild's list, so groups can't be merged");
        assertEquals(List.of("weather"), names(groups.get(null)));

        session.leave(guildBot);
        session.leave(globalBot);
    }

    @Test
    void anOptionRestrictedToValuesRegistersThemAsDiscordChoices() throws Exception {
        CountingLogin login = new CountingLogin();
        DiscordBot bot = new DiscordBot();
        DiscordGateway session = DiscordGateway.join("token-choices", bot, login);

        OptionData option = onlyOption(session, spec("deploy",
                new CommandOption("env", DiscordOptionType.TEXT, List.of("prod", "staging"), ChoiceMode.RESTRICTED)));

        assertEquals(List.of("prod", "staging"), option.getChoices().stream().map(Command.Choice::getAsString).toList(),
                "restricting an option is Discord's own choice list - that is what makes it refuse anything else");
        assertFalse(option.isAutoComplete(), "Discord allows choices or autocomplete on an option, never both");
        assertFalse(option.isRequired(), "offering values says what may be passed, not that something must be");

        session.leave(bot);
    }

    @Test
    void anOptionSuggestingValuesAutocompletesInsteadOfRestricting() throws Exception {
        CountingLogin login = new CountingLogin();
        DiscordBot bot = new DiscordBot();
        DiscordGateway session = DiscordGateway.join("token-suggest", bot, login);

        OptionData option = onlyOption(session, spec("deploy",
                new CommandOption("env", DiscordOptionType.TEXT, List.of("prod", "staging"), ChoiceMode.SUGGESTED)));

        assertTrue(option.isAutoComplete());
        assertEquals(List.of(), option.getChoices(),
                "suggestions are answered per keystroke, so they are not registered as choices - "
                        + "which is also why they aren't capped at 25");
        assertEquals(List.of("staging"), session.suggestionsFor("deploy", "env", "stag"));

        session.leave(bot);
    }

    @Test
    void suggestionsMatchIgnoringCaseWithTheOnesTypedIntoFirst() throws Exception {
        CountingLogin login = new CountingLogin();
        DiscordBot bot = new DiscordBot();
        DiscordGateway session = DiscordGateway.join("token-suggest-order", bot, login);
        onlyOption(session, spec("deploy", new CommandOption("env", DiscordOptionType.TEXT,
                List.of("staging-eu", "Prod", "preprod"), ChoiceMode.SUGGESTED)));

        assertEquals(List.of("Prod", "preprod"), session.suggestionsFor("deploy", "env", "PROD"),
                "a value that starts with what was typed is the likelier target, so it comes first");
        assertEquals(List.of("staging-eu", "Prod", "preprod"), session.suggestionsFor("deploy", "env", ""),
                "nothing typed yet matches everything, in declared order");
        assertEquals(List.of(), session.suggestionsFor("deploy", "env", "nope"));

        session.leave(bot);
    }

    @Test
    void onlyTheTwentyFiveSuggestionsDiscordShowsAreOffered() throws Exception {
        CountingLogin login = new CountingLogin();
        DiscordBot bot = new DiscordBot();
        DiscordGateway session = DiscordGateway.join("token-suggest-cap", bot, login);
        List<String> hosts = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            hosts.add("host-" + i);
        }
        onlyOption(session, spec("ssh", new CommandOption("host", DiscordOptionType.TEXT, hosts, ChoiceMode.SUGGESTED)));

        assertEquals(OptionData.MAX_CHOICES, session.suggestionsFor("ssh", "host", "host").size(),
                "declaring more than Discord shows is allowed - answering with more is not");

        session.leave(bot);
    }

    @Test
    void nothingIsSuggestedForAnOptionThisSessionRegisteredNoneFor() throws Exception {
        CountingLogin login = new CountingLogin();
        DiscordBot bot = new DiscordBot();
        DiscordGateway session = DiscordGateway.join("token-suggest-unknown", bot, login);
        onlyOption(session, spec("deploy", new CommandOption("env", DiscordOptionType.TEXT, List.of("prod"), ChoiceMode.SUGGESTED)));

        assertEquals(List.of(), session.suggestionsFor("deploy", "elsewhere", ""), "no such option");
        assertEquals(List.of(), session.suggestionsFor("other", "env", ""), "no such command");

        session.leave(bot);
    }

    @Test
    void anIntegerOptionKeepsOnlyValuesDiscordCanTakeAsNumbers() throws Exception {
        CountingLogin login = new CountingLogin();
        DiscordBot bot = new DiscordBot();
        DiscordGateway session = DiscordGateway.join("token-numbers", bot, login);

        OptionData restricted = onlyOption(session, spec("scale",
                new CommandOption("count", DiscordOptionType.INTEGER, List.of("1", "two", "3"), ChoiceMode.RESTRICTED)));
        assertEquals(List.of(1L, 3L), restricted.getChoices().stream().map(Command.Choice::getAsLong).toList(),
                "a non-numeric choice would have Discord reject the whole command, so it is dropped on its own");

        onlyOption(session, spec("wait",
                new CommandOption("seconds", DiscordOptionType.INTEGER, List.of("5", "soon", "10"), ChoiceMode.SUGGESTED)));
        assertEquals(List.of("5", "10"), session.suggestionsFor("wait", "seconds", ""),
                "an integer option's suggestions go to Discord as numbers, so a word could never be offered");

        session.leave(bot);
    }

    @Test
    void anOptionDiscordWouldRefuseIsDroppedWithoutTakingItsCommandDown() throws Exception {
        CountingLogin login = new CountingLogin();
        DiscordBot bot = new DiscordBot();
        DiscordGateway session = DiscordGateway.join("token-bad-option", bot, login);

        // A name out of Discord's [\w-] - what an older build of this library left in a graph
        // after splitting today's JSON option list on its commas.
        List<SlashCommandData> data = session.toCommandData(List.of(spec("ask",
                new CommandOption("[{\"name\"", DiscordOptionType.TEXT),
                new CommandOption("prompt", DiscordOptionType.TEXT))));

        assertEquals(List.of("ask"), data.stream().map(SlashCommandData::getName).toList(),
                "one unusable option must not cost the command - it used to take /ask off Discord");
        assertEquals(List.of("prompt"), data.get(0).getOptions().stream().map(OptionData::getName).toList());

        session.leave(bot);
    }

    /** Registers {@code spec} the way a sync would and hands back the one option it declares. */
    private static OptionData onlyOption(DiscordGateway session, SlashCommandSpec spec) {
        List<SlashCommandData> data = session.toCommandData(List.of(spec));
        assertEquals(1, data.size());
        assertEquals(1, data.get(0).getOptions().size());
        return data.get(0).getOptions().get(0);
    }

    private static SlashCommandSpec spec(String name, CommandOption... options) {
        return new SlashCommandSpec(name, name, false, false, List.of(options));
    }

    private static SlashCommandSpec spec(String name) {
        return new SlashCommandSpec(name, name, false, false, List.of());
    }

    private static List<String> names(List<SlashCommandSpec> specs) {
        return specs.stream().map(SlashCommandSpec::name).toList();
    }
}

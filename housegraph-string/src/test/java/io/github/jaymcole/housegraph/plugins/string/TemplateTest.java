package io.github.jaymcole.housegraph.plugins.string;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the template grammar behind Format Text. The node itself is thin — parse, then render
 * — so the cases that matter live here, where they need no ports.
 */
class TemplateTest {

    @Test
    void namesEveryPlaceholderInTheOrderItFirstAppears() {
        assertEquals(List.of("commit", "repo"), Template.of("New commit {commit} on {repo}").names());
    }

    @Test
    void aRepeatedPlaceholderIsOnePortAndSubstitutesInBothPlaces() {
        Template template = Template.of("{name}, are you there {name}?");

        assertEquals(List.of("name"), template.names());
        assertEquals("Ada, are you there Ada?", template.render(Map.of("name", "Ada")));
    }

    @Test
    void aPlaceholderNameIsTrimmedSoSpacingInsideTheBracesDoesNotMatter() {
        assertEquals(List.of("user"), Template.of("hi { user }").names());
        assertEquals("hi Ada", Template.of("hi { user }").render(Map.of("user", "Ada")));
    }

    @Test
    void aDoubledBraceIsALiteralOne() {
        Template template = Template.of("{{\"level\": \"{level}\"}}");

        assertEquals(List.of("level"), template.names());
        assertEquals("{\"level\": \"warn\"}", template.render(Map.of("level", "warn")));
    }

    @Test
    void textThatIsNotAWellFormedPlaceholderIsLeftExactlyAsTyped() {
        assertEquals(List.of(), Template.of("50% off {").names());
        assertEquals("50% off {", Template.of("50% off {").render(Map.of()));
        assertEquals("nothing {} here", Template.of("nothing {} here").render(Map.of()));
        assertEquals("a } stray", Template.of("a } stray").render(Map.of()));
    }

    @Test
    void aSlotWithNoValueRendersEmptyRatherThanTheWordNull() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("who", null);

        assertEquals("hi , bye", Template.of("hi {who}, bye").render(values));
        assertEquals("hi , bye", Template.of("hi {who}, bye").render(Map.of()));
    }

    @Test
    void aValueThatIsNotTextIsStringifiedSoNonStringPortsCanBeWiredIn() {
        assertEquals("3 alerts, urgent: true",
                Template.of("{count} alerts, urgent: {flag}").render(Map.of("count", 3, "flag", true)));
    }

    @Test
    void anAbsentTemplateHasNoPlaceholdersAndRendersEmpty() {
        assertEquals(List.of(), Template.of(null).names());
        assertEquals("", Template.of(null).render(Map.of()));
    }
}

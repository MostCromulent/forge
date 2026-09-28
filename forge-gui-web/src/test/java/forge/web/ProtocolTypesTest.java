package forge.web;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Checks the lists {@link ProtocolTypes} writes the browser's protocol types from. */
public class ProtocolTypesTest {
    /**
     * Fails if a record carries a protocol annotation but is missing from the list the generator reads. The lists
     * set the order the types are written in, so they are kept by hand; without this, a record left out of one is
     * simply absent from the generated file, and the browser loses the type with nothing to say it has.
     */
    @Test
    public void everyAnnotatedRecordIsInAList() {
        Assert.assertEquals(annotated(ToBrowser.class, Wire.Message.class), names(ToBrowser.MESSAGES),
                "records marked @Message but missing from ToBrowser.MESSAGES, or the other way about");
        Assert.assertEquals(annotated(ToBrowser.class, Wire.Request.class), names(ToBrowser.REQUESTS),
                "records marked @Request but missing from ToBrowser.REQUESTS, or the other way about");
        Assert.assertEquals(annotated(ToBrowser.class, Wire.Event.class), names(ToBrowser.EVENTS),
                "records marked @Event but missing from ToBrowser.EVENTS, or the other way about");
        Assert.assertEquals(annotated(FromBrowser.class, Wire.Command.class), names(FromBrowser.COMMANDS),
                "records marked @Command but missing from FromBrowser.COMMANDS, or the other way about");
    }

    private static Set<String> annotated(final Class<?> holder, final Class<? extends Annotation> mark) {
        return Stream.of(holder.getDeclaredClasses())
                .filter(c -> c.isAnnotationPresent(mark))
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());
    }

    private static Set<String> names(final List<Class<? extends Record>> listed) {
        return listed.stream().map(Class::getSimpleName).collect(Collectors.toSet());
    }
}

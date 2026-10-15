package forge.web.protocol;

import forge.web.protocol.ToBrowser.ErrorMessage;
import org.testng.Assert;
import org.testng.annotations.Test;


/** The rules every protocol record is written and read by. The tests run with assertions on, as surefire does. */
public class WireTest {

    @Test
    public void aNullInAFieldNotMarkedNullableIsCaught() {
        Assert.expectThrows(AssertionError.class, () -> Wire.encode(new ErrorMessage(null)));
    }

    // Fails if two records read one name, as the second would take every message meant for the first
    @Test
    public void eachNameIsReadByItsOwnRecord() {
        for (final Class<? extends Record> c : FromBrowser.COMMANDS) {
            for (final String name : Wire.commandNames(c)) {
                final Record decoded = Wire.decode(JsonCodec.message(name));
                Assert.assertEquals(decoded.getClass(), c, name);
                Assert.assertEquals(Wire.commandName(decoded), name);
            }
        }
    }

    @Test
    public void everyCommandAndRequestNamesItsWireType() {
        for (final Class<? extends Record> c : FromBrowser.COMMANDS) {
            Assert.assertFalse(Wire.commandNames(c).isEmpty(), c.getSimpleName() + " names no message type");
        }
        for (final Class<? extends Record> r : ToBrowser.REQUESTS) {
            Assert.assertFalse(Wire.requestKinds(r).isEmpty(), r.getSimpleName() + " names no request kind");
        }
    }
}

package forge.web;

import forge.web.FromBrowser.SelectCard;
import forge.web.ToBrowser.ErrorMessage;
import org.testng.Assert;
import org.testng.annotations.Test;


/** The rules every protocol record is written and read by. The tests run with assertions on, as surefire does. */
public class WireTest {

    @Test
    public void aNullInAFieldNotMarkedNullableIsCaught() {
        Assert.expectThrows(AssertionError.class, () -> Wire.encode(new ErrorMessage(null)));
    }

    @Test
    public void aCommandReadIntoTheWrongRecordIsCaught() {
        Assert.expectThrows(AssertionError.class, () -> Wire.decode(JsonCodec.message("ok"), SelectCard.class));
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

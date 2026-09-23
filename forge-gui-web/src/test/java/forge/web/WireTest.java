package forge.web;

import com.google.gson.JsonObject;
import forge.web.FromBrowser.SelectCard;
import forge.web.ToBrowser.ChatLine;
import forge.web.ToBrowser.ErrorMessage;
import forge.web.ToBrowser.OptionRequest;
import forge.web.ToBrowser.Ref;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

/** The rules every protocol record is written and read by. The tests run with assertions on, as surefire does. */
public class WireTest {
    @Test
    public void aMessageCarriesItsTypeAndFields() {
        final JsonObject m = Wire.encode(new ChatLine("Ann", "hi"));
        Assert.assertEquals(m.get("t").getAsString(), "chat");
        Assert.assertEquals(m.get("from").getAsString(), "Ann");
        Assert.assertEquals(m.get("text").getAsString(), "hi");
    }

    @Test
    public void aNullableFieldThatIsNullIsLeftOut() {
        // Netplay's own announcements have no sender
        final JsonObject m = Wire.encode(new ChatLine(null, "Ann joined"));
        Assert.assertFalse(m.has("from"));
    }

    @Test
    public void aNullInAFieldNotMarkedNullableIsCaught() {
        Assert.expectThrows(AssertionError.class, () -> Wire.encode(new ErrorMessage(null)));
    }

    @Test
    public void aRequestCarriesItsKindAndItsDefaultUnderTheWireName() {
        final JsonObject r = Wire.encodeRequest(new OptionRequest("Title", null, new Ref(5), List.of("Yes", "No"), 1));
        Assert.assertEquals(r.get("t").getAsString(), "request");
        Assert.assertEquals(r.get("kind").getAsString(), "option");
        Assert.assertEquals(r.get("default").getAsInt(), 1);
        Assert.assertFalse(r.has("defaultAnswer"));
        Assert.assertEquals(r.getAsJsonObject("card").get("ref").getAsInt(), 5);
        Assert.assertEquals(ToBrowser.defaultOf(r).getAsInt(), 1);
    }

    @Test
    public void aCommandReadsIntoItsRecord() {
        final JsonObject m = JsonCodec.message("selectCard");
        m.addProperty("key", 7);
        m.addProperty("menu", true);
        final SelectCard click = Wire.decode(m, SelectCard.class);
        Assert.assertEquals(click.key(), 7);
        Assert.assertTrue(click.menu());
        Assert.assertEquals(click.x(), 0, "a field the browser left out takes its default");
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

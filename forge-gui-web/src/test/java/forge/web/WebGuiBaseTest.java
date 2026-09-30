package forge.web;

import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


public class WebGuiBaseTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @Test
    public void modelInitialisesWithoutTheDesktopModule() {
        Assert.assertNotNull(FModel.getMagicDb().getCommonCards().getCard("Island"));
    }
}

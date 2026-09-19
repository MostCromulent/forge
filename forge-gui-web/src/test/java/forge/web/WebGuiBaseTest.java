package forge.web;

import forge.gui.GuiBase;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class WebGuiBaseTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @Test
    public void modelInitialisesWithoutTheDesktopModule() {
        Assert.assertNotNull(FModel.getMagicDb().getCommonCards().getCard("Island"));
    }

    @Test
    public void uiThreadIsNamedWebUiAndReportsItself() {
        final AtomicReference<String> name = new AtomicReference<>();
        final AtomicBoolean isGui = new AtomicBoolean();
        GuiBase.getInterface().invokeInEdtAndWait(() -> {
            name.set(Thread.currentThread().getName());
            isGui.set(GuiBase.getInterface().isGuiThread());
        });
        Assert.assertEquals(name.get(), "WebUI");
        Assert.assertTrue(isGui.get());
        Assert.assertFalse(GuiBase.getInterface().isGuiThread());
    }
}

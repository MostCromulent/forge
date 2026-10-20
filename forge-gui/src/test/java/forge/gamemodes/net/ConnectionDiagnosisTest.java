package forge.gamemodes.net;

import static org.testng.Assert.assertEquals;

import java.net.Inet4Address;
import java.util.List;

import org.testng.annotations.Test;

import com.google.common.net.InetAddresses;

import forge.gamemodes.net.ConnectionDiagnosis.AddressKind;
import forge.gamemodes.net.ConnectionDiagnosis.JoinHint;
import forge.gamemodes.net.ConnectionDiagnosis.Subnet;
import forge.gamemodes.net.ConnectionDiagnosis.UPnPResult;
import forge.gamemodes.net.ConnectionDiagnosis.Verdict;

public class ConnectionDiagnosisTest {

    private static Subnet subnet(final String address, final int prefixLength) {
        return new Subnet((Inet4Address) InetAddresses.forString(address), prefixLength);
    }

    // Fails if a range boundary is off by one, or a host name or 0.0.0.0 counts as a real address
    @Test
    public void classifiesAddresses() {
        assertEquals(ConnectionDiagnosis.classify("100.64.0.1"), AddressKind.CGNAT);
        assertEquals(ConnectionDiagnosis.classify("100.127.255.254"), AddressKind.CGNAT);
        assertEquals(ConnectionDiagnosis.classify("192.0.0.2"), AddressKind.CGNAT);
        assertEquals(ConnectionDiagnosis.classify("100.63.255.255"), AddressKind.PUBLIC);
        assertEquals(ConnectionDiagnosis.classify("100.128.0.1"), AddressKind.PUBLIC);
        assertEquals(ConnectionDiagnosis.classify("192.168.0.12"), AddressKind.PRIVATE);
        assertEquals(ConnectionDiagnosis.classify("172.31.0.1"), AddressKind.PRIVATE);
        assertEquals(ConnectionDiagnosis.classify("172.32.0.1"), AddressKind.PUBLIC);
        assertEquals(ConnectionDiagnosis.classify("10.1.2.3"), AddressKind.PRIVATE);
        assertEquals(ConnectionDiagnosis.classify("203.0.113.45"), AddressKind.PUBLIC);
        assertEquals(ConnectionDiagnosis.classify("0.0.0.0"), AddressKind.UNKNOWN);
        assertEquals(ConnectionDiagnosis.classify(""), AddressKind.UNKNOWN);
        assertEquals(ConnectionDiagnosis.classify(null), AddressKind.UNKNOWN);
        assertEquals(ConnectionDiagnosis.classify("router.local"), AddressKind.UNKNOWN);
        assertEquals(ConnectionDiagnosis.classify("2001:db8::1"), AddressKind.UNKNOWN);
    }

    // Fails if a forwarded port is reported as reachable when the router's own address shows it cannot be
    @Test
    public void diagnosesForwardedPort() {
        assertEquals(ConnectionDiagnosis.diagnose(UPnPResult.FORWARDED, "100.72.4.9", "203.0.113.45"), Verdict.CGNAT);
        assertEquals(ConnectionDiagnosis.diagnose(UPnPResult.FORWARDED, "192.168.0.12", "203.0.113.45"), Verdict.DOUBLE_NAT);
        assertEquals(ConnectionDiagnosis.diagnose(UPnPResult.FORWARDED, "198.51.100.7", "203.0.113.45"), Verdict.ADDRESS_MISMATCH);
        assertEquals(ConnectionDiagnosis.diagnose(UPnPResult.FORWARDED, "203.0.113.45", "203.0.113.45"), Verdict.FORWARDED);
    }

    // Fails if a missing address from the router or from the internet is treated as a problem when the other one is usable
    @Test
    public void diagnosesWithMissingAddresses() {
        assertEquals(ConnectionDiagnosis.diagnose(UPnPResult.FORWARDED, null, "203.0.113.45"), Verdict.FORWARDED);
        assertEquals(ConnectionDiagnosis.diagnose(UPnPResult.FORWARDED, "0.0.0.0", "203.0.113.45"), Verdict.FORWARDED);
        assertEquals(ConnectionDiagnosis.diagnose(UPnPResult.FORWARDED, "203.0.113.45", null), Verdict.FORWARDED);
        assertEquals(ConnectionDiagnosis.diagnose(UPnPResult.FORWARDED, null, null), Verdict.NO_ADDRESS);
        assertEquals(ConnectionDiagnosis.diagnose(UPnPResult.FORWARDED, "100.72.4.9", null), Verdict.CGNAT);
    }

    // Fails if a joiner gets a hint about an address that can work for them, or a host name is looked up
    @Test
    public void hintsOnlyWhenTheAddressCannotWork() {
        final List<Subnet> elsewhere = List.of(subnet("10.0.0.5", 24), subnet("127.0.0.1", 8));
        final List<Subnet> sameNetwork = List.of(subnet("192.168.1.7", 24));
        final List<Subnet> onTailscale = List.of(subnet("192.168.1.7", 24), subnet("100.101.1.2", 32));
        assertEquals(ConnectionDiagnosis.joinHint("192.168.1.50", elsewhere), JoinHint.LOCAL_ADDRESS);
        assertEquals(ConnectionDiagnosis.joinHint("192.168.1.50", sameNetwork), null);
        assertEquals(ConnectionDiagnosis.joinHint("127.0.0.1", elsewhere), null);
        assertEquals(ConnectionDiagnosis.joinHint("100.72.4.9", sameNetwork), JoinHint.CGNAT_ADDRESS);
        assertEquals(ConnectionDiagnosis.joinHint("100.72.4.9", onTailscale), null);
        assertEquals(ConnectionDiagnosis.joinHint("203.0.113.45", sameNetwork), null);
        assertEquals(ConnectionDiagnosis.joinHint("example.invalid", sameNetwork), null);
    }
}

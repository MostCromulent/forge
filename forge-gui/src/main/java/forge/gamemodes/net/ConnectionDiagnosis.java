package forge.gamemodes.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.common.net.InetAddresses;

import forge.gamemodes.net.event.MessageEvent;
import forge.util.Localizer;

/**
 * Explains why players outside the host's network may not be able to connect. The host side reads what the router
 * and the internet report about the host's address. The joiner side reads the address the joiner typed.
 */
public final class ConnectionDiagnosis {
    private ConnectionDiagnosis() { }

    public enum UPnPResult { FORWARDED, NO_ANSWER, REFUSED }

    enum Verdict {
        FORWARDED("lblUPnPSuccess", false),
        CGNAT("lblNatCgnat", true),
        DOUBLE_NAT("lblNatDoubleNat", true),
        ADDRESS_MISMATCH("lblNatAddressMismatch", true),
        NO_ADDRESS("lblForgeUnableDetermineYourExternalIP", true),
        NO_ANSWER("lblUPnPNoAnswer", true),
        REFUSED("lblUPnPFailed", true);

        final String messageKey;
        final boolean warning;

        Verdict(final String messageKey, final boolean warning) {
            this.messageKey = messageKey;
            this.warning = warning;
        }
    }

    enum AddressKind { PUBLIC, CGNAT, PRIVATE, UNKNOWN }

    enum JoinHint { LOCAL_ADDRESS, CGNAT_ADDRESS }

    /** One IPv4 network the joiner is on. */
    record Subnet(Inet4Address address, int prefixLength) {
        boolean contains(final Inet4Address ip) {
            return prefixLength > 0 && sameNetwork(address, ip, prefixLength);
        }
    }

    private static final Inet4Address SHARED_ADDRESS_SPACE = ipv4("100.64.0.0");
    // DS-Lite and 464XLAT routers take their IPv4 WAN address from this block (RFC 6333)
    private static final Inet4Address DS_LITE_RANGE = ipv4("192.0.0.0");

    /** The lobby message for a UPnP attempt. Addresses are null when the router or the internet did not report one. */
    public static MessageEvent hostMessage(final UPnPResult result, final int port, final String routerAddress, final String publicAddress) {
        final Verdict verdict = diagnose(result, routerAddress, publicAddress);
        final String portText = String.valueOf(port);
        // Every message gets the port and the join address, and uses only the ones it needs
        final String text = Localizer.getInstance().getMessage(verdict.messageKey, portText,
                (publicAddress != null ? publicAddress : routerAddress) + ":" + portText);
        return verdict.warning ? MessageEvent.warning(text) : new MessageEvent(text);
    }

    static Verdict diagnose(final UPnPResult result, final String routerAddress, final String publicAddress) {
        if (result == UPnPResult.NO_ANSWER) {
            return Verdict.NO_ANSWER;
        }
        if (result == UPnPResult.REFUSED) {
            return Verdict.REFUSED;
        }
        final AddressKind router = classify(routerAddress);
        if (router == AddressKind.CGNAT) {
            return Verdict.CGNAT;
        }
        if (router == AddressKind.PRIVATE) {
            return Verdict.DOUBLE_NAT;
        }
        if (publicAddress == null) {
            return router == AddressKind.PUBLIC ? Verdict.FORWARDED : Verdict.NO_ADDRESS;
        }
        if (router == AddressKind.PUBLIC && !routerAddress.equals(publicAddress)) {
            return Verdict.ADDRESS_MISMATCH;
        }
        return Verdict.FORWARDED;
    }

    /** A note for a failed join that explains why the typed address cannot work, or null when there is nothing to add. */
    public static String joinMessage(final String host) {
        final JoinHint hint = joinHint(host, ownSubnets());
        if (hint == null) {
            return null;
        }
        return Localizer.getInstance().getMessage(hint == JoinHint.LOCAL_ADDRESS ? "lblJoinLocalAddress" : "lblJoinCgnatAddress", host);
    }

    static JoinHint joinHint(final String host, final List<Subnet> own) {
        final Inet4Address ip = parseIPv4(host);
        if (ip == null || own.stream().anyMatch(s -> s.contains(ip))) {
            return null;
        }
        final AddressKind kind = classify(host);
        if (kind == AddressKind.PRIVATE) {
            return JoinHint.LOCAL_ADDRESS;
        }
        // Tailscale gives its devices addresses in the shared address space, so a joiner on Tailscale can reach them
        if (kind == AddressKind.CGNAT && own.stream().noneMatch(s -> isSharedAddressSpace(s.address()))) {
            return JoinHint.CGNAT_ADDRESS;
        }
        return null;
    }

    /** Whether an address is in 100.64.0.0/10, which providers use for carrier-grade NAT and Tailscale uses for its devices. */
    public static boolean isSharedAddressSpace(final String address) {
        final Inet4Address ip = parseIPv4(address);
        return ip != null && isSharedAddressSpace(ip);
    }

    static AddressKind classify(final String address) {
        final Inet4Address ip = parseIPv4(address);
        if (ip == null || ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()) {
            return AddressKind.UNKNOWN;
        }
        if (isSharedAddressSpace(ip) || sameNetwork(ip, DS_LITE_RANGE, 24)) {
            return AddressKind.CGNAT;
        }
        return ip.isSiteLocalAddress() ? AddressKind.PRIVATE : AddressKind.PUBLIC;
    }

    private static boolean isSharedAddressSpace(final Inet4Address ip) {
        return sameNetwork(ip, SHARED_ADDRESS_SPACE, 10);
    }

    private static List<Subnet> ownSubnets() {
        final List<Subnet> subnets = new ArrayList<>();
        try {
            for (final NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp()) {
                    continue;
                }
                for (final InterfaceAddress address : iface.getInterfaceAddresses()) {
                    if (address.getAddress() instanceof Inet4Address ip) {
                        subnets.add(new Subnet(ip, address.getNetworkPrefixLength()));
                    }
                }
            }
        } catch (final SocketException ignored) { }
        return subnets;
    }

    private static boolean sameNetwork(final Inet4Address a, final Inet4Address b, final int prefixLength) {
        final int mask = prefixLength >= 32 ? -1 : ~(-1 >>> prefixLength);
        return (InetAddresses.coerceToInteger(a) & mask) == (InetAddresses.coerceToInteger(b) & mask);
    }

    // Accepts only an IPv4 literal, so a host name is never looked up in DNS
    private static Inet4Address parseIPv4(final String address) {
        if (address == null || !InetAddresses.isInetAddress(address)) {
            return null;
        }
        final InetAddress ip = InetAddresses.forString(address);
        return ip instanceof Inet4Address ip4 ? ip4 : null;
    }

    private static Inet4Address ipv4(final String literal) {
        return (Inet4Address) InetAddresses.forString(literal);
    }
}

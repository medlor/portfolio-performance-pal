package pal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import jdk.net.ExtendedSocketOptions;

/** Opt-in disposable-fixture boundary observer, never an operation bypass. */
final class WriteObserver {
    static void phase(Path file, String phase, Path staging) throws IOException {
        String value = System.getenv("PP_PAL_WRITE_OBSERVER");
        if (value == null) return;
        Path socket = Path.of(value).toAbsolutePath().normalize();
        Path marker = file.getParent().resolve(".pp-pal-disposable-fixture");
        if (!socket.getParent().equals(file.getParent()) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !Files.readString(marker).equals("pp-pal-write-observer-v1\n") || Files.isSymbolicLink(socket))
            throw new IOException("Observer requires an explicitly marked disposable fixture and adjacent nonsymlink socket");
        long deadline = System.nanoTime() + 10_000_000_000L;
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX); var selector = Selector.open()) {
            channel.configureBlocking(false);
            channel.connect(UnixDomainSocketAddress.of(socket));
            channel.register(selector, SelectionKey.OP_CONNECT);
            while (!channel.finishConnect()) await(selector, deadline);
            var peer = channel.getOption(ExtendedSocketOptions.SO_PEERCRED);
            if (!peer.user().equals(Files.getOwner(Path.of("/proc/self"))) || !peer.user().equals(Files.getOwner(marker, LinkOption.NOFOLLOW_LINKS))) throw new IOException("Observer peer must have the fixture owner's UID");
            channel.keyFor(selector).interestOps(SelectionKey.OP_WRITE);
            var message = ByteBuffer.wrap((phase + "\n" + file + "\n" + (staging == null ? "" : staging) + "\n").getBytes(StandardCharsets.UTF_8));
            while (message.hasRemaining()) if (channel.write(message) == 0) await(selector, deadline);
            channel.keyFor(selector).interestOps(SelectionKey.OP_READ);
            var reply = ByteBuffer.allocate(256);
            while (true) {
                int read = channel.read(reply);
                if (read < 0) throw new IOException("Observer disconnected without acknowledgement");
                String text = new String(reply.array(), 0, reply.position(), StandardCharsets.UTF_8);
                if (text.endsWith("\n")) {
                    if (!text.equals("ACK " + phase + "\n")) throw new IOException("Observer acknowledgement mismatched phase");
                    break;
                }
                if (!reply.hasRemaining()) throw new IOException("Observer acknowledgement too long");
                if (read == 0) await(selector, deadline);
            }
        }
    }
    private static void await(Selector selector, long deadline) throws IOException {
        long left = (deadline - System.nanoTime()) / 1_000_000;
        if (left <= 0) throw new IOException("Observer acknowledgement timed out");
        selector.select(Math.min(left, 1000)); selector.selectedKeys().clear();
    }
}

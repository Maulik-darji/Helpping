import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Very small TCP relay for HelpPing walkie-talkie audio.
 *
 * Protocol (big-endian):
 * - Frame header: int MAGIC('HPPT'), byte VERSION(1), byte TYPE, short RESERVED(0)
 *
 * TYPE=1 JOIN:
 *   - short roomLen
 *   - room bytes (UTF-8)
 *
 * TYPE=2 AUDIO:
 *   - short payloadLen
 *   - payload bytes (the same UDP packet used by LAN mode: 12-byte header + PCM16 audio)
 *
 * Server relays AUDIO frames to all other clients in the same room.
 */
public final class PttRelayServer {

    private static final int MAGIC = 0x48505054; // 'HPPT'
    private static final byte VERSION = 1;
    private static final byte TYPE_JOIN = 1;
    private static final byte TYPE_AUDIO = 2;

    private final int port;
    private final Map<String, Set<Client>> rooms = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        int port = 50555;
        if (args.length >= 1) {
            port = Integer.parseInt(args[0]);
        }
        new PttRelayServer(port).run();
    }

    private PttRelayServer(int port) {
        this.port = port;
    }

    private void run() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("PttRelayServer listening on port " + port);
            while (true) {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                Client client = new Client(socket);
                new Thread(() -> handleClient(client), "ptt-client").start();
            }
        }
    }

    private void handleClient(Client client) {
        try {
            while (true) {
                int magic = client.in.readInt();
                byte ver = client.in.readByte();
                byte type = client.in.readByte();
                client.in.readShort(); // reserved

                if (magic != MAGIC || ver != VERSION) {
                    throw new IOException("Bad frame header");
                }

                if (type == TYPE_JOIN) {
                    int roomLen = client.in.readUnsignedShort();
                    if (roomLen <= 0 || roomLen > 512) {
                        throw new IOException("Bad room length: " + roomLen);
                    }
                    byte[] roomBytes = new byte[roomLen];
                    client.in.readFully(roomBytes);
                    String room = new String(roomBytes, StandardCharsets.UTF_8);
                    moveClientToRoom(client, room);
                    continue;
                }

                if (type == TYPE_AUDIO) {
                    int payloadLen = client.in.readUnsignedShort();
                    if (payloadLen <= 0 || payloadLen > 4096) {
                        throw new IOException("Bad payload length: " + payloadLen);
                    }
                    byte[] payload = new byte[payloadLen];
                    client.in.readFully(payload);
                    relayAudio(client, payload);
                    continue;
                }

                throw new IOException("Unknown type: " + type);
            }
        } catch (EOFException eof) {
            // client disconnected
        } catch (Throwable t) {
            System.out.println("Client error: " + t.getMessage());
        } finally {
            removeClient(client);
            client.close();
        }
    }

    private void moveClientToRoom(Client client, String room) {
        if (client.room != null && client.room.equals(room)) return;

        removeClient(client);
        client.room = room;

        rooms.computeIfAbsent(room, k -> ConcurrentHashMap.newKeySet()).add(client);
        System.out.println("Client joined room: " + room + " (clients=" + rooms.get(room).size() + ")");
    }

    private void relayAudio(Client sender, byte[] payload) {
        String room = sender.room;
        if (room == null) return;

        Set<Client> clients = rooms.get(room);
        if (clients == null) return;

        for (Client c : clients) {
            if (c == sender) continue;
            try {
                synchronized (c.out) {
                    c.out.writeInt(MAGIC);
                    c.out.writeByte(VERSION);
                    c.out.writeByte(TYPE_AUDIO);
                    c.out.writeShort(0);
                    c.out.writeShort(payload.length);
                    c.out.write(payload);
                    c.out.flush();
                }
            } catch (IOException e) {
                // Drop broken client on next read/write.
            }
        }
    }

    private void removeClient(Client client) {
        String room = client.room;
        if (room == null) return;
        Set<Client> clients = rooms.get(room);
        if (clients != null) {
            clients.remove(client);
            if (clients.isEmpty()) {
                rooms.remove(room);
            }
        }
        client.room = null;
    }

    private static final class Client {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;
        volatile String room;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        void close() {
            try {
                socket.close();
            } catch (Throwable ignore) {
            }
        }
    }
}


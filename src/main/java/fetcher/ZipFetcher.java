package fetcher;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import org.slf4j.Logger;

/**
 * Fetches the specified file to the specified socket.
 *
 * @author ales
 */
public class ZipFetcher implements Runnable {

    private final int action;
    private final Socket socket;  // socket to which the file is to be fetched
    private final String pcName;

    private ZipFile zipFile;  // file to be fetched    
    private long timeLeft;
    private String IP;
    private final Logger LOG;

    /**
     * Constructor for fetching a file to the client.
     *
     * @param socket
     * @param pcName
     * @param jarFile
     * @param LOG
     */
    public ZipFetcher(Socket socket, String pcName, ZipFile jarFile, Logger LOG) {
        this.socket = socket;
        this.pcName = pcName;
        this.zipFile = jarFile;        

        action = Actions.TRANSFER_FILE;
        this.LOG = LOG;
    }

    /**
     * Constructor for sending a check message.
     *
     * @param socket
     * @param pcName
     * @param action
     * @param timeLeft
     * @param LOG
     */
    public ZipFetcher(Socket socket, String pcName, int action, long timeLeft, Logger LOG) {
        if (action != Actions.CHECK && action != Actions.LIST_SOCKETS) {
            throw new IllegalArgumentException();
        }
        this.action = action;
        this.socket = socket;
        this.pcName = pcName;
        this.timeLeft = timeLeft;
        this.LOG = LOG;
    }

    /**
     * Constructor for adding and removing sockets.
     *
     * @param socket
     * @param pcName
     * @param action
     * @param IP
     * @param timeLeft
     * @param LOG
     */
    public ZipFetcher(Socket socket, String pcName, int action, String IP, long timeLeft, Logger LOG) {
        if (action != Actions.ADD_SOCKET && action != Actions.REMOVE_SOCKET) {
            throw new IllegalArgumentException();
        }

        this.socket = socket;
        this.pcName = pcName;
        this.action = action;
        this.timeLeft = timeLeft;
        this.IP = IP;
        this.LOG = LOG;
    }

    private void fetch(DataOutputStream out) throws IOException {
        try (InputStream in = new FileInputStream(zipFile.zipFile)) {
            // send name of the .jar file and arguments
            out.writeUTF(zipFile.jarFileName);
            out.writeUTF(zipFile.args);

            // send the actual .jar file
            byte[] bytes = new byte[16 * 1024];
            int count;
            while ((count = in.read(bytes)) > 0) {
                out.write(bytes, 0, count);
            }
            out.flush();

            LOG.info("data transfer to " + pcName
                    + " has been compleated");
        }
    }

    private void receiveSocketList() throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());

        long timeGap = in.readLong();
        int nSockets = in.readInt();

        synchronized (LOG) {
            LOG.info(pcName + ":");
            LOG.info("  time gap: " + timeGap);
            LOG.info("  socket list:");

            for (int i = 0; i < nSockets; ++i) {
                String ip = in.readUTF();
                int port = in.readInt();

                LOG.info("    " + (i + 1) + ". " + ip + ":" + port);
            }
        }
    }

    /**
     * Send data to the client.
     */
    @Override
    public void run() {
        Thread.currentThread().setName(pcName);
        
        try (DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
            out.write(action);
            switch (action) {
                case Actions.CHECK:
                    out.writeLong(timeLeft);
                    out.flush();
                    break;
                case Actions.TRANSFER_FILE:
                    fetch(out);
                    break;
                case Actions.ADD_SOCKET:
                case Actions.REMOVE_SOCKET:
                    out.writeUTF(IP);
                    out.writeLong(timeLeft);
                    out.flush();
                    break;
                case Actions.LIST_SOCKETS:
                    out.writeLong(timeLeft);
                    receiveSocketList();
                    out.flush();
                    break;
            }

            socket.close();
        } catch (IOException ex) {
            LOG.error(ex.toString());
            ex.printStackTrace();
        }
    }
}

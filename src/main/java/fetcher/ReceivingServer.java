package fetcher;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLServerSocketFactory;
import org.slf4j.Logger;

/**
 *
 * @author ales
 */
public class ReceivingServer implements Runnable {

    private final int port;
    private final ZipFiles zipFiles;
    private final Map<String, String> pcList;
    private final Logger LOG;

    public ReceivingServer(int port, ZipFiles zipFiles, Map<String, String> pcList, Logger LOG) {
        this.port = port;
        this.zipFiles = zipFiles;
        this.pcList = pcList;
        this.LOG = LOG;
    }

    @Override
    public void run() {
        Thread.currentThread().setName("server-" + port);
        
        try {
            System.setProperty("javax.net.ssl.keyStore", "testkeystore.ks");
            System.setProperty("javax.net.ssl.keyStorePassword", "thebat");
            SSLServerSocketFactory socketFactory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            ServerSocket serverSocket = socketFactory.createServerSocket(port);

            List<Thread> threadList = new ArrayList<>();
            
            LOG.info("ReceivingServer is waiting on port " + port + " to receive data");
            for (int i = 0; i < zipFiles.list.size(); ++i) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setTcpNoDelay(true);

                    Thread receiver = new Thread(new Receiver(socket));
                    threadList.add(receiver);
                    receiver.start();
                } catch (IOException ex) {
                    LOG.error("error while copying data from a client: " + ex.getMessage());
                }
            }
            
            try {
                for (Thread t : threadList) {
                    t.join();
                }
            } catch (InterruptedException ex) {
                System.err.println(ex);
            }
            
            LOG.info("receiveng server has shut down");
        } catch (IOException ex) {
            LOG.error(ex.getMessage(), ex);
        }
    }

    private class Receiver implements Runnable {

        private final Socket socket;

        public Receiver(Socket socket) {
            this.socket = socket;
        }

        private void receiveFile(DataInputStream in, String path) throws IOException {
            String fileName = in.readUTF();
            long fileSize = in.readLong();

            try (OutputStream out = new FileOutputStream(path + '/' + fileName)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while (fileSize > 0 && (count = in.read(buffer, 0, (int) Math.min(buffer.length, fileSize))) >= 0) {
                    out.write(buffer, 0, count);
                    fileSize -= count;
                }
            }
            LOG.debug("file {} have been saved into {}", fileName, path);
            LOG.info("lended file: ", fileName);
        }

        @Override
        public void run() {
            String pcName = pcList.get(socket.getInetAddress().getHostAddress());
            if (pcName == null) {
                pcName = socket.getInetAddress().getHostAddress();
            }
            Thread.currentThread().setName(pcName);
            LOG.info("waiting to receive data from " + pcName);
            
            try (DataInputStream in = new DataInputStream(socket.getInputStream())) {
                String tokens[] = in.readUTF().split(" ");
                String jarFileName = tokens[0];
                String path = tokens[1];

                File dir = new File(path);
                boolean madeDir = dir.mkdirs();
                if (madeDir) {
                    LOG.debug("created directory " + path);
                }

                try {
                    while (true) {
                        receiveFile(in, path);
                    }
                } catch (EOFException ex) {
                    LOG.info("received data produced by "
                            + jarFileName + " into " + path);
                }
            } catch (IOException ex) {
                LOG.error(ex.getMessage());
            }
        }
    }
}

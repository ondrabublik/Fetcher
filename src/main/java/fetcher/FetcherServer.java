package fetcher;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import java.sql.Timestamp;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.logging.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author ales
 */
public class FetcherServer extends Thread {

    private static final Logger LOG = LoggerFactory.getLogger(FetcherServer.class);
    public static final String pcNameFile = "network/pcNames.txt";
    public static final String zipDir = "tasks";

    private int action;  // determines what is to be done
    private int port; // number of the port on which the server listens
    // time in millisecond in which clients have a chance to connect to the server
    private long timeout;
    private final Map<String, String> pcList;
    private final boolean filterPCs;
    public int maxNumClients;

    private ServerSocket serverSocket;
    private ZipFile zipFile;// file to be fetched to clients
    private ZipFiles zipFiles;
    private String IP;
    private int nClients;
    
    public static List<String> checkedList = null;

    public FetcherServer() throws IOException {
        
        FetcherProperties props = new FetcherProperties();
        
        String parameterFilePath = "network/parameters.txt";
        try {
            props.load(new FileInputStream(parameterFilePath));
        } catch (IOException ex) {
            throw new IOException("unable to load file " + parameterFilePath, ex);
        }
        
        timeout = 3000;
        if (props.containsKey("timeout")) {
            timeout = props.getInt("timeout");
        }

        port = 4444;
        if (props.containsKey("fetcherPort")) {
            port = props.getInt("fetcherPort");
        }

        String pcListFile = null;
        if (props.containsKey("pcFilterFile")) {
            pcListFile = props.getString("pcFilterFile");
        }
        if (pcListFile == null) {
            pcList = loadCompletePCList();
        } else {
            pcList = loadPCList(pcListFile);
        }
        filterPCs = true;
        maxNumClients = Integer.MAX_VALUE;
    }

    public void initIPAddition(String IP) {
        action = Actions.ADD_SOCKET;
        this.IP = IP;
    }

    public void initIPRemoval(String IP) {
        action = Actions.REMOVE_SOCKET;
        this.IP = IP;
    }

    public void initFetcher(ZipFile zipFile) {
        action = Actions.TRANSFER_FILE;
        this.zipFile = zipFile;
    }

    public void initFetcher(ZipFile zipFile, int nClients) {
        action = Actions.TRANSFER_FILE;
        this.zipFile = zipFile;
        this.maxNumClients = nClients;
    }

    public void initMultifetcher(ZipFiles zipFiles) {
        this.action = Actions.TRANSFER_MULTIPLE_FILES;
        this.zipFiles = zipFiles;
        this.timeout = 0;  // infinite timeout
        this.maxNumClients = Integer.MAX_VALUE;

        Thread receiver = new Thread(new ReceivingServer(6666, zipFiles, pcList, LOG));
        receiver.start();
    }

    public void initChecker() {
        action = Actions.CHECK;
        checkedList = new LinkedList();
        
        try (FileWriter fw = new FileWriter("fetcherState.txt");
                BufferedWriter bw = new BufferedWriter(fw);
                PrintWriter out = new PrintWriter(bw)) {
            out.println("date: " + new Timestamp((new java.util.Date()).getTime()));
            out.println("resources available:");
            out.close();
        } catch (IOException e) {
            LOG.error(e.toString());
        }
    }

    public void initLister() {
        action = Actions.LIST_SOCKETS;
    }

    public int getNumClients() {
        return nClients;
    }

    public List<String> getCheckedList(){
        return checkedList;
    }
    
    public long getTimeOut(){
        return timeout;
    }
    
    @Override
    public void run() {
        Thread.currentThread().setName("server-" + port);
        try {
            System.setProperty("javax.net.ssl.keyStore", "testkeystore.ks");
            System.setProperty("javax.net.ssl.keyStorePassword", "thebat");
            SSLServerSocketFactory socketFactory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            serverSocket = socketFactory.createServerSocket(port);

            List<Thread> threadList = new ArrayList<>();

            if (timeout > 0) {
                LOG.info("clients have {} second to connect to the port {}...", timeout / 1000.0, serverSocket.getLocalPort());
            } else {
                LOG.info("server is listening on port " + serverSocket.getLocalPort() + "...");
            }
            Interrupter interupter = new Interrupter(timeout);
            interupter.start();

            switch (action) {
                case Actions.TRANSFER_MULTIPLE_FILES: {
                    try {
                        fetchMultipleFiles(threadList);
                    } catch (InterruptedException ex) {
                        java.util.logging.Logger.getLogger(FetcherServer.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
                break;

                default:
                    defaultTask(threadList, interupter);
            }

            // wait for threads to finish and close port 
            try {
                for (Thread t : threadList) {
                    t.join();
                }
            } catch (InterruptedException ex) {
                System.err.println(ex);
            }

            interupter.interrupt();
            serverSocket.close();

            // deleting zip file
            if (action == Actions.TRANSFER_MULTIPLE_FILES) {
                for (ZipFile zip : zipFiles.list) {
                    zip.zipFile.delete();
                }
            }

            LOG.info("fetcher server has shut down");
            LOG.info(nClients + " connections have been made");
        } catch (IOException ex) {
            LOG.error(ex.toString());
        }
    }

    private void defaultTask(List<Thread> threadList, Interrupter interupter) throws IOException {
        TreeSet<String> ipSet = new TreeSet<>();

        for (nClients = 0; nClients < maxNumClients; ++nClients) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);

                String ip = socket.getInetAddress().getHostAddress();
                String pcName = pcList.get(ip);
                if ((filterPCs && pcName == null) || !ipSet.add(ip)) { // !(ipSet.add(ip) || action == Actions.TRANSFER_MULTIPLE_JARS)
                    if (pcName == null) {
                        pcName = ip;
                    }
                    Thread fetcher = new Thread(new ZipFetcher(socket, pcName, Actions.CHECK, interupter.getTimeLeft(), LOG));
                    fetcher.start();

                    --nClients;
                    continue;
                }

                if (pcName == null) {
                    pcName = ip;
                }

                LOG.info("{}) just connected to {}", nClients + 1, pcName);
                Thread fetcher;
                switch (action) {
                    case Actions.CHECK:
                        fetcher = new Thread(new ZipFetcher(socket, pcName, action, interupter.getTimeLeft(), LOG));
                        checkedList.add(pcName);
                        try (FileWriter fw = new FileWriter("fetcherState.txt", true);
                                BufferedWriter bw = new BufferedWriter(fw);
                                PrintWriter out = new PrintWriter(bw)) {
                            out.println(pcName);
                        } catch (IOException e) {
                            LOG.error(e.toString());
                        }
                        break;
                    case Actions.LIST_SOCKETS:
                        fetcher = new Thread(new ZipFetcher(socket, pcName, action, interupter.getTimeLeft(), LOG));
                        break;
                    case Actions.TRANSFER_FILE:
                        fetcher = new Thread(new ZipFetcher(socket, pcName, zipFile, LOG));
                        break;
                    case Actions.ADD_SOCKET:
                    case Actions.REMOVE_SOCKET:
                        fetcher = new Thread(new ZipFetcher(socket, pcName, action, IP, interupter.getTimeLeft(), LOG));
                        break;
                    default:
                        throw new IOException("unknown action");
                }
                fetcher.start();
                threadList.add(fetcher);

            } catch (SocketException ex) {
                break;
            }
        }
    }

    private void fetchMultipleFiles(List<Thread> threadList) throws IOException, InterruptedException {
        Iterator<ZipFile> iterator = zipFiles.list.iterator();
        nClients = 0;
        while (iterator.hasNext()) {
            ZipFile zip = iterator.next();

            try {
                Socket socket;
                String pcName;
                while (true) {
                    socket = serverSocket.accept();
                    socket.setTcpNoDelay(true);

                    String ip = socket.getInetAddress().getHostAddress();
                    pcName = pcList.get(ip);
                    if (filterPCs && pcName == null) { // !(ipSet.add(ip) || action == Actions.TRANSFER_MULTIPLE_JARS)
                        Thread fetcher = new Thread(new ZipFetcher(socket, ip, Actions.CHECK, 0, LOG));
                        fetcher.start();
                        continue;
                    }

                    if (pcName == null) {
                        pcName = ip;
                    }

                    break;
                }

                LOG.info("{}) just connected to {}", ++nClients, pcName);

                Thread fetcher = new Thread(new ZipFetcher(socket, pcName, zip, LOG));

                fetcher.start();
                threadList.add(fetcher);

            } catch (SocketException ex) {
                break;
            }
        }
    }

    public static Map<String, String> pcNameToIPMap() throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(pcNameFile));
        Map<String, String> pcToIP = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            String[] token = line.split(" ");
            pcToIP.put(token[0], token[1]);
        }

        return pcToIP;
    }

    public static Map<String, String> loadPCList(String ipListFile) throws IOException {
        Map<String, String> completePCList = pcNameToIPMap();

        BufferedReader reader = new BufferedReader(new FileReader(ipListFile));
        Map<String, String> pcList = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            String[] pcNames = line.split(" ");
            for (String pcName : pcNames) {
                if (completePCList.containsKey(pcName)) {
                    pcList.put(completePCList.get(pcName), pcName);
                } else {
                    throw new IOException("error while reading " + ipListFile
                            + ": unknown pc name \'" + pcName + "\'");
                }
            }
        }

        return pcList;
    }

    public static Map<String, String> loadCompletePCList() throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(pcNameFile));
        Map<String, String> pcList = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            String[] token = line.split(" ");
            pcList.put(token[1], token[0]);
        }

        return pcList;
    }

    /**
     * For testing purposes, the main method may be used to run the JavaFetcher
     * server directly. It takes three argument, which are the action (either
     * 'fetch' to fetch a file to the clients or 'check' to check the number of
     * available clients), the port on which the server listens and time in
     * millisecond in which clients have a chance to connect to the server.
     *
     * @param args number of the port on which the server listens and time in
     * millisecond in which clients have a chance to connect to the server
     */
    public static void main(String[] args) throws IOException {
        LOG.info("");
        LOG.info("Fetcher is starting on {}...", new Timestamp((new java.util.Date()).getTime()));

        try {
            FetcherServer fetcher = new FetcherServer();

            // arguments
            if (args.length == 0) {
                throw new IllegalArgumentException("at least one input argument is required");
            } else if (args.length > 3) {
                throw new IllegalArgumentException("too many input arguments");
            }

            String action = args[0];
            switch (action) {
                case "check":
                    fetcher.initChecker();
                    break;
                case "list":
                    fetcher.initLister();
                    break;
                case "fetch":
                    ZipFile jarFile = new ZipFile(args[1], "FlowPro.jar", "");
                    if (args.length == 3) {
                        fetcher.initFetcher(jarFile, Integer.parseInt(args[2]));
                    } else {
                        fetcher.initFetcher(jarFile);
                    }
                    break;
                case "multifetch":
                    ZipFiles zipFiles = new ZipFiles(zipDir, args[1]);
                    fetcher.initMultifetcher(zipFiles);
                    break;
                case "add":
                    fetcher.initIPAddition(args[1]);
                    break;
                case "rm":
                    fetcher.initIPRemoval(args[1]);
                    break;
                default:
                    throw new IllegalArgumentException("unknown argument " + action);
            }
            fetcher.start();

        } catch (Exception ex) {
            LOG.error(ex.getMessage());
            System.exit(1);
        }
    }

    /**
     * Prints info about the local certificate.
     *
     * @param socket
     * @throws SSLPeerUnverifiedException
     */
    public void localCetrificateInfo(Socket socket) throws SSLPeerUnverifiedException {
        SSLSession session = ((SSLSocket) socket).getSession();
        Certificate[] cerChain = session.getLocalCertificates();
        LOG.info("the following certificate is used:");
        LOG.info("  ");
        for (Certificate cer : cerChain) {
            LOG.info(((X509Certificate) cer).getSubjectDN().toString());
        }
        LOG.info("  peer host: " + session.getPeerHost());
        LOG.info("  cipher: " + session.getCipherSuite());
        LOG.info("  protocol: " + session.getProtocol());
        LOG.info("  ID: " + new BigInteger(session.getId()));
        LOG.info("  session created in " + session.getCreationTime());
        LOG.info("  session accessed in " + session.getLastAccessedTime());
    }

    private class Interrupter extends Thread {

        private final long timeout;
        private long startTime;

        private Interrupter(long timeout) {
            this.timeout = timeout;
        }

        @Override
        public void run() {
            try {
                startTime = System.currentTimeMillis();
                if (timeout > 0) {
                    Thread.sleep(timeout);
                    serverSocket.close();
                }
            } catch (InterruptedException ex) {
                // nothing needs to be done
            } catch (IOException ex) {
                System.err.println(ex);
                //Logger.getLogger(FetcherServer.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        private long getTimeLeft() {
            return timeout - System.currentTimeMillis() + startTime;
        }
    }
}

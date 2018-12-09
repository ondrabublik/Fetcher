package fetcher;

/**
 *
 * @author ales
 */
public class Actions {
    public static final int CHECK = 0; // server checks number of available clients
    
    // .jar file management    
    public static final int TRANSFER_FILE = 1; // server transfers .jar file to clients and clients execute it    
//    public static final int REMOVE_JAR = 2; // delete specific .jar file
//    public static final int LIST_JAR = 3; // return list of all .jar files
//    public static final int RUN_JAR = 4; // server gives command to execute .jar file to clients
    public static final int TRANSFER_MULTIPLE_FILES = 5;
    
    // manage socket to which the clients are connecting to
    public static final int ADD_SOCKET = 10;
    public static final int REMOVE_SOCKET = 11;
    public static final int LIST_SOCKETS = 12;
    public static final int SET_DELAY = 13;
    
    private Actions() {
        throw new UnsupportedOperationException();
    }
}

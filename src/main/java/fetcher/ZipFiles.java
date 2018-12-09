package fetcher;

import java.io.File;
import java.util.LinkedList;
import java.util.List;

/**
 *
 * @author ales
 */
public class ZipFiles {
    public final List<ZipFile> list;

    public ZipFiles(String zipDirName, String selfIP) {
        list = new LinkedList<>();
        File zipDir = new File(zipDirName);
        if (!zipDir.exists()) {
            throw new RuntimeException("directory " + zipDirName + " does not exists");
        }
        
        for (File file : zipDir.listFiles()) {
            if (file.getName().endsWith(".zip")) {
                String jarFileName = "FlowPro.jar";
                String args = "remote " + selfIP + " " + file.getName();
                list.add(new ZipFile(file, jarFileName, args));
            }
        }
    }
}
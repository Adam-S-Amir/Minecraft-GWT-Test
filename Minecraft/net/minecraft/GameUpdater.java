/*
 * Decompiled with CFR 0.152.
 */
package net.minecraft;

import SevenZip.LzmaAlone;
import java.applet.Applet;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilePermission;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.JarURLConnection;
import java.net.SocketPermission;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.PrivilegedExceptionAction;
import java.security.SecureClassLoader;
import java.security.cert.Certificate;
import java.util.Enumeration;
import java.util.Vector;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Pack200;
import net.minecraft.Launcher;
import net.minecraft.MinecraftUtil;

public class GameUpdater
implements Runnable {
    public static final int STATE_INIT = 1;
    public static final int STATE_DETERMINING_PACKAGES = 2;
    public static final int STATE_CHECKING_CACHE = 3;
    public static final int STATE_DOWNLOADING = 4;
    public static final int STATE_EXTRACTING_PACKAGES = 5;
    public static final int STATE_UPDATING_CLASSPATH = 6;
    public static final int STATE_SWITCHING_APPLET = 7;
    public static final int STATE_INITIALIZE_REAL_APPLET = 8;
    public static final int STATE_START_REAL_APPLET = 9;
    public static final int STATE_DONE = 10;
    public int percentage;
    public int currentSizeDownload;
    public int totalSizeDownload;
    public int currentSizeExtract;
    public int totalSizeExtract;
    protected URL[] urlList;
    private static ClassLoader classLoader;
    protected Thread loaderThread;
    protected Thread animationThread;
    public boolean fatalError;
    public String fatalErrorDescription;
    protected String subtaskMessage = "";
    protected int state = 1;
    protected boolean lzmaSupported = false;
    protected boolean pack200Supported = false;
    protected String[] genericErrorMessage = new String[]{"An error occured while loading the applet.", "Please contact support to resolve this issue.", "<placeholder for error message>"};
    protected boolean certificateRefused;
    protected String[] certificateRefusedMessage = new String[]{"Permissions for Applet Refused.", "Please accept the permissions dialog to allow", "the applet to continue the loading process."};
    protected static boolean natives_loaded;
    public boolean forceUpdate = false;
    public static final String[] gameFiles;
    InputStream[] isp;
    URLConnection urlconnectionp;

    public void init() {
        this.state = 1;
        try {
            Class.forName("LZMA.LzmaInputStream");
            this.lzmaSupported = true;
        }
        catch (Throwable localThrowable) {
            // empty catch block
        }
        try {
            Pack200.class.getSimpleName();
            this.pack200Supported = true;
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private String generateStacktrace(Exception exception) {
        StringWriter result = new StringWriter();
        PrintWriter printWriter = new PrintWriter(result);
        exception.printStackTrace(printWriter);
        return ((Object)result).toString();
    }

    protected String getDescriptionForState() {
        switch (this.state) {
            case 1: {
                return "Initializing loader";
            }
            case 2: {
                return "Determining packages to load";
            }
            case 3: {
                return "Checking cache for existing files";
            }
            case 4: {
                return "Downloading packages";
            }
            case 5: {
                return "Extracting downloaded packages";
            }
            case 6: {
                return "Updating classpath";
            }
            case 7: {
                return "Switching applet";
            }
            case 8: {
                return "Initializing real applet";
            }
            case 9: {
                return "Starting real applet";
            }
            case 10: {
                return "Done loading";
            }
        }
        return "unknown state";
    }

    protected void loadJarURLs() throws Exception {
        this.state = 2;
        this.urlList = new URL[gameFiles.length + 1];
        URL path = new URL("http://s3.amazonaws.com/MinecraftDownload/");
        for (int i = 0; i < gameFiles.length; ++i) {
            this.urlList[i] = new URL(path, gameFiles[i]);
        }
        String osName = System.getProperty("os.name");
        String nativeJar = null;
        if (osName.startsWith("Win")) {
            nativeJar = "windows_natives.jar.lzma";
        } else if (osName.startsWith("Linux")) {
            nativeJar = "linux_natives.jar.lzma";
        } else if (osName.startsWith("Mac")) {
            nativeJar = "macosx_natives.jar.lzma";
        } else if (osName.startsWith("Solaris") || osName.startsWith("SunOS")) {
            nativeJar = "solaris_natives.jar.lzma";
        } else {
            this.fatalErrorOccured("OS (" + osName + ") not supported", null);
        }
        if (nativeJar == null) {
            this.fatalErrorOccured("no lwjgl natives files found", null);
        } else {
            this.urlList[this.urlList.length - 1] = new URL(path, nativeJar);
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void run() {
        this.init();
        this.state = 3;
        this.percentage = 5;
        try {
            this.loadJarURLs();
            String path = (String)AccessController.doPrivileged(new PrivilegedExceptionAction(){

                public Object run() throws Exception {
                    return MinecraftUtil.getWorkingDirectory() + File.separator + "bin" + File.separator;
                }
            });
            File dir = new File(path);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            int before = this.percentage;
            boolean cacheAvailable = false;
            if (this.canPlayOffline()) {
                cacheAvailable = true;
                this.percentage = 90;
            }
            if (this.forceUpdate || !cacheAvailable) {
                if (this.percentage != before) {
                    this.percentage = before;
                }
                System.out.println("Path: " + path);
                this.downloadJars(path);
                this.extractJars(path);
                this.extractNatives(path);
                this.percentage = 90;
            }
            this.updateClassPath(dir);
            this.state = 10;
        }
        catch (AccessControlException ace) {
            this.fatalErrorOccured(ace.getMessage(), ace);
            this.certificateRefused = true;
        }
        catch (Exception e) {
            this.fatalErrorOccured(e.getMessage(), e);
        }
        finally {
            this.loaderThread = null;
        }
    }

    protected void updateClassPath(File dir) throws Exception {
        String path;
        this.state = 6;
        this.percentage = 95;
        URL[] urls = new URL[this.urlList.length];
        for (int i = 0; i < this.urlList.length; ++i) {
            urls[i] = new File(dir, this.getJarName(this.urlList[i])).toURI().toURL();
            System.out.println("URL: " + urls[i]);
        }
        if (classLoader == null) {
            classLoader = new URLClassLoader(urls){

                protected PermissionCollection getPermissions(CodeSource codesource) {
                    PermissionCollection perms = null;
                    try {
                        Method method = SecureClassLoader.class.getDeclaredMethod("getPermissions", CodeSource.class);
                        method.setAccessible(true);
                        perms = (PermissionCollection)method.invoke(this.getClass().getClassLoader(), codesource);
                        String host = "www.minecraft.net";
                        if (host != null && host.length() > 0) {
                            perms.add(new SocketPermission(host, "connect,accept"));
                        } else {
                            codesource.getLocation().getProtocol().equals("file");
                        }
                        perms.add(new FilePermission("<<ALL FILES>>", "read"));
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                    return perms;
                }
            };
        }
        if (!(path = dir.getAbsolutePath()).endsWith(File.separator)) {
            path = path + File.separator;
        }
        this.unloadNatives(path);
        System.setProperty("org.lwjgl.librarypath", path + "natives");
        System.setProperty("net.java.games.input.librarypath", path + "natives");
        natives_loaded = true;
    }

    private void unloadNatives(String nativePath) {
        if (!natives_loaded) {
            return;
        }
        try {
            Field field = ClassLoader.class.getDeclaredField("loadedLibraryNames");
            field.setAccessible(true);
            Vector libs = (Vector)field.get(this.getClass().getClassLoader());
            String path = new File(nativePath).getCanonicalPath();
            for (int i = 0; i < libs.size(); ++i) {
                String s = (String)libs.get(i);
                if (!s.startsWith(path)) continue;
                libs.remove(i);
                --i;
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Applet createApplet() throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        Class<?> appletClass = classLoader.loadClass("net.minecraft.client.MinecraftApplet");
        return (Applet)appletClass.newInstance();
    }

    protected void downloadJars(String path) throws Exception {
        this.state = 4;
        int[] fileSizes = new int[this.urlList.length];
        for (int i = 0; i < this.urlList.length; ++i) {
            System.out.println(this.urlList[i]);
            URLConnection urlconnection = this.urlList[i].openConnection();
            urlconnection.setDefaultUseCaches(false);
            if (urlconnection instanceof HttpURLConnection) {
                ((HttpURLConnection)urlconnection).setRequestMethod("HEAD");
            }
            fileSizes[i] = urlconnection.getContentLength();
            this.totalSizeDownload += fileSizes[i];
        }
        this.percentage = 10;
        int initialPercentage = 10;
        byte[] buffer = new byte[65536];
        for (int i = 0; i < this.urlList.length; ++i) {
            int unsuccessfulAttempts = 0;
            int maxUnsuccessfulAttempts = 3;
            boolean downloadFile = true;
            while (downloadFile) {
                int bufferSize;
                downloadFile = false;
                URLConnection urlconnection = this.urlList[i].openConnection();
                if (urlconnection instanceof HttpURLConnection) {
                    urlconnection.setRequestProperty("Cache-Control", "no-cache");
                    urlconnection.connect();
                }
                String currentFile = this.getFileName(this.urlList[i]);
                InputStream inputstream = this.getJarInputStream(currentFile, urlconnection);
                FileOutputStream fos = new FileOutputStream(path + currentFile);
                long downloadStartTime = System.currentTimeMillis();
                int downloadedAmount = 0;
                int fileSize = 0;
                String downloadSpeedMessage = "";
                while ((bufferSize = inputstream.read(buffer, 0, buffer.length)) != -1) {
                    fos.write(buffer, 0, bufferSize);
                    this.currentSizeDownload += bufferSize;
                    fileSize += bufferSize;
                    this.percentage = initialPercentage + this.currentSizeDownload * 45 / this.totalSizeDownload;
                    this.subtaskMessage = "Retrieving: " + currentFile + " " + this.currentSizeDownload * 100 / this.totalSizeDownload + "%";
                    downloadedAmount += bufferSize;
                    long timeLapse = System.currentTimeMillis() - downloadStartTime;
                    if (timeLapse >= 1000L) {
                        float downloadSpeed = (float)downloadedAmount / (float)timeLapse;
                        downloadSpeed = (float)((int)(downloadSpeed * 100.0f)) / 100.0f;
                        downloadSpeedMessage = " @ " + downloadSpeed + " KB/sec";
                        downloadedAmount = 0;
                        downloadStartTime += 1000L;
                    }
                    this.subtaskMessage = this.subtaskMessage + downloadSpeedMessage;
                }
                inputstream.close();
                fos.close();
                if (!(urlconnection instanceof HttpURLConnection) || fileSize == fileSizes[i] || fileSizes[i] <= 0) continue;
                if (++unsuccessfulAttempts < maxUnsuccessfulAttempts) {
                    downloadFile = true;
                    this.currentSizeDownload -= fileSize;
                    continue;
                }
                throw new Exception("failed to download " + currentFile);
            }
        }
        this.subtaskMessage = "";
    }

    protected InputStream getJarInputStream(String currentFile, URLConnection urlconnection) throws Exception {
        InputStream[] is = new InputStream[1];
        this.isp = is;
        this.urlconnectionp = urlconnection;
        for (int j = 0; j < 3 && is[0] == null; ++j) {
            Thread t = new Thread(){

                public void run() {
                    try {
                        GameUpdater.this.isp[0] = GameUpdater.this.urlconnectionp.getInputStream();
                    }
                    catch (Exception exception) {
                        // empty catch block
                    }
                }
            };
            t.setName("JarInputStreamThread");
            t.start();
            int iterationCount = 0;
            while (is[0] == null && iterationCount++ < 5) {
                try {
                    t.join(1000L);
                }
                catch (InterruptedException localInterruptedException) {}
            }
            if (is[0] != null) continue;
            try {
                t.interrupt();
                t.join();
                continue;
            }
            catch (InterruptedException localInterruptedException1) {
                // empty catch block
            }
        }
        if (is[0] == null) {
            if (currentFile.equals("minecraft.jar")) {
                throw new Exception("Unable to download " + currentFile);
            }
            throw new Exception("Unable to download " + currentFile);
        }
        return is[0];
    }

    protected void extractLZMA(String in, String out) throws Exception {
        File f = new File(in);
        File fout = new File(out);
        LzmaAlone.decompress(f, fout);
        f.delete();
    }

    protected void extractPack(String in, String out) throws Exception {
        File f = new File(in);
        FileOutputStream fostream = new FileOutputStream(out);
        JarOutputStream jostream = new JarOutputStream(fostream);
        Pack200.Unpacker unpacker = Pack200.newUnpacker();
        unpacker.unpack(f, jostream);
        jostream.close();
        f.delete();
    }

    protected void extractJars(String path) throws Exception {
        this.state = 5;
        float increment = 10.0f / (float)this.urlList.length;
        for (int i = 0; i < this.urlList.length; ++i) {
            this.percentage = 55 + (int)(increment * (float)(i + 1));
            String filename = this.getFileName(this.urlList[i]);
            if (filename.endsWith(".pack.lzma")) {
                this.subtaskMessage = "Extracting: " + filename + " to " + filename.replaceAll(".lzma", "");
                this.extractLZMA(path + filename, path + filename.replaceAll(".lzma", ""));
                this.subtaskMessage = "Extracting: " + filename.replaceAll(".lzma", "") + " to " + filename.replaceAll(".pack.lzma", "");
                this.extractPack(path + filename.replaceAll(".lzma", ""), path + filename.replaceAll(".pack.lzma", ""));
                continue;
            }
            if (filename.endsWith(".pack")) {
                this.subtaskMessage = "Extracting: " + filename + " to " + filename.replace(".pack", "");
                this.extractPack(path + filename, path + filename.replace(".pack", ""));
                continue;
            }
            if (!filename.endsWith(".lzma")) continue;
            this.subtaskMessage = "Extracting: " + filename + " to " + filename.replace(".lzma", "");
            this.extractLZMA(path + filename, path + filename.replace(".lzma", ""));
        }
    }

    protected void extractNatives(String path) throws Exception {
        JarEntry entry;
        File nativeFolder;
        this.state = 5;
        int initialPercentage = this.percentage;
        String nativeJar = this.getJarName(this.urlList[this.urlList.length - 1]);
        Certificate[] certificate = Launcher.class.getProtectionDomain().getCodeSource().getCertificates();
        if (certificate == null) {
            URL location = Launcher.class.getProtectionDomain().getCodeSource().getLocation();
            JarURLConnection jurl = (JarURLConnection)new URL("jar:" + location.toString() + "!/net/minecraft/Launcher.class").openConnection();
            jurl.setDefaultUseCaches(true);
            try {
                certificate = jurl.getCertificates();
            }
            catch (Exception localException) {
                // empty catch block
            }
        }
        if (!(nativeFolder = new File(path + "natives")).exists()) {
            nativeFolder.mkdir();
        }
        JarFile jarFile = new JarFile(path + nativeJar, true);
        Enumeration<JarEntry> entities = jarFile.entries();
        this.totalSizeExtract = 0;
        while (entities.hasMoreElements()) {
            entry = entities.nextElement();
            if (entry.isDirectory() || entry.getName().indexOf(47) != -1) continue;
            this.totalSizeExtract = (int)((long)this.totalSizeExtract + entry.getSize());
        }
        this.currentSizeExtract = 0;
        entities = jarFile.entries();
        while (entities.hasMoreElements()) {
            int bufferSize;
            File f;
            entry = entities.nextElement();
            if (entry.isDirectory() || entry.getName().indexOf(47) != -1 || (f = new File(path + "natives" + File.separator + entry.getName())).exists() && !f.delete()) continue;
            InputStream in = jarFile.getInputStream(jarFile.getEntry(entry.getName()));
            FileOutputStream out = new FileOutputStream(path + "natives" + File.separator + entry.getName());
            byte[] buffer = new byte[65536];
            while ((bufferSize = in.read(buffer, 0, buffer.length)) != -1) {
                ((OutputStream)out).write(buffer, 0, bufferSize);
                this.currentSizeExtract += bufferSize;
                this.percentage = initialPercentage + this.currentSizeExtract * 20 / this.totalSizeExtract;
                this.subtaskMessage = "Extracting: " + entry.getName() + " " + this.currentSizeExtract * 100 / this.totalSizeExtract + "%";
            }
            GameUpdater.validateCertificateChain(certificate, entry.getCertificates());
            in.close();
            ((OutputStream)out).close();
        }
        this.subtaskMessage = "";
        jarFile.close();
        File f = new File(path + nativeJar);
        f.delete();
    }

    protected static void validateCertificateChain(Certificate[] ownCerts, Certificate[] native_certs) throws Exception {
        if (ownCerts == null) {
            return;
        }
        if (native_certs == null) {
            throw new Exception("Unable to validate certificate chain. Native entry did not have a certificate chain at all");
        }
        if (ownCerts.length != native_certs.length) {
            throw new Exception("Unable to validate certificate chain. Chain differs in length [" + ownCerts.length + " vs " + native_certs.length + "]");
        }
        for (int i = 0; i < ownCerts.length; ++i) {
            if (ownCerts[i].equals(native_certs[i])) continue;
            throw new Exception("Certificate mismatch: " + ownCerts[i] + " != " + native_certs[i]);
        }
    }

    protected String getJarName(URL url) {
        String fileName = url.getFile();
        if (fileName.contains("?")) {
            fileName = fileName.substring(0, fileName.indexOf("?"));
        }
        if (fileName.endsWith(".pack.lzma")) {
            fileName = fileName.replaceAll(".pack.lzma", "");
        } else if (fileName.endsWith(".pack")) {
            fileName = fileName.replaceAll(".pack", "");
        } else if (fileName.endsWith(".lzma")) {
            fileName = fileName.replaceAll(".lzma", "");
        }
        return fileName.substring(fileName.lastIndexOf(47) + 1);
    }

    protected String getFileName(URL url) {
        String fileName = url.getFile();
        if (fileName.contains("?")) {
            fileName = fileName.substring(0, fileName.indexOf("?"));
        }
        return fileName.substring(fileName.lastIndexOf(47) + 1);
    }

    protected void fatalErrorOccured(String error, Exception e) {
        e.printStackTrace();
        this.fatalError = true;
        this.fatalErrorDescription = "Fatal error occured (" + this.state + "): " + error;
        System.out.println(this.fatalErrorDescription);
        if (e != null) {
            System.out.println(this.generateStacktrace(e));
        }
    }

    public boolean canPlayOffline() {
        if (!MinecraftUtil.getBinFolder().exists() || !MinecraftUtil.getBinFolder().isDirectory()) {
            return false;
        }
        if (!MinecraftUtil.getNativesFolder().exists() || !MinecraftUtil.getNativesFolder().isDirectory()) {
            return false;
        }
        if (MinecraftUtil.getBinFolder().list().length < gameFiles.length + 1) {
            return false;
        }
        if (MinecraftUtil.getNativesFolder().list().length < 1) {
            return false;
        }
        String[] bins = MinecraftUtil.getBinFolder().list();
        for (String necessary : gameFiles) {
            boolean isThere = false;
            for (String found : bins) {
                if (!necessary.equalsIgnoreCase(found)) continue;
                isThere = true;
                break;
            }
            if (isThere) continue;
            return false;
        }
        return true;
    }

    static {
        natives_loaded = false;
        gameFiles = new String[]{"lwjgl.jar", "jinput.jar", "lwjgl_util.jar", "minecraft.jar"};
    }
}

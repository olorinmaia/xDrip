package com.eveningoutpost.dexdrip.webservices;

import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;

import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.UserError;
import com.eveningoutpost.dexdrip.R;
import com.eveningoutpost.dexdrip.UtilityModels.Constants;
import com.eveningoutpost.dexdrip.UtilityModels.Pref;
import com.eveningoutpost.dexdrip.dagger.Injectors;
import com.eveningoutpost.dexdrip.xdrip;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;

import javax.inject.Inject;
import javax.inject.Named;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;


import dagger.Lazy;

/**
 * Created by jamorham on 06/01/2018.
 * <p>
 * Provides a webservice on localhost port 17580 respond to incoming requests either for data or
 * to push events in.
 * <p>
 * Also provides a https webservice on localhost port 17581 which presents the key / certificate
 * contained in the raw resource localhost_cert.bks - More work is likely needed for a chain
 * which passes validation but this provides some structure for that or similar services.
 * <p>
 * <p>
 * Designed for watches which support only a http interface
 * <p>
 * base service adapted from android reference documentation
 */

// TODO megastatus for engineering mode

public class XdripWebService implements Runnable {

    private static final String TAG = "xDripWebService";
    private static volatile XdripWebService instance = null;
    private static volatile XdripWebService ssl_instance = null;

    /**
     * The port number we listen to
     */
    private final int mPort;
    private final boolean mSSL;

    /**
     * True if the server is running.
     */
    private boolean mIsRunning;

    /**
     * The {@link java.net.ServerSocket} that we listen to.
     */
    private ServerSocket mServerSocket;

    @Inject
    @Named("RouteFinder")
    Lazy<RouteFinder> routeFinder;

    /**
     * WebServer constructor.
     */
    private XdripWebService(int port, boolean use_ssl) {
        this.mPort = port;
        this.mSSL = use_ssl;
        Injectors.getWebServiceComponent().inject(this);
    }

    // start the service if needed, shut it down if not
    public static void immortality() {
        if (Pref.getBooleanDefaultFalse("xdrip_webservice")) {
            easyStart();
        } else {
            if (instance != null) {
                easyStop();
            }
        }
    }

    // robustly shut down and erase the instance
    private static synchronized void easyStop() {
        try {
            UserError.Log.d(TAG, "running easyStop()");
            instance.stop();
            instance = null;
            ssl_instance.stop();
            ssl_instance = null;
        } catch (NullPointerException e) {
            // concurrency issue
        }
    }

    // start up if needed
    private static synchronized void easyStart() {
        if (instance == null) {
            UserError.Log.d(TAG, "easyStart() Starting new instance");
            instance = new XdripWebService(17580, false);
            ssl_instance = new XdripWebService(17581, true);
        }
        instance.startIfNotRunning();
        ssl_instance.startIfNotRunning();
    }

    // start thread if needed
    private void startIfNotRunning() {
        if (!mIsRunning) {
            UserError.Log.d(TAG, "Not running so starting");
            start();
        } else {
            // UserError.Log.d(TAG, "Already running");
        }
    }

    /**
     * This method starts the web server listening to the specified port.
     */
    public void start() {
        mIsRunning = true;
        new Thread(this).start();
    }

    /**
     * This method stops the web server
     */
    public synchronized void stop() {
        try {
            mIsRunning = false;
            if (null != mServerSocket) {
                mServerSocket.close();
                mServerSocket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing the server socket.", e);
        }
    }

    public int getPort() {
        return mPort;
    }


    @Override
    public void run() {
        try {
            if (mSSL) {
                // SSL type
                UserError.Log.d(TAG, "Attempting to initialize SSL");
                final SSLServerSocketFactory ssocketFactory = SSLServerSocketHelper.makeSSLSocketFactory(
                        new BufferedInputStream(xdrip.getAppContext().getResources().openRawResource(R.raw.localhost_cert)),
                        "password".toCharArray());
                mServerSocket = ssocketFactory.createServerSocket(mPort, 1, InetAddress.getByName("127.0.0.1"));
            } else {
                // Non-SSL type
                mServerSocket = new ServerSocket(mPort, 1, InetAddress.getByName("127.0.0.1"));
            }
            while (mIsRunning) {
                final Socket socket = mServerSocket.accept();
                handle(socket);
                socket.close();
            }
        } catch (SocketException e) {
            // The server was stopped; ignore.
        } catch (IOException e) {
            Log.e(TAG, "Web server error.", e);
        }
    }

    /**
     * Respond to a request from a client.
     *
     * @param socket The client socket.
     * @throws IOException
     */
    private void handle(Socket socket) throws IOException {
        final PowerManager.WakeLock wl = JoH.getWakeLock("webservice-handler", 20000);
        BufferedReader reader = null;
        PrintStream output = null;
        try {
            socket.setSoTimeout((int) (Constants.SECOND_IN_MS * 10));
            try {
                if (socket instanceof SSLSocket) {
                    // if ssl
                    UserError.Log.d(TAG, "Attempting SSL handshake");
                    final SSLSocket sslSocket = (SSLSocket) socket;

                    sslSocket.startHandshake();
                    final SSLSession sslSession = sslSocket.getSession();

                    UserError.Log.d(TAG, "SSLSession :");
                    UserError.Log.d(TAG, "\tProtocol : " + sslSession.getProtocol());
                    UserError.Log.d(TAG, "\tCipher suite : " + sslSession.getCipherSuite());
                }
            } catch (SSLHandshakeException e) {
                UserError.Log.e(TAG, "SSL ERROR: " + e.toString());
                return;
            } catch (Exception e) {
                UserError.Log.e(TAG, "SSL unknown error: " + e);
                return;
            }


            String route = null;

            // Read HTTP headers and parse out the route.
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String line;
            while (!TextUtils.isEmpty(line = reader.readLine())) {
                if (line.startsWith("GET /")) {
                    int start = line.indexOf('/') + 1;
                    int end = line.indexOf(' ', start);
                    route = URLDecoder.decode(line.substring(start, end), "UTF-8");
                    UserError.Log.d(TAG, "Received request for: " + route);
                    break;
                }
            }

            // Output stream that we send the response to
            output = new PrintStream(socket.getOutputStream());

            // Prepare the content to send.
            if (null == route) {
                writeServerError(output);
                return;
            }

            final WebResponse response = routeFinder.get().handleRoute(route);

            // if we didn't manage to generate a response
            if (response == null) {
                writeServerError(output);
                return;
            }

            // if the response bytes are null
            if (response.bytes == null) {
                writeServerError(output);
                return;
            }
            // Send out the content.
            output.println("HTTP/1.0 " + response.resultCode + " OK");
            output.println("Content-Type: " + response.mimeType);
            output.println("Content-Length: " + response.bytes.length);
            output.println();
            output.write(response.bytes);
            output.flush();

            UserError.Log.d(TAG, "Sent response: " + response.bytes.length + " bytes, code: " + response.resultCode + " mimetype: " + response.mimeType);

        } catch (SocketTimeoutException e) {
            UserError.Log.d(TAG, "Got socket timeout: " + e);

        } finally {
            if (output != null) {
                output.close();
            }
            if (reader != null) {
                reader.close();
            }
            JoH.releaseWakeLock(wl);
        }
    }

    /**
     * Writes a server error response (HTTP/1.0 500) to the given output stream.
     *
     * @param output The output stream.
     */
    private void writeServerError(PrintStream output) {
        output.println("HTTP/1.0 500 Internal Server Error");
        output.flush();
        UserError.Log.e(TAG, "Internal server error reply");
    }

}


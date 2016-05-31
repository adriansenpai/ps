/*
 * Copyright (c) 2016, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psiphonlibrary;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.net.VpnService.Builder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;

import com.psiphon3.R;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.psiphon.PsiphonTunnel;

public class TunnelManager implements PsiphonTunnel.HostService, MyLog.ILogger {

    // Android IPC messages

    // Client -> Service
    public static final int MSG_REGISTER = 0;
    public static final int MSG_UNREGISTER = 1;
    public static final int MSG_STOP_VPN_SERVICE = 2;

    // Service -> Client
    public static final int MSG_REGISTER_RESPONSE = 3;
    public static final int MSG_KNOWN_SERVER_REGIONS = 4;
    public static final int MSG_TUNNEL_STARTING = 5;
    public static final int MSG_TUNNEL_STOPPING = 6;
    public static final int MSG_TUNNEL_CONNECTION_STATE = 7;
    public static final int MSG_LOGS = 8;

    public static final String INTENT_ACTION_HANDSHAKE = "com.psiphon3.psiphonlibrary.TunnelManager.HANDSHAKE";

    // Service -> Client bundle parameter names
    public static final String DATA_TUNNEL_STATE_AVAILABLE_EGRESS_REGIONS = "availableEgressRegions";
    public static final String DATA_TUNNEL_STATE_IS_CONNECTED = "isConnected";
    public static final String DATA_TUNNEL_STATE_LISTENING_LOCAL_SOCKS_PROXY_PORT = "listeningLocalSocksProxyPort";
    public static final String DATA_TUNNEL_STATE_LISTENING_LOCAL_HTTP_PROXY_PORT = "listeningLocalHttpProxyPort";
    public static final String DATA_TUNNEL_STATE_CLIENT_REGION = "clientRegion";
    public static final String DATA_TUNNEL_STATE_HOME_PAGES = "homePages";

    // Extras in handshake intent
    public static final String DATA_HANDSHAKE_IS_RECONNECT = "isReconnect";

    // Extras in start service intent (Client -> Service)
    public static final String DATA_TUNNEL_CONFIG_HANDSHAKE_PENDING_INTENT = "tunnelConfigHandshakePendingIntent";
    public static final String DATA_TUNNEL_CONFIG_NOTIFICATION_PENDING_INTENT = "tunnelConfigNotificationPendingIntent";
    public static final String DATA_TUNNEL_CONFIG_WHOLE_DEVICE = "tunnelConfigWholeDevice";
    public static final String DATA_TUNNEL_CONFIG_EGRESS_REGION = "tunnelConfigEgressRegion";
    public static final String DATA_TUNNEL_CONFIG_DISABLE_TIMEOUTS = "tunnelConfigDisableTimeouts";
    public static final String DATA_TUNNEL_CONFIG_UPSTREAM_PROXY_CONFIG = "tunnelConfigUpstreamProxyUrl";
    public static final String DATA_LOGS = "logs";

    // Tunnel config, received from the client.
    public static class Config {
        PendingIntent handshakePendingIntent = null;
        PendingIntent notificationPendingIntent = null;
        boolean wholeDevice = false;
        String egressRegion = PsiphonConstants.REGION_CODE_ANY;
        boolean disableTimeouts = false;
        String upstreamProxyURL;
    }

    private Config m_tunnelConfig = new Config();

    // Shared tunnel state, sent to the client in the HANDSHAKE
    // intent and various state-related Messages.
    public static class State {
        ArrayList<String> availableEgressRegions = new ArrayList<>();
        boolean isConnected = false;
        int listeningLocalSocksProxyPort = 0;
        int listeningLocalHttpProxyPort = 0;
        String clientRegion;
        ArrayList<String> homePages = new ArrayList<>();
    }

    private State m_tunnelState = new State();

    private NotificationManager mNotificationManager = null;
    private NotificationCompat.Builder mNotificationBuilder = null;
    private Service m_parentService = null;
    private boolean m_serviceDestroyed = false;
    private boolean m_firstStart = true;
    private boolean m_signalledStop = false;
    private CountDownLatch m_tunnelThreadStopSignal;
    private Thread m_tunnelThread;
    private AtomicBoolean m_isReconnect;
    private AtomicBoolean m_isStopping;
    private PsiphonTunnel m_tunnel = null;
    private String m_lastUpstreamProxyErrorMessage;
    private Handler m_Handler = new Handler();
    private GoogleSafetyNetApiWrapper m_safetyNetwrapper;

    public TunnelManager(Service parentService) {
        m_parentService = parentService;
        m_isReconnect = new AtomicBoolean(false);
        m_isStopping = new AtomicBoolean(false);
        m_tunnel = PsiphonTunnel.newPsiphonTunnel(this);
    }

    // Implementation of android.app.Service.onStartCommand
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mNotificationManager == null) {
            mNotificationManager = (NotificationManager) m_parentService.getSystemService(Context.NOTIFICATION_SERVICE);
        }

        if (mNotificationBuilder == null) {
            mNotificationBuilder = new NotificationCompat.Builder(m_parentService);
        }

        if (m_firstStart && intent != null) {
            getTunnelConfig(intent);
            m_parentService.startForeground(R.string.psiphon_service_notification_id, this.createNotification(false));
            MyLog.v(R.string.client_version, MyLog.Sensitivity.NOT_SENSITIVE, EmbeddedValues.CLIENT_VERSION);
            m_firstStart = false;
            m_tunnelThreadStopSignal = new CountDownLatch(1);
            m_tunnelThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runTunnel();
                }
            });
            m_tunnelThread.start();
        }

        return Service.START_REDELIVER_INTENT;
    }

    public void onCreate() {
        MyLog.setLogger(this);
    }

    // Implementation of android.app.Service.onDestroy
    public void onDestroy() {
        m_serviceDestroyed = true;

        if (m_tunnelThread == null) {
            return;
        }

        stopAndWaitForTunnel();

        MyLog.unsetLogger();
    }

    public void onRevoke() {
        MyLog.w(R.string.vpn_service_revoked, MyLog.Sensitivity.NOT_SENSITIVE);

        stopAndWaitForTunnel();

        // Stop service
        m_parentService.stopForeground(true);
        m_parentService.stopSelf();
    }

    private void stopAndWaitForTunnel() {
        // signalStopService could have been called, but in case is was not, call here.
        // If signalStopService was not already called, the join may block the calling
        // thread for some time.
        signalStopService();

        try {
            m_tunnelThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        m_tunnelThreadStopSignal = null;
        m_tunnelThread = null;
    }

    // signalStopService signals the runTunnel thread to stop. The thread will
    // self-stop the service. This is the preferred method for stopping the
    // Psiphon tunnel service:
    // 1. VpnService doesn't respond to stopService calls
    // 2. The UI will not block while waiting for stopService to return
    public void signalStopService() {
        m_signalledStop = true;
        if (m_tunnelThreadStopSignal != null) {
            m_tunnelThreadStopSignal.countDown();
        }

        if (m_safetyNetwrapper != null) {
            m_safetyNetwrapper.disconnect();
        }
    }

    public boolean signalledStop() {
        return m_signalledStop;
    }

    private void getTunnelConfig(Intent intent) {
        m_tunnelConfig.handshakePendingIntent = intent.getParcelableExtra(
                TunnelManager.DATA_TUNNEL_CONFIG_HANDSHAKE_PENDING_INTENT);

        m_tunnelConfig.notificationPendingIntent = intent.getParcelableExtra(
                TunnelManager.DATA_TUNNEL_CONFIG_NOTIFICATION_PENDING_INTENT);

        m_tunnelConfig.wholeDevice = intent.getBooleanExtra(
                TunnelManager.DATA_TUNNEL_CONFIG_WHOLE_DEVICE, false);

        m_tunnelConfig.egressRegion = intent.getStringExtra(
                TunnelManager.DATA_TUNNEL_CONFIG_EGRESS_REGION);

        m_tunnelConfig.disableTimeouts = intent.getBooleanExtra(
                TunnelManager.DATA_TUNNEL_CONFIG_DISABLE_TIMEOUTS, false);

        m_tunnelConfig.upstreamProxyURL = intent.getStringExtra(
                TunnelManager.DATA_TUNNEL_CONFIG_UPSTREAM_PROXY_CONFIG);
    }

    private Notification createNotification(boolean alert) {
        int contentTextID;
        int iconID;
        CharSequence ticker = null;

        if (m_tunnelState.isConnected) {
            if (m_tunnelConfig.wholeDevice) {
                contentTextID = R.string.psiphon_running_whole_device;
            } else {
                contentTextID = R.string.psiphon_running_browser_only;
            }
            iconID = R.drawable.notification_icon_connected;
        } else {
            contentTextID = R.string.psiphon_service_notification_message_connecting;
            ticker = m_parentService.getText(R.string.psiphon_service_notification_message_connecting);
            iconID = R.drawable.notification_icon_connecting_animation;
        }

        mNotificationBuilder
                .setSmallIcon(iconID)
                .setContentTitle(m_parentService.getText(R.string.app_name))
                .setContentText(m_parentService.getText(contentTextID))
                .setTicker(ticker)
                .setContentIntent(m_tunnelConfig.notificationPendingIntent);

        Notification notification = mNotificationBuilder.build();

        if (alert) {
            if (PreferenceManager.getDefaultSharedPreferences(m_parentService).getBoolean(
                    m_parentService.getString(R.string.preferenceNotificationsWithSound), false)) {
                notification.defaults |= Notification.DEFAULT_SOUND;
            }
            if (PreferenceManager.getDefaultSharedPreferences(m_parentService).getBoolean(
                    m_parentService.getString(R.string.preferenceNotificationsWithVibrate), false)) {
                notification.defaults |= Notification.DEFAULT_VIBRATE;
            }
        }

        return notification;
    }

    private void setIsConnected(boolean isConnected) {
        boolean alert = (isConnected != m_tunnelState.isConnected);

        m_tunnelState.isConnected = isConnected;

        // Don't update notification to CONNECTING, etc., when a stop was commanded.
        if (!m_serviceDestroyed && !m_isStopping.get()) {
            if (mNotificationManager != null) {
                mNotificationManager.notify(
                        R.string.psiphon_service_notification_id,
                        createNotification(alert));
            }
        }
    }

    public IBinder onBind(Intent intent) {
        return m_incomingMessenger.getBinder();
    }

    private final Messenger m_incomingMessenger = new Messenger(
            new IncomingMessageHandler(this));
    private Messenger m_outgoingMessenger = null;

    private static class IncomingMessageHandler extends Handler {
        private final WeakReference<TunnelManager> mTunnelManager;

        IncomingMessageHandler(TunnelManager manager) {
            mTunnelManager = new WeakReference<>(manager);
        }

        @Override
        public void handleMessage(Message msg)
        {
            TunnelManager manager = mTunnelManager.get();
            switch (msg.what)
            {
                case TunnelManager.MSG_REGISTER:
                    if (manager != null) {
                        manager.m_outgoingMessenger = msg.replyTo;
                        manager.sendClientMessage(MSG_REGISTER_RESPONSE, manager.getTunnelStateBundle());
                    }
                    break;

                case TunnelManager.MSG_UNREGISTER:
                    if (manager != null) {
                        manager.m_outgoingMessenger = null;
                    }
                    break;

                case TunnelManager.MSG_STOP_VPN_SERVICE:
                    if (manager != null) {
                        manager.signalStopService();
                    }
                    break;

                default:
                    super.handleMessage(msg);
            }
        }
    }

    private void sendClientMessage(int what, Bundle data) {
        if (m_incomingMessenger == null || m_outgoingMessenger == null) {
            return;
        }
        try {
            Message msg = Message.obtain(null, what);
            msg.replyTo = m_incomingMessenger;
            if (data != null) {
                msg.setData(data);
            }
            m_outgoingMessenger.send(msg);
        } catch (RemoteException e) {
            // TODO-TUNNEL-CORE: ?
            // NOTE: potential stack overflow since MyLog invokes
            // statusEntryAdded which invokes sendClientMessage
            // MyLog.g("sendClientMessage failed: %s", e.getMessage());
        }
    }

    private void sendHandshakeIntent(boolean isReconnect) {
        Intent fillInExtras = new Intent();
        fillInExtras.putExtra(DATA_HANDSHAKE_IS_RECONNECT, isReconnect);
        fillInExtras.putExtras(getTunnelStateBundle());
        try {
            m_tunnelConfig.handshakePendingIntent.send(
                    m_parentService, 0, fillInExtras);
        } catch (PendingIntent.CanceledException e) {
            MyLog.g("sendHandshakeIntent failed: %s", e.getMessage());
        }
    }

    private Bundle getTunnelStateBundle() {
        Bundle data = new Bundle();
        data.putStringArrayList(DATA_TUNNEL_STATE_AVAILABLE_EGRESS_REGIONS, m_tunnelState.availableEgressRegions);
        data.putBoolean(DATA_TUNNEL_STATE_IS_CONNECTED, m_tunnelState.isConnected);
        data.putInt(DATA_TUNNEL_STATE_LISTENING_LOCAL_SOCKS_PROXY_PORT, m_tunnelState.listeningLocalSocksProxyPort);
        data.putInt(DATA_TUNNEL_STATE_LISTENING_LOCAL_HTTP_PROXY_PORT, m_tunnelState.listeningLocalHttpProxyPort);
        data.putString(DATA_TUNNEL_STATE_CLIENT_REGION, m_tunnelState.clientRegion);
        data.putStringArrayList(DATA_TUNNEL_STATE_HOME_PAGES, m_tunnelState.homePages);
        return data;
    }

    private final static String LEGACY_SERVER_ENTRY_FILENAME = "psiphon_server_entries.json";
    private final static int MAX_LEGACY_SERVER_ENTRIES = 100;

    public static String getServerEntries(Context context) {
        StringBuilder list = new StringBuilder();

        for (String encodedServerEntry : EmbeddedValues.EMBEDDED_SERVER_LIST) {
            list.append(encodedServerEntry);
            list.append("\n");
        }

        // Import legacy server entries
        try {
            FileInputStream file = context.openFileInput(LEGACY_SERVER_ENTRY_FILENAME);
            BufferedReader reader = new BufferedReader(new InputStreamReader(file));
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
            file.close();
            JSONObject obj = new JSONObject(json.toString());
            JSONArray jsonServerEntries = obj.getJSONArray("serverEntries");

            // MAX_LEGACY_SERVER_ENTRIES ensures the list we pass through to tunnel-core
            // is unlikely to trigger an OutOfMemoryError
            for (int i = 0; i < jsonServerEntries.length() && i < MAX_LEGACY_SERVER_ENTRIES; i++) {
                list.append(jsonServerEntries.getString(i));
                list.append("\n");
            }

            // Don't need to repeat the import again
            context.deleteFile(LEGACY_SERVER_ENTRY_FILENAME);
        } catch (FileNotFoundException e) {
            // pass
        } catch (IOException | JSONException | OutOfMemoryError e) {
            MyLog.g("prepareServerEntries failed: %s", e.getMessage());
        }

        return list.toString();
    }

    private void runTunnel() {

        Utils.initializeSecureRandom();

        m_isStopping.set(false);
        m_isReconnect.set(false);

        // Notify if an upgrade has already been downloaded and is waiting for install
        UpgradeManager.UpgradeInstaller.notifyUpgrade(m_parentService);

        sendClientMessage(MSG_TUNNEL_STARTING, null);

        MyLog.v(R.string.current_network_type, MyLog.Sensitivity.NOT_SENSITIVE, Utils.getNetworkTypeName(m_parentService));

        MyLog.v(R.string.starting_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);

        m_tunnelState.homePages.clear();

        DataTransferStats.getDataTransferStats().startSession();

        boolean runVpn =
                m_tunnelConfig.wholeDevice &&
                        Utils.hasVpnService() &&
                        // Guard against trying to start WDM mode when the global option flips while starting a TunnelService
                        (m_parentService instanceof TunnelVpnService);

        try {
            if (runVpn) {
                if (!m_tunnel.startRouting()) {
                    throw new PsiphonTunnel.Exception("application is not prepared or revoked");
                }
                MyLog.v(R.string.vpn_service_running, MyLog.Sensitivity.NOT_SENSITIVE);
            }

            m_tunnel.startTunneling(getServerEntries(m_parentService));

            try {
                m_tunnelThreadStopSignal.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            m_isStopping.set(true);

        } catch (PsiphonTunnel.Exception e) {
            MyLog.e(R.string.start_tunnel_failed, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } finally {

            MyLog.v(R.string.stopping_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);

            sendClientMessage(MSG_TUNNEL_STOPPING, null);

            m_tunnel.stop();

            DataTransferStats.getDataTransferStats().stop();

            MyLog.v(R.string.stopped_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);

            // Stop service
            m_parentService.stopForeground(true);
            m_parentService.stopSelf();
        }
    }

    @Override
    public String getAppName() {
        return m_parentService.getString(R.string.app_name);
    }

    @Override
    public Context getContext() {
        return m_parentService;
    }

    @Override
    public VpnService getVpnService() {
        return ((TunnelVpnService) m_parentService);
    }

    @Override
    public Builder newVpnServiceBuilder() {
        return ((TunnelVpnService) m_parentService).newBuilder();
    }

    /**
     * Create a tunnel-core config suitable for different tunnel types (i.e., the main Psiphon app
     * tunnel and the UpgradeChecker temp tunnel).
     *
     * @param context
     * @param tempTunnelName       null if not a temporary tunnel. If set, must be a valid to use in file path.
     * @param clientPlatformPrefix null if not applicable (i.e., for main Psiphon app); should be provided
     *                             for temp tunnels. Will be prepended to standard client platform value.
     * @return JSON string of config. null on error.
     */
    public static String buildTunnelCoreConfig(
            Context context,
            Config tunnelConfig,
            String tempTunnelName,
            String clientPlatformPrefix) {
        boolean temporaryTunnel = tempTunnelName != null && !tempTunnelName.isEmpty();

        JSONObject json = new JSONObject();

        try {
            String clientPlatform = PsiphonConstants.PLATFORM;
            if (clientPlatformPrefix != null && !clientPlatformPrefix.isEmpty()) {
                clientPlatform += clientPlatformPrefix;
            }
            json.put("ClientPlatform", clientPlatform);

            json.put("ClientVersion", EmbeddedValues.CLIENT_VERSION);

            if (UpgradeChecker.upgradeCheckNeeded(context)) {
                Uri upgradeDownloadUrl = Uri.parse(EmbeddedValues.UPGRADE_URL);
                upgradeDownloadUrl = upgradeDownloadUrl.buildUpon()
                        .appendQueryParameter("tunnel_name", tempTunnelName == null ? "main" : tempTunnelName)
                        .appendQueryParameter("client_version", EmbeddedValues.CLIENT_VERSION)
                        .appendQueryParameter("client_platform", clientPlatform)
                        .build();
                json.put("UpgradeDownloadUrl", upgradeDownloadUrl.toString());

                json.put("UpgradeDownloadClientVersionHeader", "x-amz-meta-psiphon-client-version");

                json.put("UpgradeDownloadFilename",
                        new UpgradeManager.DownloadedUpgradeFile(context).getFullPath());
            }

            json.put("PropagationChannelId", EmbeddedValues.PROPAGATION_CHANNEL_ID);

            json.put("SponsorId", EmbeddedValues.SPONSOR_ID);

            json.put("RemoteServerListUrl", EmbeddedValues.REMOTE_SERVER_LIST_URL);

            json.put("RemoteServerListSignaturePublicKey", EmbeddedValues.REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY);

            json.put("UpstreamProxyUrl", tunnelConfig.upstreamProxyURL);

            json.put("EmitDiagnosticNotices", true);

            // If this is a temporary tunnel (like for UpgradeChecker) we need to override some of
            // the implicit config values.
            if (temporaryTunnel) {
                File tempTunnelDir = new File(context.getFilesDir(), tempTunnelName);
                if (!tempTunnelDir.exists()
                        && !tempTunnelDir.mkdirs()) {
                    // Failed to create DB directory
                    return null;
                }

                // On Android, these directories must be set to the app private storage area.
                // The Psiphon library won't be able to use its current working directory
                // and the standard temporary directories do not exist.
                json.put("DataStoreDirectory", tempTunnelDir.getAbsolutePath());

                File remoteServerListDownload = new File(tempTunnelDir, "remote_server_list");
                json.put("RemoteServerListDownloadFilename", remoteServerListDownload.getAbsolutePath());

                // This number is an arbitrary guess at what might be the "best" balance between
                // wake-lock-battery-burning and successful upgrade downloading.
                // Note that the fall-back untunneled upgrade download doesn't start for 30 secs,
                // so we should be waiting longer than that.
                json.put("EstablishTunnelTimeoutSeconds", 300);

                json.put("TunnelWholeDevice", 0);

                json.put("LocalHttpProxyPort", 0);
                json.put("LocalSocksProxyPort", 0);

                json.put("EgressRegion", "");
            } else {
                // TODO-TUNNEL-CORE: configure local proxy ports
                json.put("LocalHttpProxyPort", 0);
                json.put("LocalSocksProxyPort", 0);

                String egressRegion = tunnelConfig.egressRegion;
                MyLog.g("EgressRegion", "regionCode", egressRegion);
                json.put("EgressRegion", egressRegion);
            }

            if (tunnelConfig.disableTimeouts) {
                //disable timeouts
                MyLog.g("DisableTimeouts", "disableTimeouts", true);
                json.put("TunnelConnectTimeoutSeconds", 0);
                json.put("TunnelPortForwardTimeoutSeconds", 0);
                json.put("TunnelSshKeepAliveProbeTimeoutSeconds", 0);
                json.put("TunnelSshKeepAlivePeriodicTimeoutSeconds", 0);
                json.put("FetchRemoteServerListTimeoutSeconds", 0);
                json.put("PsiphonApiServerTimeoutSeconds", 0);
                json.put("FetchRoutesTimeoutSeconds", 0);
                json.put("HttpProxyOriginServerTimeoutSeconds", 0);
            }

            return json.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    @Override
    public String getPsiphonConfig() {
        String config = buildTunnelCoreConfig(m_parentService, m_tunnelConfig, null, null);
        return config == null ? "" : config;
    }

    @Override
    public void onDiagnosticMessage(String message) {
        // TODO-TUNNEL-CORE: temporary:
        //MyLog.g("diagnostic", "msg", message);
        android.util.Log.e("PSIPHON-DIAGNOSTIC", message);
    }

    @Override
    public void onAvailableEgressRegions(List<String> regions) {
        m_tunnelState.availableEgressRegions.clear();
        m_tunnelState.availableEgressRegions.addAll(regions);
        Bundle data = new Bundle();
        data.putStringArrayList(DATA_TUNNEL_STATE_AVAILABLE_EGRESS_REGIONS,
                m_tunnelState.availableEgressRegions);
        sendClientMessage(MSG_KNOWN_SERVER_REGIONS, data);
    }

    @Override
    public void onSocksProxyPortInUse(int port) {
        MyLog.e(R.string.socks_port_in_use, MyLog.Sensitivity.NOT_SENSITIVE, port);
        signalStopService();
    }

    @Override
    public void onHttpProxyPortInUse(int port) {
        MyLog.e(R.string.http_proxy_port_in_use, MyLog.Sensitivity.NOT_SENSITIVE, port);
        signalStopService();
    }

    @Override
    public void onListeningSocksProxyPort(int port) {
        MyLog.v(R.string.socks_running, MyLog.Sensitivity.NOT_SENSITIVE, port);
        m_tunnelState.listeningLocalSocksProxyPort = port;
    }

    @Override
    public void onListeningHttpProxyPort(int port) {
        MyLog.v(R.string.http_proxy_running, MyLog.Sensitivity.NOT_SENSITIVE, port);
        m_tunnelState.listeningLocalHttpProxyPort = port;
    }

    @Override
    public void onUpstreamProxyError(String message) {
        // Display the error message only once, and continue trying to connect in
        // case the issue is temporary.
        if (m_lastUpstreamProxyErrorMessage == null || !m_lastUpstreamProxyErrorMessage.equals(message)) {
            MyLog.v(R.string.upstream_proxy_error, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, message);
            m_lastUpstreamProxyErrorMessage = message;
        }
    }

    @Override
    public void onConnecting() {
        DataTransferStats.getDataTransferStats().stop();

        MyLog.v(R.string.tunnel_connecting, MyLog.Sensitivity.NOT_SENSITIVE);

        setIsConnected(false);
        Bundle data = new Bundle();
        data.putBoolean(DATA_TUNNEL_STATE_IS_CONNECTED, false);
        sendClientMessage(MSG_TUNNEL_CONNECTION_STATE, data);
    }

    @Override
    public void onConnected() {
        DataTransferStats.getDataTransferStats().startConnected();

        MyLog.v(R.string.tunnel_connected, MyLog.Sensitivity.NOT_SENSITIVE);

        sendHandshakeIntent(m_isReconnect.get());
        // Any subsequent onConnecting after this first onConnect will be a reconnect.
        m_isReconnect.set(true);

        setIsConnected(true);
        Bundle data = new Bundle();
        data.putBoolean(DATA_TUNNEL_STATE_IS_CONNECTED, true);
        sendClientMessage(MSG_TUNNEL_CONNECTION_STATE, data);
    }

    @Override
    public void onHomepage(String url) {
        for (String homePage : m_tunnelState.homePages) {
            if (homePage.equals(url)) {
                return;
            }
        }
        m_tunnelState.homePages.add(url);
    }

    @Override
    public void onClientRegion(String region) {
        Bundle data = new Bundle();
        data.putString(DATA_TUNNEL_STATE_CLIENT_REGION, region);
        sendClientMessage(MSG_TUNNEL_CONNECTION_STATE, data);
    }

    @Override
    public void onClientUpgradeDownloaded(String filename) {
        UpgradeManager.UpgradeInstaller.notifyUpgrade(m_parentService);
    }

    @Override
    public void onClientIsLatestVersion() {
    }

    @Override
    public void onSplitTunnelRegion(String region) {
        MyLog.v(R.string.split_tunnel_region, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, region);
    }

    @Override
    public void onUntunneledAddress(String address) {
        MyLog.v(R.string.untunneled_address, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, address);
    }

    @Override
    public void onBytesTransferred(long sent, long received) {
        DataTransferStats stats = DataTransferStats.getDataTransferStats();
        stats.addBytesSent(sent);
        stats.addBytesReceived(received);
    }

    @Override
    public void onStartedWaitingForNetworkConnectivity() {
        MyLog.v(R.string.waiting_for_network_connectivity, MyLog.Sensitivity.NOT_SENSITIVE);
    }

    @Override
    public void onClientVerificationRequired() {
        // Perform safetyNet check
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                m_safetyNetwrapper = GoogleSafetyNetApiWrapper.getInstance(getContext());
                m_safetyNetwrapper.connect(TunnelManager.this);
            }
        });
    }

    @Override
    public void onExiting() {}

    public void setClientVerificationResult(String payload) {
        if (m_tunnel != null) {
            m_tunnel.setClientVerificationPayload(payload);
        }
    }

    @Override
    public void statusEntryAdded() {
        // TODO-TUNNEL-CORE: temporary implementation only! neither robust nor functional.
        if (m_outgoingMessenger == null) {
            return;
        }
        ArrayList<String> logs = new ArrayList<>();
        Bundle data = new Bundle();
        data.putStringArrayList(DATA_LOGS, logs);
        sendClientMessage(MSG_LOGS, data);
    }
}

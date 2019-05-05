package com.example.android.bluetoothadvertisements;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.support.annotation.RequiresApi;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Pattern;

/**
 * Manages BLE Advertising independent of the main app.
 * If the app goes off screen (or gets killed completely) advertising can continue because this
 * Service is maintaining the necessary Callback in memory.
 */
public class AdvertiserService extends Service {

    private static final String TAG = AdvertiserService.class.getSimpleName();

    private static final int FOREGROUND_NOTIFICATION_ID = 1;

    /**
     * A global variable to let AdvertiserFragment check if the Service is running without needing
     * to start or bind to it.
     * This is the best practice method as defined here:
     * https://groups.google.com/forum/#!topic/android-developers/jEvXMWgbgzE
     */
    private boolean running = false;

    public static final String ADVERTISING_FAILED =
        "com.example.android.bluetoothadvertisements.advertising_failed";

    public static final String ADVERTISING_FAILED_EXTRA_CODE = "failureCode";

    public static final int ADVERTISING_TIMED_OUT = 6;

    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;

    private BluetoothGattServer mGattServer;

    private AdvertiseCallback mAdvertiseCallback;

    private ArrayList<BluetoothDevice> mDevices = new ArrayList<>();
    private HashMap<String, BluetoothGatt> mDevicesAndGatts = new HashMap<>();

    private BroadcastReceiver isItRunning = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (running) {
                sendFailureIntent(323554);
            }
        }
    };
    private Vibrator vibrator;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic readCharacteristic;
    private String fileName = "UnknownFile";

    @Override
    public void onCreate() {
        running = true;
        initialize();
        sendFailureIntent(323554);
        startAdvertising();
        //Utils.tryReconnectService(this);
        registerReceiver(isItRunning, new IntentFilter("is.it.running.TheAdvertiser"));
        //startAcceptTcpSocket();
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        /*
         * Note that onDestroy is not guaranteed to be called quickly or at all. Services exist at
         * the whim of the system, and onDestroy can be delayed or skipped entirely if memory need
         * is critical.
         */
        wakeLock.release();
        running = false;
        stopAdvertising();
        sendFailureIntent(234232);
        stopForeground(true);
        try {
            unregisterReceiver(isItRunning);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    private WifiManager.LocalOnlyHotspotReservation mReservation;
    private boolean isHotspotEnabled = false;

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void turnOnHotspot() {
        WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        if (manager != null) {
            // Don't start when it started (existed)
            manager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {

                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    super.onStarted(reservation);
                    //Log.d(TAG, "Wifi Hotspot is on now");
                    mReservation = reservation;
                    Log.d("Hotspot", "enable true");
                    isHotspotEnabled = true;
                    for (BluetoothGatt gatt : mDevicesAndGatts.values()) {
                        if (gatt != null) {
                            BluetoothGattService service =
                                    gatt.getService(Constants.advertiseServiceUUIDMac.getUuid());
                            if (service != null) {
                                BluetoothGattCharacteristic characteristic = service.getCharacteristic(Constants.writeAdvertiseCharacteristicsUUIDMac.getUuid());
                                if (characteristic != null) {
                                    characteristic.setValue("wifi/-#" + reservation.getWifiConfiguration().SSID + "/-#" + reservation.getWifiConfiguration().preSharedKey);
                                    gatt.writeCharacteristic(characteristic);
                                }
                            }
                        }
                    }
                    startAcceptTcpSocket();
                }

                @Override
                public void onStopped() {
                    super.onStopped();
                    //Log.d(TAG, "onStopped: ");
                    isHotspotEnabled = false;

                    Log.d("Hotspot", "enable false");
                    try {
                        socket.close();
                    } catch (Exception e) {
                        System.out.println("Can't close accept socket.");
                        e.printStackTrace();
                    }
                    try {
                        serverSocket.close();
                    } catch (Exception e) {
                        System.out.println("Can't close server socket.");
                        e.printStackTrace();
                    }
                }

                @Override
                public void onFailed(int reason) {
                    super.onFailed(reason);
                    //Log.d(TAG, "onFailed: ");

                    Log.d("Hotspot", "fail: "+reason);
                    isHotspotEnabled = false;
                }
            }, null);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void turnOffHotspot() {
        if (mReservation != null) {
            mReservation.close();
            isHotspotEnabled = false;
        }
    }

    //check whether wifi hotspot on or off
    public boolean isApOn() {
        WifiManager wifimanager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        try {
            Method method = wifimanager.getClass().getDeclaredMethod("isWifiApEnabled");
            method.setAccessible(true);
            return (Boolean) method.invoke(wifimanager);
        }
        catch (Throwable ignored) {}
        return false;
    }

    // toggle wifi hotspot on or off
    public boolean configApState(boolean state) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (state) {
                turnOnHotspot();
            } else {
                turnOffHotspot();
            }
            return true;
        }
        WifiManager wifimanager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiConfiguration wificonfiguration = null;
        try {
            // if WiFi is on, turn it off
            if(wifimanager.isWifiEnabled()) {
                wifimanager.setWifiEnabled(false);
            }
            Method method = wifimanager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            method.invoke(wifimanager, wificonfiguration, state);
            return true;
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Required for extending service, but this will be a Started Service only, so no need for
     * binding.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    PowerManager.WakeLock wakeLock;

    /**
     * Get references to system Bluetooth objects if we don't have them already.
     */
    private void initialize() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MyApp::MyWakelockTag");
        wakeLock.acquire();
        vibrator = null;//(Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (mBluetoothLeAdvertiser == null || mGattServer == null) {
            BluetoothManager mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager != null) {
                BluetoothAdapter mBluetoothAdapter = mBluetoothManager.getAdapter();
                if (mBluetoothAdapter != null) {
                    if (mBluetoothLeAdvertiser == null) {
                        mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
                    }
                    if (mGattServer == null) {
                        GattServerCallback gattServerCallback = new GattServerCallback();
                        mGattServer = mBluetoothManager.openGattServer(this, gattServerCallback);
                        setupServer();
                    }
                } else {
                    Toast.makeText(this, getString(R.string.bt_null), Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, getString(R.string.bt_null), Toast.LENGTH_LONG).show();
            }
        }

    }

    /**
     * Starts BLE Advertising.
     */
    private void startAdvertising() {
        goForeground();

        Log.d(TAG, "Service: Starting Advertising");

        if (mAdvertiseCallback == null) {
            AdvertiseSettings settings = buildAdvertiseSettings();
            AdvertiseData data = buildAdvertiseData();
            mAdvertiseCallback = new SampleAdvertiseCallback();

            if (mBluetoothLeAdvertiser != null) {
                mBluetoothLeAdvertiser.startAdvertising(settings, data,
                    mAdvertiseCallback);
            }
        }
    }

    /**
     * without writeCharacteristic we are useless
     */
    private void setupServer() {
        BluetoothGattService service = new BluetoothGattService(Constants.Service_UUID.getUuid(),
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        writeCharacteristic = new BluetoothGattCharacteristic(
                Constants.WriteCharacteristic_UUID.getUuid(),
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        readCharacteristic = new BluetoothGattCharacteristic(
                Constants.ReadCharacteristic_UUID.getUuid(),
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);

        service.addCharacteristic(writeCharacteristic);
        service.addCharacteristic(readCharacteristic);
        mGattServer.addService(service);
    }

    /**
     * Move service to the foreground, to avoid execution limits on background processes.
     *
     * Callers should call stopForeground(true) when background work is complete.
     */
    private void goForeground() {
        NotificationManager manager = (NotificationManager) getApplicationContext().getSystemService(NOTIFICATION_SERVICE);
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
            notificationIntent, 0);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel advertising_channel = new NotificationChannel("advertising_channel", "Advertising Service", NotificationManager.IMPORTANCE_MIN);
            manager.createNotificationChannel(advertising_channel);
            NotificationChannel advertising_channel2 = new NotificationChannel("transfer_info", "Transfer Info", NotificationManager.IMPORTANCE_HIGH);
            manager.createNotificationChannel(advertising_channel2);
        }
        Notification n = new NotificationCompat.Builder(this, "advertising_channel")
            .setContentTitle("Advertising device via Bluetooth")
            .setContentText("This device is discoverable to others nearby.")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build();
        manager.notify(FOREGROUND_NOTIFICATION_ID, n);
        startForeground(FOREGROUND_NOTIFICATION_ID, n);
    }

    /**
     * Stops BLE Advertising.
     */
    private void stopAdvertising() {
        Log.d(TAG, "Service: Stopping Advertising");
        if (mBluetoothLeAdvertiser != null) {
            mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
            mAdvertiseCallback = null;
        }
        if (mGattServer != null) {
            mGattServer.close();
        }
        mGattServer = null;
    }

    /**
     * Returns an AdvertiseData object which includes the Service UUID and Device Name.
     */
    private AdvertiseData buildAdvertiseData() {

        /**
         * Note: There is a strict limit of 31 Bytes on packets sent over BLE Advertisements.
         *  This includes everything put into AdvertiseData including UUIDs, device info, &
         *  arbitrary service or manufacturer data.
         *  Attempting to send packets over this limit will result in a failure with error code
         *  AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE. Catch this error in the
         *  onStartFailure() method of an AdvertiseCallback implementation.
         */

        AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder();
        dataBuilder.addServiceUuid(Constants.Service_UUID);
        //dataBuilder.addServiceData(Constants.Service_UUID, "P".getBytes());
        dataBuilder.setIncludeDeviceName(true);

        /* For example - this will cause advertising to fail (exceeds size limit) */
        //String failureData = "asdghkajsghalkxcjhfa;sghtalksjcfhalskfjhasldkjfhdskf";
        //dataBuilder.addServiceData(Constants.Service_UUID, failureData.getBytes());

        return dataBuilder.build();
    }

    /**
     * Returns an AdvertiseSettings object set to use low power (to help preserve battery life)
     * and disable the built-in timeout since this code uses its own timeout runnable.
     */
    private AdvertiseSettings buildAdvertiseSettings() {
        AdvertiseSettings.Builder settingsBuilder = new AdvertiseSettings.Builder();
        settingsBuilder.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER);
        settingsBuilder.setTimeout(0);
        return settingsBuilder.build();
    }

    /**
     * Custom callback after Advertising succeeds or fails to start. Broadcasts the error code
     * in an Intent to be picked up by AdvertiserFragment and stops this Service.
     */
    private class SampleAdvertiseCallback extends AdvertiseCallback {

        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);

            Log.d(TAG, "Advertising failed");
            sendFailureIntent(errorCode);
            stopSelf();

        }

        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            Log.d(TAG, "Advertising successfully started");
        }
    }

    /**
     * Builds and sends a broadcast intent indicating Advertising has failed. Includes the error
     * code as an extra. This is intended to be picked up by the {@code AdvertiserFragment}.
     */
    private void sendFailureIntent(int errorCode){
        Intent failureIntent = new Intent();
        failureIntent.setAction(ADVERTISING_FAILED);
        failureIntent.putExtra(ADVERTISING_FAILED_EXTRA_CODE, errorCode);
        sendBroadcast(failureIntent);
    }

    private class GattServerCallback extends BluetoothGattServerCallback {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mDevices.add(device);
                if (vibrator != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(700, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        vibrator.vibrate(700);
                    }
                }
                device.connectGatt(AdvertiserService.this, true, new BluetoothGattCallback() {
                    @Override
                    public void onConnectionStateChange(BluetoothGatt gatt, int statusGatt, int newStateGatt) {
                        if (newStateGatt == BluetoothProfile.STATE_CONNECTED) {
                            mDevicesAndGatts.put(gatt.getDevice().getAddress(), gatt);
                            Log.d("onConnectionStateChange", "connected to: "+gatt.getDevice().getAddress());
                            gatt.discoverServices();
                        }

                        if (newStateGatt == BluetoothProfile.STATE_DISCONNECTED) {
                            Log.d("BluetoothGattOperation", "disconnected gatt -> finished: "+gatt.getDevice().getAddress());
                        }

                    }

                    @Override
                    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                        boolean foundNeededService = false;
                        Log.d("onServicesDiscovered", "service lenght: "+ gatt.getServices().size());
                        for (BluetoothGattService service : gatt.getServices()) {
                            if (service.getUuid().equals(Constants.advertiseServiceUUIDMac.getUuid())) {
                                Log.d("onServicesDiscovered", "correct service uuid: " + service.getUuid());
                                foundNeededService = true;
                            }
                        }
                        if (!foundNeededService) {
                            gatt.disconnect();
                            Log.d("BluetoothGattOperation", "disconnected gat: "+gatt.getDevice().getAddress());
                            mDevicesAndGatts.remove(gatt.getDevice().getAddress());
                            return;
                        }
                        BluetoothGattCharacteristic characteristic =
                                gatt.getService(Constants.advertiseServiceUUIDMac.getUuid())
                                        .getCharacteristic(Constants.writeAdvertiseCharacteristicsUUIDMac.getUuid());
                        characteristic.setValue("OookKll");
                        gatt.writeCharacteristic(characteristic);
                    }
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mDevices.remove(device);
                if (vibrator != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createWaveform(new long[]{100, 700, 100, 700}, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Thread.sleep(100);
                                    vibrator.vibrate(700);
                                    Thread.sleep(100);
                                    vibrator.vibrate(700);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        }).start();
                    }
                }
                BluetoothGatt connectedPeriph = mDevicesAndGatts.get(device.getAddress());
                if (connectedPeriph != null) {
                    connectedPeriph.disconnect();
                    Log.d("BluetoothGattOperation", "disconnected gat: "+device.getAddress());
                    mDevicesAndGatts.remove(device.getAddress());
                }
            }
        }



        public void onCharacteristicWriteRequest(BluetoothDevice device,
                                                 int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite,
                                                 boolean responseNeeded,
                                                 int offset,
                                                 byte[] value) {
            if (characteristic.getUuid().equals(Constants.WriteCharacteristic_UUID.getUuid())) {
                String data = new String(value);
                Log.d("onCharacteristicWrite", "data: "+data);
                switch (data) {
                    case Constants.RECEIVE_FILE:
                        if (!isApOn()) {
                            if (vibrator != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
                                } else {
                                    vibrator.vibrate(500);
                                }
                            }
                            configApState(true);
                        }
                        break;
                    case Constants.TURN_WIFI_TETHER_OFF:
                        if (isApOn()) {
                            if (vibrator != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    vibrator.vibrate(VibrationEffect.createWaveform(new long[]{100, 500, 100, 500}, VibrationEffect.DEFAULT_AMPLITUDE));
                                } else {
                                    new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                Thread.sleep(100);
                                                vibrator.vibrate(500);
                                                Thread.sleep(100);
                                                vibrator.vibrate(500);
                                            } catch (InterruptedException e) {
                                                e.printStackTrace();
                                            }
                                        }
                                    }).start();
                                }
                            }
                            configApState(false);
                        }
                        break;
                }
                if (data.contains("filename/-#")) {
                    String[] filenameParts = data.split(Pattern.quote("/-#"));
                    fileName = filenameParts[1];
                    BluetoothGatt gatt = mDevicesAndGatts.get(device.getAddress());
                    if (gatt != null) {
                        BluetoothGattCharacteristic characteristicToSend =
                                gatt.getService(Constants.advertiseServiceUUIDMac.getUuid())
                                        .getCharacteristic(Constants.writeAdvertiseCharacteristicsUUIDMac.getUuid());
                        characteristicToSend.setValue("socket_read/-#");
                        gatt.writeCharacteristic(characteristicToSend);
                    }
                }
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, "DONE".getBytes());
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device,
                                                int requestId,
                                                int offset,
                                                BluetoothGattCharacteristic characteristic) {
            if (characteristic.getUuid().equals(Constants.ReadCharacteristic_UUID.getUuid())) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, "OK".getBytes());
            }
        }
    }

    ServerSocket serverSocket = null;
    Socket socket = null;
    boolean transferDone = false;

    public void acceptFile() throws IOException {
        if (socket != null && !socket.isClosed()) {
            socket.close();
            while (!socket.isClosed()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            socket = null;
        }
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
            while (!serverSocket.isClosed()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            serverSocket = null;
        }

        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(9265));
        } catch (IOException ex) {
            ex.printStackTrace();
            System.out.println("Can't setup server on this port number. ");
            return;
        }

        InputStream in = null;

        try {
            socket = serverSocket.accept();
        } catch (IOException ex) {
            ex.printStackTrace();
            System.out.println("Can't accept client connection. ");
            serverSocket.close();
            return;
        }

        try {
            in = socket.getInputStream();
        } catch (IOException ex) {
            ex.printStackTrace();
            socket.close();
            serverSocket.close();
            System.out.println("Can't get socket input stream. ");
            return;
        }

        long allData = 0;

        File dir = new File(Environment.getExternalStorageDirectory(), "CrossplatformDrop");
        if (!dir.exists()) {
            dir.mkdir();
        }
        File file = new File(dir, fileName);
        if (file.exists()) {
            file.delete();
        }
        file.createNewFile();
        OutputStream output = new FileOutputStream(file);
        byte[] buffer = new byte[32 * 1024]; // or other buffer size
        int read;

        while ((read = in.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            allData += read;
        }


        Log.d("Socket", "readFile lenght: "+allData);
        output.flush();
        output.close();
        in.close();
        socket.close();
        serverSocket.close();
        transferDone = true;
    }


    void startAcceptTcpSocket() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                transferDone = false;
                try {
                    acceptFile();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        NotificationCompat.Builder mBuilder =   new NotificationCompat.Builder(AdvertiserService.this, "transfer_info")
                                .setSmallIcon(R.drawable.ic_launcher)
                                .setAutoCancel(true);
                        if (transferDone) {
                            mBuilder.setContentTitle("Transfer finished!");
                            mBuilder.setContentText(fileName + " transfered to /sdcard/CrossplatformDrop successfully!");
                        } else {
                            mBuilder.setContentTitle("Transfer failed!");
                            mBuilder.setContentText(fileName + " could not be transfered!");
                        }// clear notification after click
                        NotificationManager mNotificationManager =
                                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        mNotificationManager.notify(3251, mBuilder.build());
                        configApState(false);
                    }
                });
            }
        }).start();
    }
}

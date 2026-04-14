package com.sprinklerapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class WifiProvisionActivity extends AppCompatActivity {

    // BLE UUID – musia byť rovnaké ako v Arduino
    private static final UUID SERVICE_UUID   = UUID.fromString("484bb508-f485-41a1-802c-cb8bb0bdfbf0");
    private static final UUID CHAR_SSID_UUID = UUID.fromString("9312f166-ff7c-42d0-9c89-1c5a2ea0b080");
    private static final UUID CHAR_PASS_UUID = UUID.fromString("b9e17c9f-1474-44a0-a332-f9894d626b08");
    private static final UUID CHAR_HOST_UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID CHAR_AUTH_UUID = UUID.fromString("b2c3d4e5-f6a7-8901-bcde-f12345678901");
    private static final UUID CHAR_PATH_UUID = UUID.fromString("c3d4e5f6-a7b8-9012-cdef-123456789012");

    // Časové limity
    private static final long SERVICE_DISCOVERY_DELAY_MS = 600;
    private static final int  MAX_CONNECT_RETRIES        = 3;
    private static final long RETRY_DELAY_MS             = 1500;
    private static final long WIFI_TEST_TIMEOUT_MS       = 15000;
    private static final long FIREBASE_POLL_INTERVAL_MS  = 3000;
    private static final long FIREBASE_POLL_TIMEOUT_MS   = 30000;

    // UI komponenty
    private TextView tvDeviceName, tvConnectionStatus, tvProvisionStatus, tvHotspotHint;
    private TextInputEditText etSsid, etPassword;
    private MaterialButton btnSend, btnBack;
    private LinearProgressIndicator progressBar;
    private SwitchMaterial switchHotspot;

    // BLE premenné
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic ssidCharacteristic;
    private BluetoothGattCharacteristic passCharacteristic;
    private BluetoothGattCharacteristic completeUrlCharacteristic;
    private BluetoothGattCharacteristic authCharacteristic;
    private BluetoothGattCharacteristic pathCharacteristic;
    private boolean gattConnected    = false;
    private boolean credentialsSent  = false;
    private boolean writingInProgress = false;   // guards the multi-write sequence
    private String  pendingPassword    = null;
    private String  pendingCompleteUrl = null;
    private String  pendingAuth        = null;
    private String  pendingPath        = null;
    private String  deviceAddress      = null;
    private int     connectRetries     = 0;

    // WiFi overenie
    private ConnectivityManager.NetworkCallback wifiTestCallback = null;
    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;

    // Firebase polling
    private boolean pollingFirebase = false;
    private long    pollStart       = 0;
    private String  baselineValue   = null;

    // Hotspot – ak true, preskočíme overovanie WiFi
    private boolean usePhoneHotspot = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_provision);

        tvDeviceName       = findViewById(R.id.tvDeviceName);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvProvisionStatus  = findViewById(R.id.tvProvisionStatus);
        tvHotspotHint      = findViewById(R.id.tvHotspotHint);
        etSsid             = findViewById(R.id.etSsid);
        etPassword         = findViewById(R.id.etPassword);
        btnSend            = findViewById(R.id.btnSend);
        btnBack            = findViewById(R.id.btnBack);
        progressBar        = findViewById(R.id.progressBar);
        switchHotspot      = findViewById(R.id.switchHotspot);

        deviceAddress     = getIntent().getStringExtra("device_address");
        String deviceName = getIntent().getStringExtra("device_name");
        tvDeviceName.setText(deviceName != null ? deviceName : "Arduino");

        wifiManager         = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        btnBack.setOnClickListener(v -> finish());
        btnSend.setOnClickListener(v -> validateAndSend());
        btnSend.setEnabled(false);

        switchHotspot.setOnCheckedChangeListener((buttonView, isChecked) -> {
            usePhoneHotspot = isChecked;
            tvHotspotHint.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        connectToDevice();
    }


    private void validateAndSend() {
        String ssid = etSsid.getText() != null ? etSsid.getText().toString().trim() : "";
        String pass = etPassword.getText() != null ? etPassword.getText().toString() : "";

        if (ssid.isEmpty()) {
            etSsid.setError("Zadajte názov WiFi (SSID)");
            return;
        }

        btnSend.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvProvisionStatus.setVisibility(View.VISIBLE);

        String firebaseCompleteUrl = FirebaseConfig.BASE_URL;

        if (usePhoneHotspot || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Hotspot or pre-Android 10: skip WiFi validation, send directly
            String label = usePhoneHotspot
                    ? "režim hotspot — preskakujem overenie WiFi"
                    : "staršie Android — preskakujem overenie WiFi";
            tvProvisionStatus.setText("Odosielam do Arduino (" + label + ")...");
            sendCredentialsToArduino(ssid, pass, firebaseCompleteUrl);
        } else {
            // Android 10+: validate WiFi first
            tvProvisionStatus.setText("Overujem WiFi prihlasovacie údaje...");
            validateWifiModern(ssid, pass, firebaseCompleteUrl);
        }
    }


    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.Q)
    private void validateWifiModern(String ssid, String pass, String firebaseCompleteUrl) {

        if (wifiTestCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(wifiTestCallback); } catch (Exception ignored) {}
            wifiTestCallback = null;
        }

        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(pass)
                .build();

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build();

        mainHandler.postDelayed(() -> {
            if (wifiTestCallback != null) {
                try { connectivityManager.unregisterNetworkCallback(wifiTestCallback); } catch (Exception ignored) {}
                wifiTestCallback = null;
                onWifiValidationFailed();
            }
        }, WIFI_TEST_TIMEOUT_MS);

        wifiTestCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                mainHandler.post(() -> {
                    try { connectivityManager.unregisterNetworkCallback(this); } catch (Exception ignored) {}
                    wifiTestCallback = null;
                    onWifiValidated(ssid, pass, firebaseCompleteUrl);
                });
            }

            @Override
            public void onUnavailable() {
                mainHandler.post(() -> {
                    try { connectivityManager.unregisterNetworkCallback(this); } catch (Exception ignored) {}
                    wifiTestCallback = null;
                    onWifiValidationFailed();
                });
            }
        };

        connectivityManager.requestNetwork(request, wifiTestCallback);
    }

    private void onWifiValidated(String ssid, String pass, String firebaseCompleteUrl) {
        tvProvisionStatus.setText("WiFi údaje overené! Odosielam do Arduina...");
        sendCredentialsToArduino(ssid, pass, firebaseCompleteUrl);
    }

    private void onWifiValidationFailed() {
        progressBar.setVisibility(View.GONE);
        tvProvisionStatus.setText("Nepodarilo sa pripojiť k tejto WiFi sieti.\nSkontrolujte SSID a heslo a skúste znova.");
        btnSend.setEnabled(true);
    }


    private void sendCredentialsToArduino(String ssid, String pass, String firebaseCompleteUrl) {
        if (!gattConnected || ssidCharacteristic == null || passCharacteristic == null) {
            showGeneralError("Spojenie s Arduino bolo prerušené. Skúste znova.");
            return;
        }
        writingInProgress  = true;
        pendingPassword    = pass;
        pendingCompleteUrl = firebaseCompleteUrl;
        pendingAuth        = FirebaseConfig.SECRET;
        pendingPath        = FirebaseConfig.PATH;
        mainHandler.postDelayed(() -> writeCharacteristic(ssidCharacteristic, ssid), 500);
    }

    private void connectToDevice() {
        setStatus("Pripájanie... (pokus " + (connectRetries + 1) + "/" + MAX_CONNECT_RETRIES + ")", false);
        progressBar.setVisibility(View.VISIBLE);

        if (bluetoothGatt != null) {
            if (hasPermission()) { bluetoothGatt.disconnect(); bluetoothGatt.close(); }
            bluetoothGatt = null;
        }

        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager.getAdapter();
        BluetoothDevice device   = adapter.getRemoteDevice(deviceAddress);

        if (!hasPermission()) {
            showGeneralError("Chýba oprávnenie Bluetooth. Reštartujte aplikáciu.");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                gattConnected = false;
                if (hasPermission()) gatt.close();
                bluetoothGatt = null;

                // Disconnect during the multi-write sequence → show error
                if (writingInProgress && !credentialsSent) {
                    writingInProgress = false;
                    clearPendingWrites();
                    mainHandler.post(() -> showGeneralError(
                            "Spojenie s Arduino sa prerušilo počas odosielania údajov.\n"
                          + "Reštartujte Arduino a skúste znova."));
                    return;
                }

                // Disconnect after credentials were fully sent → ignore (expected)
                if (credentialsSent) return;

                // Disconnect during initial connection → retry
                if (connectRetries < MAX_CONNECT_RETRIES) {
                    connectRetries++;
                    mainHandler.postDelayed(() -> connectToDevice(), RETRY_DELAY_MS);
                } else {
                    mainHandler.post(() -> showGeneralError(
                            "Pripojenie zlyhalo. Vypnite/zapnite Arduino a skúste znova."));
                }
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gattConnected  = true;
                connectRetries = 0;
                mainHandler.post(() -> setStatus("Pripojené! Zisťujem služby...", false));
                mainHandler.postDelayed(() -> {
                    if (hasPermission() && bluetoothGatt != null) {
                        gatt.discoverServices();
                    }
                }, SERVICE_DISCOVERY_DELAY_MS);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    ssidCharacteristic        = service.getCharacteristic(CHAR_SSID_UUID);
                    passCharacteristic        = service.getCharacteristic(CHAR_PASS_UUID);
                    completeUrlCharacteristic = service.getCharacteristic(CHAR_HOST_UUID);
                    authCharacteristic        = service.getCharacteristic(CHAR_AUTH_UUID);
                    pathCharacteristic        = service.getCharacteristic(CHAR_PATH_UUID);
                    mainHandler.post(() -> {
                        progressBar.setVisibility(View.GONE);
                        setStatus("Pripravené — zadajte WiFi údaje nižšie", false);
                        btnSend.setEnabled(true);
                    });
                } else {
                    mainHandler.post(() -> showGeneralError(
                            "Služba Arduino sa nenašla. Skontrolujte UUID."));
                }
            } else {
                mainHandler.post(() -> showGeneralError("Zistenie služieb zlyhalo."));
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic, int status) {

            if (characteristic.getUuid().equals(CHAR_SSID_UUID)) {
                if (pendingPassword != null) {
                    String pass = pendingPassword;
                    pendingPassword = null;
                    mainHandler.postDelayed(() -> writeCharacteristic(passCharacteristic, pass), 300);
                }
            } else if (characteristic.getUuid().equals(CHAR_PASS_UUID)) {
                if (pendingCompleteUrl != null) {
                    String url = pendingCompleteUrl;
                    pendingCompleteUrl = null;
                    mainHandler.postDelayed(() -> writeCharacteristic(completeUrlCharacteristic, url), 300);
                }
            } else if (characteristic.getUuid().equals(CHAR_HOST_UUID)) {
                if (pendingAuth != null) {
                    String a = pendingAuth;
                    pendingAuth = null;
                    mainHandler.postDelayed(() -> writeCharacteristic(authCharacteristic, a), 300);
                }
            } else if (characteristic.getUuid().equals(CHAR_AUTH_UUID)) {
                if (pendingPath != null) {
                    String p = pendingPath;
                    pendingPath = null;
                    mainHandler.postDelayed(() -> writeCharacteristic(pathCharacteristic, p), 300);
                }
            } else if (characteristic.getUuid().equals(CHAR_PATH_UUID)) {
                credentialsSent   = true;
                writingInProgress = false;
                mainHandler.post(() -> {
                    setStatus("Prihlasovací údaje odoslané!", false);
                    tvProvisionStatus.setVisibility(View.VISIBLE);
                    tvProvisionStatus.setText("Čakám na pripojenie Arduino k WiFi a Firebase...");
                    if (hasPermission() && bluetoothGatt != null) bluetoothGatt.disconnect();
                    startFirebasePolling();
                });
            }
        }
    };
    private void startFirebasePolling() {
        String firebaseUrl    = FirebaseConfig.fullUrl();
        String firebaseSecret = FirebaseConfig.SECRET;

        if (FirebaseConfig.BASE_URL.isEmpty()) {
            progressBar.setVisibility(View.GONE);
            tvProvisionStatus.setText("Prihlasovací údaje boli odoslané!");
            return;
        }

        pollingFirebase = true;
        pollStart       = System.currentTimeMillis();
        baselineValue   = null;

        fetchFirebase(firebaseUrl, firebaseSecret, value -> {
            baselineValue = value;
            schedulePoll(firebaseUrl, firebaseSecret);
        });
    }

    private void schedulePoll(String firebaseUrl, String firebaseSecret) {
        if (!pollingFirebase) return;

        if (System.currentTimeMillis() - pollStart > FIREBASE_POLL_TIMEOUT_MS) {
            pollingFirebase = false;
            mainHandler.post(() -> {
                progressBar.setVisibility(View.GONE);
                tvProvisionStatus.setText("Údaje zatiaľ neprijaty.\nArduino sa stále pripája.");
            });
            return;
        }

        mainHandler.postDelayed(() -> fetchFirebase(firebaseUrl, firebaseSecret, value -> {
            if (value != null && !value.equals("null") && !value.equals(baselineValue)) {
                pollingFirebase = false;
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    setStatus("Arduino pripojené a posiela dáta!", false);
                    tvProvisionStatus.setText("Pripojené! Otvára sa dashboard...");
                    mainHandler.postDelayed(() ->
                                    startActivity(new android.content.Intent(
                                            WifiProvisionActivity.this, DashboardActivity.class))
                            , 800);
                });
            } else {
                baselineValue = value;
                schedulePoll(firebaseUrl, firebaseSecret);
            }
        }), FIREBASE_POLL_INTERVAL_MS);
    }

    private void fetchFirebase(String firebaseUrl, String firebaseSecret, FirebaseCallback callback) {
        new Thread(() -> {
            try {
                String urlStr = firebaseUrl + "/current/lastSeen.json"
                        + (firebaseSecret.isEmpty() ? "" : "?auth=" + firebaseSecret);
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                conn.disconnect();
                mainHandler.post(() -> callback.onResult(sb.toString().trim()));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onResult(null));
            }
        }).start();
    }

    interface FirebaseCallback { void onResult(String value); }

    private void writeCharacteristic(BluetoothGattCharacteristic c, String value) {
        if (!hasPermission() || bluetoothGatt == null) {
            showGeneralError("Spojenie s Arduino bolo stratené. Skúste znova.");
            return;
        }
        c.setValue(value.getBytes(StandardCharsets.UTF_8));
        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        bluetoothGatt.writeCharacteristic(c);
    }

    private void clearPendingWrites() {
        pendingPassword    = null;
        pendingCompleteUrl = null;
        pendingAuth        = null;
        pendingPath        = null;
    }

    /** General error – resets UI to a recoverable state. */
    private void showGeneralError(String message) {
        progressBar.setVisibility(View.GONE);
        setStatus(message, true);
        tvProvisionStatus.setVisibility(View.GONE);
        btnSend.setEnabled(gattConnected
                && ssidCharacteristic != null
                && passCharacteristic != null);
    }

    private void setStatus(String msg, boolean isError) {
        tvConnectionStatus.setText(msg);
        tvConnectionStatus.setTextColor(getColor(isError ? R.color.error_red : R.color.accent_green));
    }

    private boolean hasPermission() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Manifest.permission.BLUETOOTH_CONNECT
                : Manifest.permission.ACCESS_FINE_LOCATION;
        return ActivityCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pollingFirebase = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (wifiTestCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { connectivityManager.unregisterNetworkCallback(wifiTestCallback); } catch (Exception ignored) {}
        }
        if (bluetoothGatt != null) {
            if (hasPermission()) { bluetoothGatt.disconnect(); bluetoothGatt.close(); }
            bluetoothGatt = null;
        }
    }
}

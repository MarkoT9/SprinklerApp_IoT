package com.sprinklerapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 100;
    private static final long SCAN_PERIOD = 10000; // 10 sekúnd

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private boolean isScanning = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    private MaterialButton btnScan;
    private CircularProgressIndicator progressIndicator;
    private RecyclerView recyclerView;
    private TextView tvStatus;
    private DeviceAdapter deviceAdapter;
    private List<BluetoothDevice> deviceList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnScan = findViewById(R.id.btnScan);
        progressIndicator = findViewById(R.id.progressIndicator);
        recyclerView = findViewById(R.id.recyclerDevices);
        tvStatus = findViewById(R.id.tvStatus);

        MaterialButton btnOpenDashboard = findViewById(R.id.btnOpenDashboard);
        btnOpenDashboard.setOnClickListener(v ->
            startActivity(new Intent(MainActivity.this, DashboardActivity.class))
        );

        deviceAdapter = new DeviceAdapter(deviceList, device -> {
            stopScan();
            Intent intent = new Intent(MainActivity.this, WifiProvisionActivity.class);
            intent.putExtra("device_address", device.getAddress());
            intent.putExtra("device_name",
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED ? device.getName() : "Arduino");
            startActivity(intent);
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(deviceAdapter);

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        btnScan.setOnClickListener(v -> {
            if (isScanning) stopScan();
            else checkPermissionsAndScan();
        });
    }

    private void checkPermissionsAndScan() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]), REQUEST_PERMISSIONS);
        } else {
            startScan();
        }
    }

    private void startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Prosím, povoľte Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }

        deviceList.clear();
        deviceAdapter.notifyDataSetChanged();

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        isScanning = true;
        btnScan.setText("Zastaviť vyhľadávanie");
        progressIndicator.setVisibility(View.VISIBLE);
        tvStatus.setText("Vyhľadávam Arduino zariadenia...");

        handler.postDelayed(this::stopScan, SCAN_PERIOD);

        if (ActivityCompat.checkSelfPermission(this, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            bleScanner.startScan(scanCallback);
        }
    }

    private void stopScan() {
        if (!isScanning) return;
        isScanning = false;
        btnScan.setText("Hľadať zariadenia");
        progressIndicator.setVisibility(View.GONE);
        tvStatus.setText(deviceList.isEmpty()
                ? "Nenašli sa žiadne zariadenia. Skúste znova."
                : "Nájdených " + deviceList.size() + " zariadení. Ťuknite pre pripojenie.");
        handler.removeCallbacksAndMessages(null);

        if (bleScanner != null && ActivityCompat.checkSelfPermission(this,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            bleScanner.stopScan(scanCallback);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (!deviceList.contains(device)) {
                if (ActivityCompat.checkSelfPermission(MainActivity.this,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                ? Manifest.permission.BLUETOOTH_CONNECT
                                : Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    deviceList.add(device);
                    runOnUiThread(() -> deviceAdapter.notifyItemInserted(deviceList.size() - 1));
                }
            }
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) { allGranted = false; break; }
            }
            if (allGranted) startScan();
            else Toast.makeText(this, "Sú potrebné oprávnenia Bluetooth", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
    }


    static class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder> {
        interface OnDeviceClick { void onClick(BluetoothDevice device); }

        private final List<BluetoothDevice> devices;
        private final OnDeviceClick listener;

        DeviceAdapter(List<BluetoothDevice> devices, OnDeviceClick listener) {
            this.devices = devices;
            this.listener = listener;
        }

        @NonNull
        @Override
        public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_device, parent, false);
            return new DeviceViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
            BluetoothDevice device = devices.get(position);
            String name = "Neznáme zariadenie";
            if (ActivityCompat.checkSelfPermission(holder.itemView.getContext(),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            ? Manifest.permission.BLUETOOTH_CONNECT
                            : Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                name = device.getName() != null ? device.getName() : "Neznáme zariadenie";
            }
            holder.tvName.setText(name);
            holder.tvAddress.setText(device.getAddress());
            holder.itemView.setOnClickListener(v -> listener.onClick(device));
        }

        @Override
        public int getItemCount() { return devices.size(); }

        static class DeviceViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvAddress;
            DeviceViewHolder(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvDeviceName);
                tvAddress = itemView.findViewById(R.id.tvDeviceAddress);
            }
        }
    }
}

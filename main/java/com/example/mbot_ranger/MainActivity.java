package com.example.mbot_ranger;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;      // ← add this!
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MBOT_RANGER";
    private static final String TARGET_MAC_ADDRESS = "00:1B:10:69:20:1E";
    private static final int REQUEST_PERMISSIONS = 1001;
    private static final int REQUEST_ENABLE_BT  = 1002;
    private static final long SCAN_PERIOD = 10_000L;

    private static final UUID SERVICE_UUID =
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE_CHARACTERISTIC_UUID =
            UUID.fromString("0000ffe3-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY_CHARACTERISTIC_UUID =
            UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb");
    private static final UUID CLIENT_CHAR_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;

    private TextView textStatus;
    private TextView textDistance;
    private Button btnConnect, btnLineFollow, btnObstacle, btnHandFollow, btnStop, btnDistance;
    private final Handler handler = new Handler();
    private boolean isScanning = false;
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textStatus    = findViewById(R.id.textStatus);
        textDistance  = findViewById(R.id.textDistance);
        btnConnect    = findViewById(R.id.btnConnect);
        btnLineFollow = findViewById(R.id.btnLineFollow);
        btnObstacle   = findViewById(R.id.btnObstacleAvoid);
        btnHandFollow = findViewById(R.id.btnHandFollow);
        btnStop       = findViewById(R.id.btnStop);
        btnDistance   = findViewById(R.id.btnDistance);

        // Start all off
        btnLineFollow .setEnabled(false);
        btnObstacle   .setEnabled(false);
        btnHandFollow .setEnabled(false);
        btnStop       .setEnabled(false);
        btnDistance   .setEnabled(false);

        BluetoothManager btManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = btManager != null ? btManager.getAdapter() : null;

        btnConnect.setOnClickListener(v -> ensureBlePermissions());
        btnLineFollow.setOnClickListener(v -> sendAsciiCommand('L', "Line Following"));
        btnObstacle.setOnClickListener(v -> sendAsciiCommand('O', "Obstacle Avoidance"));
        btnHandFollow.setOnClickListener(v -> sendAsciiCommand('H', "Hand Following"));
        btnStop.setOnClickListener(v -> sendAsciiCommand('S', "Stopped"));
        btnDistance.setOnClickListener(v -> sendAsciiCommand('D', "Querying Distance"));
    }

    private void ensureBlePermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQUEST_PERMISSIONS);
        } else {
            startBleScan();
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(rc, perms, grants);
        if (rc == REQUEST_PERMISSIONS) {
            boolean ok = true;
            for (int g: grants) if (g != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
            if (ok) startBleScan(); else Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show();
        }
    }

    private void startBleScan() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth unsupported", Toast.LENGTH_LONG).show();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
            return;
        }
        if (!isLocationEnabled()) {
            Toast.makeText(this, "Enable Location Services", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) { Toast.makeText(this, "BLE Scanner unavailable", Toast.LENGTH_LONG).show(); return; }
        if (isScanning) return;
        isScanning = true;
        handler.postDelayed(() -> {
            if (isScanning) {
                isScanning = false;
                bleScanner.stopScan(scanCallback);
                runOnUiThread(() -> textStatus.setText("Scan timed out, connecting"));
                directConnect();
            }
        }, SCAN_PERIOD);
        bleScanner.startScan(scanCallback);
        textStatus.setText("Scanning…");
    }

    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager)getSystemService(LOCATION_SERVICE);
        return lm!=null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
    }

    @Override
    protected void onActivityResult(int rc, int result, Intent data) {
        super.onActivityResult(rc, result, data);
        if (rc == REQUEST_ENABLE_BT && bluetoothAdapter.isEnabled()) startBleScan();
    }

    private void directConnect() {
        BluetoothDevice dev = bluetoothAdapter.getRemoteDevice(TARGET_MAC_ADDRESS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "Missing BLUETOOTH_CONNECT"); return; }
        bluetoothGatt = dev.connectGatt(this, false, gattCallback);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int cbType, ScanResult res) {
            BluetoothDevice d = res.getDevice();
            if (d!=null && TARGET_MAC_ADDRESS.equalsIgnoreCase(d.getAddress())) {
                isScanning = false; bleScanner.stopScan(this);
                runOnUiThread(() -> textStatus.setText("Connecting…"));
                directConnect();
            }
        }
        @Override public void onScanFailed(int errorCode) {
            isScanning = false; Log.e(TAG, "Scan failed: "+errorCode);
            directConnect();
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "Connected, discovering services");
                g.discoverServices();
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            Log.d(TAG, "Services discovered: " + status);
            BluetoothGattService svc = g.getService(SERVICE_UUID);
            if (svc!=null) {
                writeCharacteristic = svc.getCharacteristic(WRITE_CHARACTERISTIC_UUID);
                BluetoothGattCharacteristic notifyChar = svc.getCharacteristic(NOTIFY_CHARACTERISTIC_UUID);
                if (notifyChar!=null) {
                    g.setCharacteristicNotification(notifyChar, true);
                    BluetoothGattDescriptor desc = notifyChar.getDescriptor(CLIENT_CHAR_CONFIG_UUID);
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(desc);
                }
            }
            runOnUiThread(() -> {
                textStatus.setText("Connected");
                btnLineFollow .setEnabled(true);
                btnObstacle   .setEnabled(true);
                btnHandFollow .setEnabled(true);
                btnStop       .setEnabled(true);
                btnDistance   .setEnabled(true);
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            Log.d(TAG, "Descriptor write status="+status);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            String s = new String(c.getValue(), StandardCharsets.UTF_8).trim();
            Log.d(TAG, "BLE RX: " + s);
            runOnUiThread(() -> {
                textStatus.append("\n" + s);
                // parse DIST:###.#
                if (s.startsWith("DIST:")) {
                    String dist = s.substring(5);
                    textDistance.setText("Distance: " + dist + " cm");
                }
            });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            Log.d(TAG, "Write status="+status);
        }
    };

    private void sendAsciiCommand(char cmd, String label) {
        if (busy) return;
        busy = true;
        handler.postDelayed(() -> busy = false, 100);
        if (writeCharacteristic == null || bluetoothGatt == null) return;
        writeCharacteristic.setValue(new byte[]{(byte)cmd});
        bluetoothGatt.writeCharacteristic(writeCharacteristic);
        textStatus.setText(label);
    }
}
//bypass구현
package com.example.aoudio;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.kc7bfi.jflac.FLACDecoder;
import org.kc7bfi.jflac.frame.Frame;
import org.kc7bfi.jflac.metadata.StreamInfo;
import org.kc7bfi.jflac.util.ByteData;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {
    private static final String ACTION_USB_PERMISSION = "com.example.USB_PERMISSION";
    private WebView webView;
    private UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbEndpoint endpoint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }

        // WebView 설정
        webView = findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new WebAppInterface(this), "Android");
        webView.loadUrl("0.0.0.0:8000/"); //site link
        
    }

    // USB 데이터 전송 함수
    public void sendDataToUsb(byte[] data) {
        if (connection != null && endpoint != null) {
            int packetSize = endpoint.getMaxPacketSize();
            byte[] buffer = new byte[packetSize];
            int offset = 0;

            while (offset < data.length) {
                int length = Math.min(packetSize, data.length - offset);
                System.arraycopy(data, offset, buffer, 0, length);
                connection.bulkTransfer(endpoint, buffer, length, 0);
                offset += length;
            }
        } else {
            Toast.makeText(this, "USB 연결이 초기화되지 않았습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    // PCM 오디오 데이터를 읽어 USB로 전송
    public void readAndTransferAudioData(String filePath) {
        new Thread(() -> {
            try (FileInputStream fis = new FileInputStream(new File(filePath))) {
                int bufferSize = 4096;
                byte[] buffer = new byte[bufferSize];
                int bytesRead;

                while ((bytesRead = fis.read(buffer)) != -1) {
                    sendDataToUsb(buffer);
                }
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "오디오 데이터를 읽는 중 오류 발생: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // FLAC 데이터를 디코딩하여 USB로 전송
    public void readAndTransferFLACData(String filePath) {
        new Thread(() -> {
            try (InputStream is = new FileInputStream(new File(filePath))) {
                FLACDecoder decoder = new FLACDecoder(is);
                StreamInfo streamInfo = decoder.readStreamInfo();
                Frame frame;

                while ((frame = decoder.readNextFrame()) != null) {
                    ByteData pcmData = new ByteData(frame.header.blockSize * 2);
                    decoder.decodeFrame(frame, pcmData);
                    sendDataToUsb(pcmData.getData());
                }
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "FLAC 데이터를 읽는 중 오류 발생: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // USB BroadcastReceiver - 장치 연결 및 권한 처리
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            connection = usbManager.openDevice(device);
                            UsbInterface usbInterface = device.getInterface(0);
                            connection.claimInterface(usbInterface, true);
                            endpoint = usbInterface.getEndpoint(0);
                        }
                    } else {
                        Toast.makeText(context, "장치에 대한 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show();
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                requestPermission(device);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (connection != null) {
                    connection.close();
                }
            }
        }
    };

    // USB 장치 권한 요청
    private void requestPermission(UsbDevice device) {
        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
        usbManager.requestPermission(device, permissionIntent);
    }

    // WebApp 인터페이스 - 웹 콘텐츠와 상호작용
    public class WebAppInterface {
        MainActivity mActivity;

        WebAppInterface(MainActivity activity) {
            mActivity = activity;
        }

        @JavascriptInterface
        public void transferAudioData(String filePath) {
            mActivity.readAndTransferAudioData(filePath);
        }

        @JavascriptInterface
        public void transferFLACData(String filePath) {
            mActivity.readAndTransferFLACData(filePath);
        }
    }


}
